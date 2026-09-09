#include "create_table_op.h"

#include "core/column.h"
#include "core/database.h"
#include "core/storage_error.h"

// 建表：在 Database catalog 中注册表；失败抛 StorageError，捕获后转为 error JSON
nlohmann::json execute_create_table(const nlohmann::json &plan)
{
    try
    {
        if (!plan.contains("table") || !plan["table"].is_string())
        {
            throw StorageError("INVALID_PLAN", "createTable 需要字符串字段 table");
        }
        if (!plan.contains("columns"))
        {
            throw StorageError("INVALID_PLAN", "createTable 需要字段 columns");
        }
        Database::instance().create_table(plan["table"].get<std::string>(),
                                          columns_from_json(plan["columns"]));
    }
    catch (const StorageError &e)
    {
        return {
            {"success", false},
            {"type", "error"},
            {"error", {{"code", e.code()}, {"message", e.what()}}}};
    }
    return {
        {"success", true},
        {"type", "rowcount"},
        {"rowsAffected", 0}};
}
