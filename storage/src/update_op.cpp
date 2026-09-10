#include "update_op.h"

#include <utility>
#include <vector>

#include "core/database.h"
#include "core/expression.h"
#include "core/storage_error.h"
#include "core/table.h"

// 更新满足条件的行：set 为 列名 -> 新值 的映射，
// condition 可省略（作用于全表）；受影响行数 = 匹配行数；成功后落盘
nlohmann::json execute_update(const nlohmann::json &plan)
{
    size_t affected = 0;
    try
    {
        if (!plan.contains("table") || !plan.at("table").is_string() ||
            plan.at("table").get<std::string>().empty())
        {
            throw StorageError("INVALID_PLAN", "Missing or invalid string field: table");
        }
        Table &table = Database::instance().get_table(plan.at("table").get<std::string>());

        if (!plan.contains("set") || !plan.at("set").is_object() || plan.at("set").empty())
        {
            throw StorageError("INVALID_PLAN", "update is missing a non-empty set object");
        }
        // 先校验列存在并解析新值，避免逐行更新中途失败产生部分更新
        std::vector<std::pair<size_t, Value>> assignments;
        for (auto it = plan.at("set").begin(); it != plan.at("set").end(); ++it)
        {
            const size_t index = table.column_index(it.key()); // 列不存在抛 COLUMN_NOT_FOUND
            assignments.emplace_back(index, Value::from_json(it.value()));
        }

        // condition 可省略；省略时作用于全表
        ExpressionPtr condition = plan.contains("condition")
                                      ? parse_expression(plan.at("condition"))
                                      : nullptr;
        const std::vector<std::string> names = table.column_names();

        for (size_t i = 0; i < table.row_count(); ++i)
        {
            Row &row = table.row(i);
            if (condition)
            {
                const EvalContext ctx(names, row);
                if (!condition->evaluate(ctx).truth_value())
                {
                    continue;
                }
            }
            for (const auto &assignment : assignments)
            {
                row.at(assignment.first) = assignment.second;
            }
            ++affected;
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
