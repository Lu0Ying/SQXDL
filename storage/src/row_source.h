#ifndef STORAGE_CORE_ROW_SOURCE_H
#define STORAGE_CORE_ROW_SOURCE_H

#include <chrono>
#include <cmath>
#include <memory>
#include <ostream>
#include <string>
#include <vector>

#include "core/expression.h"
#include "core/row.h"
#include "core/row_store.h"
#include "nlohmann/json.hpp"

// 流式行源：逐步产出行数据，供读路径（scan / filter / project）使用。
// 行源链每一步只驻留一页数据（见 RowStore::RowIterator 与缓冲池），
// 结果集本身也不再整体物化，而是分帧边生产边写出（见 stream_to_resultset）。
class RowSource
{
public:
    virtual ~RowSource() = default;

    // 产出一行；无更多行返回 false
    virtual bool next(Row &out) = 0;

    // 输出列名（列顺序即行内字段顺序）
    virtual const std::vector<std::string> &column_names() const = 0;
};

// 按物理计划节点构建行源链：scan / filter / project 逐级包装；
// join 的结果沿用既有实现整体物化（回退为物化行源）；
// 结构非法抛 StorageError(INVALID_PLAN)，表/列不存在抛对应错误码
std::unique_ptr<RowSource> build_row_source(const nlohmann::json &plan);

// 把行源排空为结果集 JSON：{success, type:"resultset", columns, rows}
// （join 子节点等需要完整结果集的场合使用；顶层读路径改走 stream_to_resultset）
nlohmann::json drain_to_resultset(RowSource &source);

// 流式输出每批的行数：一批写成一条 rows 行，避免逐行写放大管道与解析开销
constexpr size_t kResultBatchRows = 256;

// 从 start 到当前的耗时（毫秒，四舍五入到微秒），即协议 time 字段的统一口径
inline double elapsed_millis(std::chrono::steady_clock::time_point start)
{
    const double ms =
        std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
    return std::round(ms * 1000.0) / 1000.0;
}

// 把行源按分帧协议直接写入 out，边拉取边写出，不在内存中累积整个结果集：
//   header 行 {"success":true,"type":"resultset","columns":[...],"streaming":true}
//   rows   行 {"type":"rows","rows":[[...],...]}   每 kResultBatchRows 行一条（写后 flush）
//   end    行 {"type":"end","rowCount":N,"time":x} 结果集结束标志
// 中途抛异常时已写出的帧保留，由调用方以一条 error 行收尾（调用方按帧类型识别）。
void stream_to_resultset(RowSource &source, std::ostream &out,
                         std::chrono::steady_clock::time_point start);

#endif
