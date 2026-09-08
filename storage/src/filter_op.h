#ifndef STORAGE_FILTER_OP_H
#define STORAGE_FILTER_OP_H

#include "nlohmann/json.hpp"

// 按条件过滤：返回空数据集（默认成功）
nlohmann::json execute_filter(const nlohmann::json& plan);

#endif
