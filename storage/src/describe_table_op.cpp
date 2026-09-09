#include "describe_table_op.h"

#include "core/database.h"
#include "core/storage_error.h"

// 查看表结构：返回单列 field 的数据集，按建表顺序列出列名；
// 表不存在由 Database 抛 StorageError(TABLE_NOT_FOUND)，捕获后转为 error JSON
nlohmann::json execute_describe_table(const nlohmann::json &plan)
{
    try
    {
        if (!plan.contains("table") || !plan["table"].is_string())
        {
            throw StorageError("INVALID_PLAN", "describeTable 需要字符串字段 table");
        }
        const Database &database = Database::instance();
        const Table &table = database.get_table(plan["table"].get<std::string>());

        nlohmann::json rows = nlohmann::json::array();
        for (const std::string &name : table.column_names())
        {
            rows.push_back(nlohmann::json::array({name}));
        }
        return {
            {"success", true},
            {"type", "resultset"},
            {"columns", nlohmann::json::array({"field"})},
            {"rows", rows}};
    }
    catch (const StorageError &e)
    {
        return {
            {"success", false},
            {"type", "error"},
            {"error", {{"code", e.code()}, {"message", e.what()}}}};
    }
}
