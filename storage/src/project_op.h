#ifndef STORAGE_PROJECT_OP_H
#define STORAGE_PROJECT_OP_H

#include "nlohmann/json.hpp"

// 投影指定列：返回空数据集，列清单取 plan 中的 columns（默认成功）
nlohmann::json execute_project(const nlohmann::json& plan);

#endif
