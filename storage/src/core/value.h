#ifndef STORAGE_CORE_VALUE_H
#define STORAGE_CORE_VALUE_H

#include <cstdint>
#include <string>

#include "nlohmann/json.hpp"

// 单元格值类型：覆盖 readme 中 literal 允许的 JSON 原生类型
enum class ValueType
{
    Null,    // JSON null
    Integer, // JSON 整数（含负数）
    Double,  // JSON 浮点数
    String,  // JSON 字符串
    Boolean  // JSON 布尔
};

// 单元格值：行中每个字段、字面量的统一承载类型（不可变语义）
class Value
{
public:
    Value();                  // Null
    Value(std::nullptr_t);    // Null
    explicit Value(int v);    // Integer（整数字面量最常用入口）
    explicit Value(int64_t v); // Integer
    explicit Value(double v); // Double
    explicit Value(bool v);   // Boolean
    explicit Value(const char *v); // String
    explicit Value(std::string v); // String

    ValueType type() const;
    bool is_null() const;
    bool is_number() const; // Integer 或 Double

    // JSON 原生类型 -> Value；对象/数组等其他 JSON 类型抛 StorageError(INVALID_PLAN)
    static Value from_json(const nlohmann::json &j);
    nlohmann::json to_json() const;

    // 相等比较（用于 =）：数字族可跨 Integer/Double 比较，其余要求类型一致
    // （否则抛 StorageError(TYPE_MISMATCH)）；
    // 任一方为 Null 时按 SQL 三值逻辑返回 false（结果未知）
    static bool equals(const Value &lhs, const Value &rhs);

    // 小于比较（用于 <）：数字族内部可比较，字符串按字典序，布尔 false < true；
    // 类型不兼容抛 StorageError(TYPE_MISMATCH)，任一方为 Null 返回 false
    static bool less(const Value &lhs, const Value &rhs);

    // 二元算术（+ - * /）：两操作数须为数字，否则抛 StorageError(TYPE_MISMATCH)；
    // 两整数运算返回整数，任一方为浮点则返回浮点；除数为 0 时返回 Null（结果未知）
    static Value arith(const std::string &op, const Value &lhs, const Value &rhs);

    // 逻辑真值（AND/OR 等逻辑运算的操作数）：
    // Boolean 取其值；Null 按 SQL 三值逻辑视为未知（false）；
    // 其他类型抛 StorageError(TYPE_MISMATCH)
    bool truth_value() const;

    // 调试/展示输出：字符串原样输出（不带引号），Null 输出 NULL
    std::string to_string() const;

private:
    // 仅在 is_number() 为 true 时有效
    double as_double() const;

    ValueType type_;
    int64_t int_value_;
    double double_value_;
    bool bool_value_;
    std::string string_value_;
};

#endif
