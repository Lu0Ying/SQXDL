#ifndef STORAGE_PROJECT_OP_H
#define STORAGE_PROJECT_OP_H

#include "nlohmann/json.hpp"

// 投影指定列（对应 SELECT 列清单）：从 child 数据集选取并重排 columns，返回结果集
nlohmann::json execute_project(const nlohmann::json& plan);

#endif
