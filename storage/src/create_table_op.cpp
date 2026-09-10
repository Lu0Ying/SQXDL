#include "create_table_op.h"

#include "core/column.h"
#include "core/database.h"
#include "core/storage_error.h"

// 建表：在 Database catalog 中注册表；失败抛 StorageError，捕获后转为 error JSON
nlohmann::json execute_create_table(const nlohmann::json &plan)
{
    try
    {
        if (!plan.contains("table") || !plan.at("table").is_string() ||
            plan.at("table").get<std::string>().empty())
        {
            throw StorageError("INVALID_PLAN", "Missing or invalid string field: table");
        }
        const std::string table = plan.at("table").get<std::string>();

        std::vector<Column> columns = columns_from_json(plan.value("columns", nlohmann::json()));
        Database::instance().create_table(table, std::move(columns));
    }
    catch (const StorageError &e)
    {
        return {{"success", false},
                {"type", "error"},
                {"error", {{"code", e.code()}, {"message", e.what()}}}};
    }
    return {{"success", true}, {"type", "rowcount"}, {"rowsAffected", 0}};
}
