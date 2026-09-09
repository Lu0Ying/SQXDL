#include "row.h"

#include "storage_error.h"

Row::Row()
{
}

size_t Row::size() const
{
    return values_.size();
}

bool Row::empty() const
{
    return values_.empty();
}

const Value &Row::at(size_t index) const
{
    if (index >= values_.size())
    {
        throw StorageError("INTERNAL_ERROR", "行索引越界: " + std::to_string(index));
    }
    return values_[index];
}

Value &Row::at(size_t index)
{
    if (index >= values_.size())
    {
        throw StorageError("INTERNAL_ERROR", "行索引越界: " + std::to_string(index));
    }
    return values_[index];
}

void Row::append(Value value)
{
    values_.push_back(std::move(value));
}

Row Row::from_json(const nlohmann::json &array)
{
    if (!array.is_array())
    {
        throw StorageError("INVALID_PLAN", "行数据必须是 JSON 数组");
    }
    Row row;
    for (const auto &element : array)
    {
        row.append(Value::from_json(element));
    }
    return row;
}

nlohmann::json Row::to_json() const
{
    nlohmann::json array = nlohmann::json::array();
    for (const auto &value : values_)
    {
        array.push_back(value.to_json());
    }
    return array;
}
