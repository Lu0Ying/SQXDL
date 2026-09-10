#include "filter_op.h"

#include <vector>

#include "core/expression.h"
#include "core/row.h"
#include "core/storage_error.h"
#include "query_op.h"

// 按条件过滤：递归执行 child 得到数据集，用 condition 逐行求值，保留为真的行；
// 列清单与 child 一致。子节点出错时原样传播其错误
nlohmann::json execute_filter(const nlohmann::json &plan)
{
    try
    {
        if (!plan.is_object())
        {
            throw StorageError("INVALID_PLAN", "filter 计划必须是 JSON 对象");
        }
        if (!plan.contains("child"))
        {
            throw StorageError("INVALID_PLAN", "filter 缺少 child");
        }
        if (!plan.contains("condition"))
        {
            throw StorageError("INVALID_PLAN", "filter 缺少 condition");
        }

        nlohmann::json child = execute_query_node(plan.at("child"));
        if (!child.is_object() || !child.value("success", false))
        {
            return child; // 传播 child 的错误结果
        }
        if (child.value("type", "") != "resultset" || !child.contains("columns") ||
            !child.contains("rows") || !child.at("columns").is_array() ||
            !child.at("rows").is_array())
        {
            throw StorageError("INVALID_PLAN", "filter 的 child 未返回合法数据集");
        }

        ExpressionPtr condition = parse_expression(plan.at("condition"));

        std::vector<std::string> names;
        const nlohmann::json &columns = child.at("columns");
        names.reserve(columns.size());
        for (const auto &column : columns)
        {
            if (!column.is_string())
            {
                throw StorageError("INVALID_PLAN", "数据集列名必须是字符串");
            }
            names.push_back(column.get<std::string>());
        }

        nlohmann::json filtered = nlohmann::json::array();
        for (const auto &row_json : child.at("rows"))
        {
            const Row row = Row::from_json(row_json);
            const EvalContext ctx(names, row);
            if (condition->evaluate(ctx).truth_value())
            {
                filtered.push_back(row_json);
            }
        }

        return {
            {"success", true},
            {"type", "resultset"},
            {"columns", columns},
            {"rows", std::move(filtered)}
        };
    }
    catch (const StorageError &e)
    {
        return {{"success", false},
                {"type", "error"},
                {"error", {{"code", e.code()}, {"message", e.what()}}}};
    }
}
