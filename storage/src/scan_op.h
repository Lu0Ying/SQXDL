#ifndef STORAGE_SCAN_OP_H
#define STORAGE_SCAN_OP_H

#include "nlohmann/json.hpp"

// 全表扫描：返回空数据集（默认成功）
nlohmann::json execute_scan(const nlohmann::json& plan);

#endif
