#ifndef STORAGE_CORE_COLUMN_H
#define STORAGE_CORE_COLUMN_H

#include <string>
#include <vector>

#include "nlohmann/json.hpp"

// 列定义：列名 + SQL 类型名（INT / VARCHAR / BOOLEAN / DOUBLE）
struct Column
{
    std::string name;
    std::string type;
};

// 从 JSON 列定义数组解析列定义，每个元素为对象 {"name": 列名, "type": 类型}；
// 非对象元素、空列名、重复列名、非法类型或空数组抛 StorageError(INVALID_PLAN)
std::vector<Column> columns_from_json(const nlohmann::json &array);

#endif
