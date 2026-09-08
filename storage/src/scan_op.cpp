#include "scan_op.h"

nlohmann::json execute_scan(const nlohmann::json& plan)
{
    return {
        {"success", true},
        {"type", "resultset"},
        {"columns", nlohmann::json::array()},
        {"rows", nlohmann::json::array()}
    };
}
