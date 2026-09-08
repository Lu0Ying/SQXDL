#include <iostream>
#include "nlohmann/json.hpp"

#include "scan_op.h"
#include "filter_op.h"
#include "project_op.h"
#include "insert_op.h"
#include "update_op.h"
#include "delete_op.h"
#include "create_table_op.h"
#include "show_tables_op.h"
#include "delete_table_op.h"

int main()
{
    std::string physic_plan_string((std::istreambuf_iterator<char>(std::cin)),
                                   std::istreambuf_iterator<char>());

    if (physic_plan_string.empty())
    {
        std::cerr << "Error: Expected physic plan JSON on stdin" << std::endl;
        return 1;
    }

    nlohmann::json physic_plan = nlohmann::json::parse(physic_plan_string);

    std::string op = physic_plan.value("op", "");

    nlohmann::json result;
    if (op == "scan")
    {
        result = execute_scan(physic_plan);
    }
    else if (op == "filter")
    {
        result = execute_filter(physic_plan);
    }
    else if (op == "project")
    {
        result = execute_project(physic_plan);
    }
    else if (op == "insert")
    {
        result = execute_insert(physic_plan);
    }
    else if (op == "update")
    {
        result = execute_update(physic_plan);
    }
    else if (op == "delete")
    {
        result = execute_delete(physic_plan);
    }
    else if (op == "createTable")
    {
        result = execute_create_table(physic_plan);
    }
    else if (op == "showTables")
    {
        result = execute_show_tables(physic_plan);
    }
    else if (op == "deleteTable")
    {
        result = execute_delete_table(physic_plan);
    }
    else
    {
        result = {
            {"success", false},
            {"type", "error"},
            {"error", {{"code", "INVALID_PLAN"}, {"message", "Unknown operation type"}}}};
    }

    std::cout << result.dump() << std::endl;
    return 0;
}
