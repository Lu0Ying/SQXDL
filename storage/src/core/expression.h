#ifndef STORAGE_CORE_EXPRESSION_H
#define STORAGE_CORE_EXPRESSION_H

#include <memory>
#include <string>
#include <vector>

#include "nlohmann/json.hpp"
#include "row.h"
#include "value.h"

// 求值上下文：列名 -> 当前行字段的解析环境
// （持有引用，由调用方保证在单次求值期间存活）
class EvalContext
{
public:
    EvalContext(const std::vector<std::string> &column_names, const Row &row);

    // 列解析规则：
    // 1) 精确匹配 schema 列名（join 重名列的 schema 名本身形如 "student.name"）；
    // 2) 失败时按点后缀宽容匹配——带点查询（teacher.tid）匹配裸名 tid 或任意
    //    "表.tid"；裸名查询（tid）匹配任意 "表.tid"。命中必须唯一，多个命中
    //    抛 StorageError(INVALID_PLAN) 报歧义；仍无命中抛 StorageError(COLUMN_NOT_FOUND)
    const Value &resolve(const std::string &column_name) const;

private:
    const std::vector<std::string> &column_names_;
    const Row &row_;
};

// 在列名表中定位列（供 EvalContext::resolve 与 project 投影共用）：
// 先精确匹配，再按点后缀宽容匹配。返回命中下标；唯一宽容命中返回其下标，
// 多个命中返回 -2（歧义），无命中返回 -1。
int find_column_index(const std::vector<std::string> &column_names, const std::string &column_name);

// 条件表达式基类：对应 readme 1.2 中 condition 树的节点
class Expression
{
public:
    virtual ~Expression() = default;

    // 对当前行求值：比较/逻辑运算返回 Boolean，列引用返回该列的字段值
    virtual Value evaluate(const EvalContext &ctx) const = 0;

    // 调试用结构化输出
    virtual std::string to_string() const = 0;
};

using ExpressionPtr = std::shared_ptr<const Expression>;

// 列引用节点（type = column）
class ColumnRefExpression : public Expression
{
public:
    explicit ColumnRefExpression(std::string name);

    Value evaluate(const EvalContext &ctx) const override;
    std::string to_string() const override;

private:
    std::string name_;
};

// 字面量节点（type = literal）：value 为 JSON 原生类型
class LiteralExpression : public Expression
{
public:
    explicit LiteralExpression(Value value);

    Value evaluate(const EvalContext &ctx) const override;
    std::string to_string() const override;

private:
    Value value_;
};

// 二元运算节点（type = binary）：
// 比较运算 =  !=  <>  <  <=  >  >= ；逻辑运算 AND / OR（大小写不敏感）；
// 算术运算 +  -  *  /
class BinaryExpression : public Expression
{
public:
    BinaryExpression(std::string op, ExpressionPtr left, ExpressionPtr right);

    Value evaluate(const EvalContext &ctx) const override;
    std::string to_string() const override;

private:
    std::string op_;
    ExpressionPtr left_;
    ExpressionPtr right_;
};

// 从 condition JSON 构建表达式树（递归解析 column/literal/binary 节点），
// 结构非法或运算符不支持抛 StorageError(INVALID_PLAN)
ExpressionPtr parse_expression(const nlohmann::json &j);

#endif
