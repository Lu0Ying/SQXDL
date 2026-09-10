#include "value.h"

#include <sstream>

#include "storage_error.h"

Value::Value() : type_(ValueType::Null), int_value_(0), double_value_(0.0), bool_value_(false)
{
}

Value::Value(std::nullptr_t) : Value()
{
}

Value::Value(int v) : Value(static_cast<int64_t>(v))
{
}

Value::Value(int64_t v)
    : type_(ValueType::Integer), int_value_(v), double_value_(0.0), bool_value_(false)
{
}

Value::Value(double v) : type_(ValueType::Double), int_value_(0), double_value_(v), bool_value_(false)
{
}

Value::Value(bool v) : type_(ValueType::Boolean), int_value_(0), double_value_(0.0), bool_value_(v)
{
}

Value::Value(const char *v) : Value(std::string(v))
{
}

Value::Value(std::string v)
    : type_(ValueType::String), int_value_(0), double_value_(0.0), bool_value_(false),
      string_value_(std::move(v))
{
}

ValueType Value::type() const
{
    return type_;
}

bool Value::is_null() const
{
    return type_ == ValueType::Null;
}

bool Value::is_number() const
{
    return type_ == ValueType::Integer || type_ == ValueType::Double;
}

Value Value::from_json(const nlohmann::json &j)
{
    if (j.is_null())
    {
        return Value(nullptr);
    }
    if (j.is_boolean())
    {
        return Value(j.get<bool>());
    }
    if (j.is_number_integer())
    {
        return Value(j.get<int64_t>());
    }
    if (j.is_number_float())
    {
        return Value(j.get<double>());
    }
    if (j.is_string())
    {
        return Value(j.get<std::string>());
    }
    throw StorageError("INVALID_PLAN",
                       "literal only supports JSON native types (number/string/boolean/null)");
}

nlohmann::json Value::to_json() const
{
    switch (type_)
    {
    case ValueType::Null:
        return nullptr;
    case ValueType::Integer:
        return int_value_;
    case ValueType::Double:
        return double_value_;
    case ValueType::String:
        return string_value_;
    case ValueType::Boolean:
        return bool_value_;
    }
    return nullptr; // 不可达，消除编译器警告
}

bool Value::equals(const Value &lhs, const Value &rhs)
{
    if (lhs.is_null() || rhs.is_null())
    {
        return false; // SQL：与 NULL 的等值比较结果未知，视为不匹配
    }
    if (lhs.is_number() && rhs.is_number())
    {
        if (lhs.type_ == ValueType::Integer && rhs.type_ == ValueType::Integer)
        {
            return lhs.int_value_ == rhs.int_value_;
        }
        return lhs.as_double() == rhs.as_double();
    }
    if (lhs.type_ != rhs.type_)
    {
        throw StorageError("TYPE_MISMATCH",
                            "Incompatible types: cannot compare " + lhs.to_string() +
                                " with " + rhs.to_string());
    }
    switch (lhs.type_)
    {
    case ValueType::String:
        return lhs.string_value_ == rhs.string_value_;
    case ValueType::Boolean:
        return lhs.bool_value_ == rhs.bool_value_;
    default:
        return false; // 不可达：数字族已在上方处理
    }
}

bool Value::less(const Value &lhs, const Value &rhs)
{
    if (lhs.is_null() || rhs.is_null())
    {
        return false;
    }
    if (lhs.is_number() && rhs.is_number())
    {
        if (lhs.type_ == ValueType::Integer && rhs.type_ == ValueType::Integer)
        {
            return lhs.int_value_ < rhs.int_value_;
        }
        return lhs.as_double() < rhs.as_double();
    }
    if (lhs.type_ != rhs.type_)
    {
        throw StorageError("TYPE_MISMATCH",
                            "Incompatible types: cannot compare " + lhs.to_string() +
                                " with " + rhs.to_string());
    }
    switch (lhs.type_)
    {
    case ValueType::String:
        return lhs.string_value_ < rhs.string_value_;
    case ValueType::Boolean:
        return !lhs.bool_value_ && rhs.bool_value_;
    default:
        return false; // 不可达：数字族已在上方处理
    }
}

bool Value::truth_value() const
{
    switch (type_)
    {
    case ValueType::Boolean:
        return bool_value_;
    case ValueType::Null:
        return false; // SQL：未知视为假
    default:
        throw StorageError("TYPE_MISMATCH", "Logical operations require boolean operands");
    }
}

std::string Value::to_string() const
{
    switch (type_)
    {
    case ValueType::Null:
        return "NULL";
    case ValueType::Integer:
        return std::to_string(int_value_);
    case ValueType::Double:
    {
        std::ostringstream oss;
        oss << double_value_;
        return oss.str();
    }
    case ValueType::String:
        return string_value_;
    case ValueType::Boolean:
        return bool_value_ ? "true" : "false";
    }
    return "";
}

double Value::as_double() const
{
    return type_ == ValueType::Integer ? static_cast<double>(int_value_) : double_value_;
}
