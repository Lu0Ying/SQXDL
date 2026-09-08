#include "project_op.h"

nlohmann::json execute_project(const nlohmann::json& plan)
{
    nlohmann::json columns = plan.value("columns", nlohmann::json::array());
    return {
        {"success", true},
        {"type", "resultset"},
        {"columns", columns},
        {"rows", nlohmann::json::array()}
    };
}
