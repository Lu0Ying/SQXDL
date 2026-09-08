#include "show_tables_op.h"

nlohmann::json execute_show_tables(const nlohmann::json& plan)
{
    return {
        {"success", true},
        {"type", "resultset"},
        {"columns", nlohmann::json::array({"table"})},
        {"rows", nlohmann::json::array()}
    };
}
