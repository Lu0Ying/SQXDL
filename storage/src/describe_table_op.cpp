#include "describe_table_op.h"

#include "core/database.h"
#include "core/storage_error.h"
#include "core/table.h"

// 查看表结构：返回列 column + type 的数据集，按建表顺序列出每列的列名与类型；
// 表不存在由 Database 抛 StorageError(TABLE_NOT_FOUND)，捕获后转为 error JSON
nlohmann::json execute_describe_table(const nlohmann::json &plan)
{
    try
    {
        if (!plan.contains("table") || !plan.at("table").is_string() ||
            plan.at("table").get<std::string>().empty())
        {
            throw StorageError("INVALID_PLAN", "Missing or invalid string field: table");
        }
        const Table &table = Database::instance().get_table(plan.at("table").get<std::string>());

        nlohmann::json columns = nlohmann::json::array();
        columns.push_back("column");
        columns.push_back("type");

        nlohmann::json rows = nlohmann::json::array();
        for (const auto &column : table.columns())
        {
            nlohmann::json row = nlohmann::json::array();
            row.push_back(column.name);
            row.push_back(column.type);
            rows.push_back(std::move(row));
        }

        return {
            {"success", true},
            {"type", "resultset"},
            {"columns", std::move(columns)},
            {"rows", std::move(rows)}};
    }
    catch (const StorageError &e)
    {
        return {{"success", false},
                {"type", "error"},
                {"error", {{"code", e.code()}, {"message", e.what()}}}};
    }
}
