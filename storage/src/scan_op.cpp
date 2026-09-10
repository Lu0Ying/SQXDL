#include "scan_op.h"

#include "core/database.h"
#include "core/storage_error.h"
#include "core/table.h"

// 全表扫描（查询树叶子节点）：返回整表数据（结果集，列 = 表列、行 = 表行）
nlohmann::json execute_scan(const nlohmann::json &plan)
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
        for (const auto &column : table.columns())
        {
            columns.push_back(column.name);
        }
        nlohmann::json rows = nlohmann::json::array();
        for (const auto &row : table.rows())
        {
            rows.push_back(row.to_json());
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
