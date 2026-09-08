#include "delete_table_op.h"

nlohmann::json execute_delete_table(const nlohmann::json& plan)
{
    return {
        {"success", true},
        {"type", "rowcount"},
        {"rowsAffected", 0}
    };
}
