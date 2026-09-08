#ifndef STORAGE_CREATE_TABLE_OP_H
#define STORAGE_CREATE_TABLE_OP_H

#include "nlohmann/json.hpp"

// 建表：返回受影响行数 0（默认成功）
nlohmann::json execute_create_table(const nlohmann::json& plan);

#endif
