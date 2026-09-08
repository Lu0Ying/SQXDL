#ifndef STORAGE_DELETE_OP_H
#define STORAGE_DELETE_OP_H

#include "nlohmann/json.hpp"

// 删除满足条件的行：返回受影响行数（默认成功）
nlohmann::json execute_delete(const nlohmann::json& plan);

#endif
