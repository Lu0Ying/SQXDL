#ifndef STORAGE_SHOW_TABLES_OP_H
#define STORAGE_SHOW_TABLES_OP_H

#include "nlohmann/json.hpp"

// showTables：返回当前所有表名（数据集，单列 table）
nlohmann::json execute_show_tables(const nlohmann::json& plan);

#endif
