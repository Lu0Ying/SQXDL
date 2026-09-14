#include "update_op.h"

#include <memory>
#include <utility>
#include <vector>

#include "core/database.h"
#include "core/expression.h"
#include "core/row_store.h"
#include "core/storage_error.h"
#include "core/table.h"

// 更新满足条件的行：set 为 列名 -> 新值 的映射，
// condition 可省略（作用于全表）；受影响行数 = 匹配行数；成功后落盘。
// 逐页流式读取行数据（不整表物化），命中行在所在数据页就地改写；
// 页内放不下时旧槽位置空、新元组追加到页链尾部，因此不重写整表
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
        const std::string table_name = plan.at("table").get<std::string>();
        const Table &table = Database::instance().get_table(table_name);

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
        RowStore &store = Database::instance().row_store();

        // 逆序扫描：行迁移时新元组只会追加到「已访问过的链尾页」或新页，
        // 因此每一行在本次更新中至多被处理一次，受影响行数不会重复计数
        std::unique_ptr<RowIterator> iterator = store.scan(table_name, true);
        Row row;
        while (iterator->next(row))
        {
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
            if (store.update_row(table_name, iterator->last_rid(), row))
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
