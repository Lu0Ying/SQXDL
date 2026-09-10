#include "delete_table_op.h"

#include "core/database.h"
#include "core/storage_error.h"

// 删表：从 Database catalog 中移除表；表不存在抛 StorageError(TABLE_NOT_FOUND)
nlohmann::json execute_delete_table(const nlohmann::json &plan)
{
    try
    {
        if (!plan.contains("table") || !plan.at("table").is_string() ||
            plan.at("table").get<std::string>().empty())
        {
            throw StorageError("INVALID_PLAN", "缺少或非法的字符串字段 table");
        }
        Database::instance().drop_table(plan.at("table").get<std::string>());
    }
    catch (const StorageError &e)
    {
        return {{"success", false},
                {"type", "error"},
                {"error", {{"code", e.code()}, {"message", e.what()}}}};
    }
    return {{"success", true}, {"type", "rowcount"}, {"rowsAffected", 0}};
}
