#ifndef STORAGE_SCAN_OP_H
#define STORAGE_SCAN_OP_H

#include "nlohmann/json.hpp"

// 全表扫描：返回整表数据（数据集，列 = 表列、行 = 表行）
nlohmann::json execute_scan(const nlohmann::json& plan);

#endif
