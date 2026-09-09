#include "column.h"

#include <set>

#include "storage_error.h"

std::vector<Column> columns_from_json(const nlohmann::json &array)
{
    if (!array.is_array())
    {
        throw StorageError("INVALID_PLAN", "columns 必须是字符串数组");
    }
    std::vector<Column> columns;
    std::set<std::string> seen;
    for (const auto &element : array)
    {
        if (!element.is_string())
        {
            throw StorageError("INVALID_PLAN", "列名必须是字符串");
        }
        std::string name = element.get<std::string>();
        if (name.empty())
        {
            throw StorageError("INVALID_PLAN", "列名不能为空");
        }
        if (!seen.insert(name).second)
        {
            throw StorageError("INVALID_PLAN", "重复的列名: " + name);
        }
        columns.push_back(Column{name});
    }
    if (columns.empty())
    {
        throw StorageError("INVALID_PLAN", "至少需要定义一个列");
    }
    return columns;
}
