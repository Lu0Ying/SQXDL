#ifndef STORAGE_INSERT_OP_H
#define STORAGE_INSERT_OP_H

#include "nlohmann/json.hpp"

// 插入一行：返回受影响行数 1（默认成功）
nlohmann::json execute_insert(const nlohmann::json& plan);

#endif
