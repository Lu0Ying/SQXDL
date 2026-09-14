#include "insert_op.h"

#include "core/database.h"
#include "core/row.h"
#include "core/row_store.h"
#include "core/storage_error.h"
#include "core/table.h"

// 插入一行：columns 可省略（按建表顺序对应 values），
// 提供时按列名把 values 映射到对应列，未提及的列置 NULL；
// 行以页内元组形式增量追加到该表数据页链，不做整表重写，成功后落盘
nlohmann::json execute_insert(const nlohmann::json &plan)
{
    try
    {
        if (!plan.contains("table") || !plan.at("table").is_string() ||
            plan.at("table").get<std::string>().empty())
        {
            throw StorageError("INVALID_PLAN", "Missing or invalid string field: table");
        }
        const std::string table_name = plan.at("table").get<std::string>();
        const Table &table = Database::instance().get_table(table_name);

        if (!plan.contains("values") || !plan.at("values").is_array())
        {
            throw StorageError("INVALID_PLAN", "insert is missing values array");
        }
        Row row = Row::from_json(plan.at("values"));

        // columns 提供时按列名重映射，未提及的列填 NULL
        const nlohmann::json &columns = plan.value("columns", nlohmann::json());
        if (columns.is_array() && !columns.empty())
        {
            if (row.size() != columns.size())
            {
                throw StorageError("INVALID_PLAN", "columns and values have different sizes");
            }
            Row full_row; // 先按表列数填 NULL，再按列名赋值
            for (size_t i = 0; i < table.columns().size(); ++i)
            {
                full_row.append(Value());
            }
            for (size_t i = 0; i < columns.size(); ++i)
            {
                if (!columns[i].is_string())
                {
                    throw StorageError("INVALID_PLAN", "columns elements must be column names");
                }
                const size_t index = table.column_index(columns[i].get<std::string>());
                full_row.at(index) = row.at(i);
            }
            row = std::move(full_row);
        }
        if (row.size() != table.columns().size())
        {
            throw StorageError("INTERNAL_ERROR",
                               "Insert value count " + std::to_string(row.size()) +
                                   " does not match column count " +
                                   std::to_string(table.columns().size()));
        }

        Database::instance().row_store().insert_row(table_name, row);
        Database::instance().flush();
    }
    catch (const StorageError &e)
    {
        return {{"success", false},
                {"type", "error"},
                {"error", {{"code", e.code()}, {"message", e.what()}}}};
    }
    return {{"success", true}, {"type", "rowcount"}, {"rowsAffected", 1}};
}
