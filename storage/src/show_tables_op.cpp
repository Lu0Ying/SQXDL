#include "show_tables_op.h"

#include "core/database.h"
#include "core/storage_error.h"

// showTables：列出当前所有表名（数据集，单列 table）；
// 表目录从磁盘加载（见 Database），名称按字典序返回
nlohmann::json execute_show_tables(const nlohmann::json &plan)
{
    try
    {
        const std::vector<std::string> names = Database::instance().table_names();

        nlohmann::json columns = nlohmann::json::array();
        columns.push_back("table");

        nlohmann::json rows = nlohmann::json::array();
        for (const auto &name : names)
        {
            nlohmann::json row = nlohmann::json::array();
            row.push_back(name);
            rows.push_back(std::move(row));
        }

        return {
            {"success", true},
            {"type", "resultset"},
            {"columns", std::move(columns)},
            {"rows", std::move(rows)}
        };
    }
    catch (const StorageError &e)
    {
        return {{"success", false},
                {"type", "error"},
                {"error", {{"code", e.code()}, {"message", e.what()}}}};
    }
}
