#include "delete_op.h"

#include <memory>

#include "core/database.h"
#include "core/expression.h"
#include "core/row_store.h"
#include "core/storage_error.h"
#include "core/table.h"

// 删除满足条件的行：condition 可省略（作用于全表）；
// 受影响行数 = 匹配行数；成功后落盘。
// 逐页流式读取行数据（不整表物化），命中行在所在页把槽位置空，
// 整页数据被删空时回收该页，因此不重写整表
nlohmann::json execute_delete(const nlohmann::json &plan)
{
    size_t affected = 0;
    try
    {
        if (!plan.contains("table") || !plan.at("table").is_string() ||
            plan.at("table").get<std::string>().empty())
        {
            throw StorageError("INVALID_PLAN", "Missing or invalid string field: table");
        }
        const std::string table_name = plan.at("table").get<std::string>();
        const Table &table = Database::instance().get_table(table_name);

        // condition 可省略；省略时删除全表行
        ExpressionPtr condition = plan.contains("condition")
                                      ? parse_expression(plan.at("condition"))
                                      : nullptr;
        const std::vector<std::string> names = table.column_names();
        RowStore &store = Database::instance().row_store();

        std::unique_ptr<RowIterator> iterator = store.scan(table_name);
        Row row;
        while (iterator->next(row))
        {
            bool matched = true;
            if (condition)
            {
                const EvalContext ctx(names, row);
                matched = condition->evaluate(ctx).truth_value();
            }
            // 已被删除的槽位返回 false，不计入受影响行数
            if (matched && store.delete_row(table_name, iterator->last_rid()))
            {
                ++affected;
            }
        }

        Database::instance().flush();
    }
    catch (const StorageError &e)
    {
        return {{"success", false},
                {"type", "error"},
                {"error", {{"code", e.code()}, {"message", e.what()}}}};
    }
    return {{"success", true}, {"type", "rowcount"}, {"rowsAffected", affected}};
}
