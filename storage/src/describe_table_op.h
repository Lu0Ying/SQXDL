#ifndef STORAGE_DESCRIBE_TABLE_OP_H
#define STORAGE_DESCRIBE_TABLE_OP_H

#include "nlohmann/json.hpp"

// describeTable：查看表结构，返回 column + type 两列数据集（按建表顺序列出列名与类型）
nlohmann::json execute_describe_table(const nlohmann::json &plan);

#endif
