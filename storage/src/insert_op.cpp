#include "insert_op.h"

nlohmann::json execute_insert(const nlohmann::json& plan)
{
    return {
        {"success", true},
        {"type", "rowcount"},
        {"rowsAffected", 1}
    };
}
