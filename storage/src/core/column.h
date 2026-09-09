#ifndef STORAGE_CORE_COLUMN_H
#define STORAGE_CORE_COLUMN_H

#include <string>
#include <vector>

#include "nlohmann/json.hpp"

// 列定义：当前 createTable 仅提供列名（readme 1.3），暂无类型约束
struct Column
{
    std::string name;
};

// 从 JSON 列名数组（如 ["id","name"]）解析列定义；
// 非字符串元素、空列名、重复列名或空数组抛 StorageError(INVALID_PLAN)
std::vector<Column> columns_from_json(const nlohmann::json &array);

#endif
