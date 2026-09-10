#ifndef STORAGE_QUERY_OP_H
#define STORAGE_QUERY_OP_H

#include "nlohmann/json.hpp"

// 查询节点分发：按 op 递归执行查询树节点（scan / filter / project）。
// 供 filter / project 执行其 child 子树，也供 storage_core 执行查询计划根节点。
nlohmann::json execute_query_node(const nlohmann::json &plan);

#endif
