#include "project_op.h"

#include <memory>

#include "core/storage_error.h"
#include "row_source.h"

// 投影指定列（对应 SELECT 列清单）：在 child 行源上按 columns 抽选并重排列；
// columns 中列不存在报 COLUMN_NOT_FOUND。逐行投影，不物化 child 全量结果
nlohmann::json execute_project(const nlohmann::json &plan)
{
    try
    {
        if (!plan.is_object())
        {
            throw StorageError("INVALID_PLAN", "project plan must be a JSON object");
        }
        if (!plan.contains("child"))
        {
            throw StorageError("INVALID_PLAN", "project is missing child");
        }
        if (!plan.contains("columns") || !plan.at("columns").is_array() ||
            plan.at("columns").empty())
        {
            throw StorageError("INVALID_PLAN", "project requires a non-empty columns array");
        }
        const nlohmann::json &columns = plan.at("columns");
        for (const auto &column : columns)
        {
            if (!column.is_string())
            {
                throw StorageError("INVALID_PLAN", "project column names must be strings");
            }
        }

        std::unique_ptr<RowSource> source = build_row_source(plan);
        return drain_to_resultset(*source);
    }
    catch (const StorageError &e)
    {
        return {{"success", false},
                {"type", "error"},
                {"error", {{"code", e.code()}, {"message", e.what()}}}};
    }
}
