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
        throw StorageError("INVALID_PLAN", "columns 必须是列定义数组");
    }
    std::vector<Column> columns;
    std::set<std::string> seen;
    for (const auto &element : array)
    {
        if (!element.is_object())
        {
            throw StorageError("INVALID_PLAN", "列定义必须是包含 name 与 type 的对象");
        }
        if (!element.contains("name") || !element.at("name").is_string())
        {
            throw StorageError("INVALID_PLAN", "列定义缺少字符串字段 name");
        }
        std::string name = element.at("name").get<std::string>();
        if (name.empty())
        {
            throw StorageError("INVALID_PLAN", "列名不能为空");
        }
        if (!seen.insert(name).second)
        {
            throw StorageError("INVALID_PLAN", "重复的列名: " + name);
        }
        if (!element.contains("type") || !element.at("type").is_string())
        {
            throw StorageError("INVALID_PLAN", "列 " + name + " 缺少字符串字段 type");
        }
        std::string type = normalize_type(element.at("type").get<std::string>());
        if (!is_valid_type(type))
        {
            throw StorageError("INVALID_PLAN", "不支持的列类型: " + type);
        }
        columns.push_back(Column{name, type});
    }
    if (columns.empty())
    {
        throw StorageError("INVALID_PLAN", "至少需要定义一个列");
    }
    return columns;
}
