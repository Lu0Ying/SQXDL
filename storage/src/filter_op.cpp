#include "filter_op.h"

#include <memory>

#include "core/storage_error.h"
#include "row_source.h"

// 按条件过滤（对应 WHERE）：在 child 行源上逐行求值 condition，保留为真的行；
// 列清单与 child 一致。child 只在需要时才向下拉取数据，过滤过程不物化 child 全量
nlohmann::json execute_filter(const nlohmann::json &plan)
{
    try
    {
        if (!plan.is_object())
        {
            throw StorageError("INVALID_PLAN", "filter plan must be a JSON object");
        }
        if (!plan.contains("child"))
        {
            throw StorageError("INVALID_PLAN", "filter is missing child");
        }
        if (!plan.contains("condition"))
        {
            throw StorageError("INVALID_PLAN", "filter is missing condition");
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
