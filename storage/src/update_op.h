#ifndef STORAGE_UPDATE_OP_H
#define STORAGE_UPDATE_OP_H

#include "nlohmann/json.hpp"

// 更新满足条件的行：返回受影响行数；写盘后持久化
nlohmann::json execute_update(const nlohmann::json& plan);

#endif
