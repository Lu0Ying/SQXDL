#include "delete_op.h"

nlohmann::json execute_delete(const nlohmann::json& plan)
{
    return {
        {"success", true},
        {"type", "rowcount"},
        {"rowsAffected", 0}
    };
}
