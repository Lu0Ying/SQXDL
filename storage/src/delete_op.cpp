#include "delete_op.h"

#include "core/database.h"
#include "core/expression.h"
#include "core/storage_error.h"
#include "core/table.h"

// 删除满足条件的行：condition 可省略（作用于全表）；
// 受影响行数 = 匹配行数；成功后落盘
nlohmann::json execute_delete(const nlohmann::json &plan)
{
    size_t affected = 0;
    try
    {
        if (!plan.contains("table") || !plan.at("table").is_string() ||
            plan.at("table").get<std::string>().empty())
        {
            throw StorageError("INVALID_PLAN", "缺少或非法的字符串字段 table");
        }
        Table &table = Database::instance().get_table(plan.at("table").get<std::string>());

        // condition 可省略；省略时删除全表行
        ExpressionPtr condition = plan.contains("condition")
                                      ? parse_expression(plan.at("condition"))
                                      : nullptr;
        const std::vector<std::string> names = table.column_names();

        // 从后往前删除，避免索引因删除而偏移
        for (size_t i = table.row_count(); i > 0; --i)
        {
            const size_t index = i - 1;
            bool matched = true;
            if (condition)
            {
                const EvalContext ctx(names, table.row(index));
                matched = condition->evaluate(ctx).truth_value();
            }
            if (matched)
            {
                table.remove_row(index);
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
