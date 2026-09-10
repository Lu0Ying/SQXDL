#include "column.h"

#include <cctype>
#include <set>

#include "storage_error.h"

namespace
{
// 类型名归一化为大写，并校验是否为受支持的列类型
std::string normalize_type(std::string type)
{
    for (char &c : type)
    {
        c = static_cast<char>(std::toupper(static_cast<unsigned char>(c)));
    }
    return type;
}

bool is_valid_type(const std::string &type)
{
    return type == "INT" || type == "VARCHAR" || type == "BOOLEAN" || type == "DOUBLE";
}
} // namespace

std::vector<Column> columns_from_json(const nlohmann::json &array)
{
    if (!array.is_array())
    {
        throw StorageError("INVALID_PLAN", "columns must be an array of column definitions");
    }
    std::vector<Column> columns;
    std::set<std::string> seen;
    for (const auto &element : array)
    {
        if (!element.is_object())
        {
            throw StorageError("INVALID_PLAN", "Column definition must be an object with name and type");
        }
        if (!element.contains("name") || !element.at("name").is_string())
        {
            throw StorageError("INVALID_PLAN", "Column definition is missing string field: name");
        }
        std::string name = element.at("name").get<std::string>();
        if (name.empty())
        {
            throw StorageError("INVALID_PLAN", "Column name cannot be empty");
        }
        if (!seen.insert(name).second)
        {
            throw StorageError("INVALID_PLAN", "Duplicate column name: " + name);
        }
        if (!element.contains("type") || !element.at("type").is_string())
        {
            throw StorageError("INVALID_PLAN", "Column " + name + " is missing string field: type");
        }
        std::string type = normalize_type(element.at("type").get<std::string>());
        if (!is_valid_type(type))
        {
            throw StorageError("INVALID_PLAN", "Unsupported column type: " + type);
        }
        columns.push_back(Column{name, type});
    }
    if (columns.empty())
    {
        throw StorageError("INVALID_PLAN", "At least one column must be defined");
    }
    return columns;
}
