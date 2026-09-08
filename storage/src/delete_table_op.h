#ifndef STORAGE_DELETE_TABLE_OP_H
#define STORAGE_DELETE_TABLE_OP_H

#include "nlohmann/json.hpp"

// deleteTable：删表（DROP TABLE），返回受影响行数 0（默认成功）
nlohmann::json execute_delete_table(const nlohmann::json& plan);

#endif
