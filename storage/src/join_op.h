#ifndef STORAGE_JOIN_OP_H
#define STORAGE_JOIN_OP_H

#include "nlohmann/json.hpp"

// 连接两个数据集（对应 JOIN）：left / right 为两个子计划，type 指定连接类型
// （inner / left / right / cross，默认 inner），condition 为连接条件
// （cross 无需条件，其余必填），返回结果集
nlohmann::json execute_join(const nlohmann::json &plan);

#endif
