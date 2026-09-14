#include "scan_op.h"

#include <memory>

#include "core/storage_error.h"
#include "row_source.h"

// 全表扫描（查询树叶子节点）：按页流式读取整表（结果集，列 = 表列、行 = 表行）。
// 行数据不整表物化，逐页从缓冲池取得，内存占用由页缓存页数决定
nlohmann::json execute_scan(const nlohmann::json &plan)
{
    try
    {
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
