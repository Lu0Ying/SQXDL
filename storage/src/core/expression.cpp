#include "expression.h"

#include "storage_error.h"

EvalContext::EvalContext(const std::vector<std::string> &column_names, const Row &row)
    : column_names_(column_names), row_(row)
{
}

const Value &EvalContext::resolve(const std::string &column_name) const
{
    for (size_t i = 0; i < column_names_.size(); ++i)
    {
        if (column_names_[i] == column_name)
        {
            return row_.at(i);
        }
    }
    throw StorageError("COLUMN_NOT_FOUND", "列 " + column_name + " 不存在");
}

ColumnRefExpression::ColumnRefExpression(std::string name) : name_(std::move(name))
{
}

Value ColumnRefExpression::evaluate(const EvalContext &ctx) const
{
    return ctx.resolve(name_);
}

std::string ColumnRefExpression::to_string() const
{
    return name_;
}

LiteralExpression::LiteralExpression(Value value) : value_(std::move(value))
{
}

Value LiteralExpression::evaluate(const EvalContext &) const
{
    return value_;
}

std::string LiteralExpression::to_string() const
{
    return value_.to_string();
}

BinaryExpression::BinaryExpression(std::string op, ExpressionPtr left, ExpressionPtr right)
    : op_(std::move(op)), left_(std::move(left)), right_(std::move(right))
{
}

Value BinaryExpression::evaluate(const EvalContext &ctx) const
{
    // 逻辑运算：两侧操作数须为布尔（Null 视为未知/假）
    if (op_ == "AND" || op_ == "OR")
    {
        const bool left = left_->evaluate(ctx).truth_value();
        const bool right = right_->evaluate(ctx).truth_value();
        return Value(op_ == "AND" ? (left && right) : (left || right));
    }

    // 比较运算：任一方为 Null 时结果未知，视为不匹配（对 != 亦然）
    const Value left = left_->evaluate(ctx);
    const Value right = right_->evaluate(ctx);
    if (left.is_null() || right.is_null())
    {
        return Value(false);
    }
    if (op_ == "=")
    {
        return Value(Value::equals(left, right));
    }
    if (op_ == "!=" || op_ == "<>")
    {
        return Value(!Value::equals(left, right));
    }
    if (op_ == "<")
    {
        return Value(Value::less(left, right));
    }
    if (op_ == "<=")
    {
        return Value(!Value::less(right, left));
    }
    if (op_ == ">")
    {
        return Value(Value::less(right, left));
    }
    if (op_ == ">=")
    {
        return Value(!Value::less(left, right));
    }
    throw StorageError("INVALID_PLAN", "不支持的二元运算符: " + op_);
}

std::string BinaryExpression::to_string() const
{
    return "(" + left_->to_string() + " " + op_ + " " + right_->to_string() + ")";
}

namespace
{
// 运算符归一化：AND/OR 大小写不敏感，其余精确匹配
std::string normalize_op(const std::string &op)
{
    if (op == "and")
    {
        return "AND";
    }
    if (op == "or")
    {
        return "OR";
    }
    return op;
}
} // namespace

ExpressionPtr parse_expression(const nlohmann::json &j)
{
    if (!j.is_object())
    {
        throw StorageError("INVALID_PLAN", "condition 节点必须是 JSON 对象");
    }
    const std::string type = j.value("type", "");
    if (type == "column")
    {
        if (!j.contains("name") || !j.at("name").is_string())
        {
            throw StorageError("INVALID_PLAN", "column 节点缺少字符串字段 name");
        }
        return std::make_shared<ColumnRefExpression>(j.at("name").get<std::string>());
    }
    if (type == "literal")
    {
        if (!j.contains("value"))
        {
            throw StorageError("INVALID_PLAN", "literal 节点缺少字段 value");
        }
        return std::make_shared<LiteralExpression>(Value::from_json(j.at("value")));
    }
    if (type == "binary")
    {
        if (!j.contains("op") || !j.at("op").is_string())
        {
            throw StorageError("INVALID_PLAN", "binary 节点缺少字符串字段 op");
        }
        const std::string op = normalize_op(j.at("op").get<std::string>());
        if (op != "=" && op != "!=" && op != "<>" && op != "<" && op != "<=" &&
            op != ">" && op != ">=" && op != "AND" && op != "OR")
        {
            throw StorageError("INVALID_PLAN", "不支持的二元运算符: " + op);
        }
        if (!j.contains("left") || !j.contains("right"))
        {
            throw StorageError("INVALID_PLAN", "binary 节点缺少 left/right 子表达式");
        }
        ExpressionPtr left = parse_expression(j.at("left"));
        ExpressionPtr right = parse_expression(j.at("right"));
        return std::make_shared<BinaryExpression>(op, std::move(left), std::move(right));
    }
    throw StorageError("INVALID_PLAN", "未知 condition 节点 type: " + type);
}
