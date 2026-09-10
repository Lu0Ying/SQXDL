#include "query_op.h"

#include "core/storage_error.h"
#include "filter_op.h"
#include "join_op.h"
#include "project_op.h"
#include "scan_op.h"

nlohmann::json execute_query_node(const nlohmann::json &plan)
{
    if (!plan.is_object())
    {
        throw StorageError("INVALID_PLAN", "Query node must be a JSON object");
    }
    const std::string op = plan.value("op", "");
    if (op == "scan")
    {
        return execute_scan(plan);
    }
    if (op == "filter")
    {
        return execute_filter(plan);
    }
    if (op == "project")
    {
        return execute_project(plan);
    }
    if (op == "join")
    {
        return execute_join(plan);
    }
    throw StorageError("INVALID_PLAN", "Invalid query node op: " + op);
}
