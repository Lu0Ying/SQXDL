#include "update_op.h"

nlohmann::json execute_update(const nlohmann::json& plan)
{
    return {
        {"success", true},
        {"type", "rowcount"},
        {"rowsAffected", 0}
    };
}
