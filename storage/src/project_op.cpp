#include "project_op.h"

#include <vector>

#include "core/storage_error.h"
#include "query_op.h"

// 投影指定列：递归执行 child 得到数据集，按 columns 选取并重排列；
// columns 中列不存在报 COLUMN_NOT_FOUND，子节点出错时原样传播其错误
nlohmann::json execute_project(const nlohmann::json &plan)
{
    try
    {
        if (!plan.is_object())
        {
            throw StorageError("INVALID_PLAN", "project 计划必须是 JSON 对象");
        }
        if (!plan.contains("child"))
        {
            throw StorageError("INVALID_PLAN", "project 缺少 child");
        }
        if (!plan.contains("columns") || !plan.at("columns").is_array() ||
            plan.at("columns").empty())
        {
            throw StorageError("INVALID_PLAN", "project 需要非空的 columns 数组");
        }
        const nlohmann::json &columns = plan.at("columns");
        for (const auto &column : columns)
        {
            if (!column.is_string())
            {
                throw StorageError("INVALID_PLAN", "project 的列名必须是字符串");
            }
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
            throw StorageError("INVALID_PLAN", "project 的 child 未返回合法数据集");
        }

        // 定位每一被投影列在 child 数据集中的下标
        const nlohmann::json &child_columns = child.at("columns");
        std::vector<size_t> indices;
        indices.reserve(columns.size());
        for (const auto &column : columns)
        {
            const std::string name = column.get<std::string>();
            size_t index = child_columns.size();
            for (size_t i = 0; i < child_columns.size(); ++i)
            {
                if (child_columns[i].is_string() && child_columns[i].get<std::string>() == name)
                {
                    index = i;
                    break;
                }
            }
            if (index == child_columns.size())
            {
                throw StorageError("COLUMN_NOT_FOUND", "列 " + name + " 不存在");
            }
            indices.push_back(index);
        }

        nlohmann::json projected = nlohmann::json::array();
        for (const auto &row_json : child.at("rows"))
        {
            if (!row_json.is_array() || row_json.size() != child_columns.size())
            {
                throw StorageError("INTERNAL_ERROR", "数据集行与列定义不一致");
            }
            nlohmann::json row = nlohmann::json::array();
            for (const size_t index : indices)
            {
                row.push_back(row_json.at(index));
            }
            projected.push_back(std::move(row));
        }

        return {
            {"success", true},
            {"type", "resultset"},
            {"columns", columns},
            {"rows", std::move(projected)}
        };
    }
    catch (const StorageError &e)
    {
        return {{"success", false},
                {"type", "error"},
                {"error", {{"code", e.code()}, {"message", e.what()}}}};
    }
}
