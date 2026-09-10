#include "insert_op.h"

#include "core/database.h"
#include "core/storage_error.h"
#include "core/row.h"
#include "core/table.h"

// 插入一行：columns 可省略（按建表顺序对应 values），
// 提供时按列名把 values 映射到对应列，未提及的列置 NULL；成功后落盘
nlohmann::json execute_insert(const nlohmann::json &plan)
{
    try
    {
        if (!plan.contains("table") || !plan.at("table").is_string() ||
            plan.at("table").get<std::string>().empty())
        {
            throw StorageError("INVALID_PLAN", "缺少或非法的字符串字段 table");
        }
        Table &table = Database::instance().get_table(plan.at("table").get<std::string>());

        if (!plan.contains("values") || !plan.at("values").is_array())
        {
            throw StorageError("INVALID_PLAN", "insert 缺少 values 数组");
        }
        Row row = Row::from_json(plan.at("values"));

        // columns 提供时按列名重映射，未提及的列填 NULL
        const nlohmann::json &columns = plan.value("columns", nlohmann::json());
        if (columns.is_array() && !columns.empty())
        {
            if (row.size() != columns.size())
            {
                throw StorageError("INVALID_PLAN", "columns 与 values 数量不一致");
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
                    throw StorageError("INVALID_PLAN", "columns 元素必须是列名");
                }
                const size_t index = table.column_index(columns[i].get<std::string>());
                full_row.at(index) = row.at(i);
            }
            row = std::move(full_row);
        }

        table.append_row(std::move(row));
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
