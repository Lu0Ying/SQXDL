#ifndef STORAGE_FILTER_OP_H
#define STORAGE_FILTER_OP_H

#include "nlohmann/json.hpp"

// 按条件过滤（对应 WHERE）：对 child 数据集逐行求值 condition，返回结果集
nlohmann::json execute_filter(const nlohmann::json& plan);

#endif
