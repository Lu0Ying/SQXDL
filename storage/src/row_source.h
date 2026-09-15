#ifndef STORAGE_CORE_ROW_SOURCE_H
#define STORAGE_CORE_ROW_SOURCE_H

#include <memory>
#include <string>
#include <vector>

#include "core/expression.h"
#include "core/row.h"
#include "core/row_store.h"
#include "nlohmann/json.hpp"

// 流式行源：逐步产出行数据，供读路径（scan / filter / project）使用。
// 行源链每一步只驻留一页数据（见 RowStore::RowIterator 与缓冲池），
// 因此内存占用与表规模无关；仅最终结果集需按「一行进一行出」的协议整体输出。
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
nlohmann::json drain_to_resultset(RowSource &source);

#endif
