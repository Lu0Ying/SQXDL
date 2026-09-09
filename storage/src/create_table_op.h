#ifndef STORAGE_CREATE_TABLE_OP_H
#define STORAGE_CREATE_TABLE_OP_H

#include "nlohmann/json.hpp"

// 建表：按 plan 中 table 与 columns（含 name/type）在 catalog 注册表，返回受影响行数 0
nlohmann::json execute_create_table(const nlohmann::json& plan);

#endif
