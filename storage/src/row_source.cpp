#include "row_source.h"

#include <utility>

#include "core/database.h"
#include "core/expression.h"
#include "core/storage_error.h"
#include "core/table.h"
#include "query_op.h"

namespace
{
    // 全表扫描行源：包装 RowStore 的页级流式迭代器（一次只驻留一页）
    class ScanRowSource : public RowSource
    {
    public:
        ScanRowSource(std::vector<std::string> columns, std::unique_ptr<RowIterator> iterator)
            : column_names_(std::move(columns)), iterator_(std::move(iterator))
        {
        }

        bool next(Row &out) override
        {
            return iterator_->next(out);
        }

        const std::vector<std::string> &column_names() const override
        {
            return column_names_;
        }

    private:
        std::vector<std::string> column_names_;
        std::unique_ptr<RowIterator> iterator_;
    };

    // 过滤行源：逐行求值 condition，命中即产出（列清单与 child 一致）
    class FilterRowSource : public RowSource
    {
    public:
        FilterRowSource(std::unique_ptr<RowSource> child, ExpressionPtr condition)
            : child_(std::move(child)), condition_(std::move(condition))
        {
        }

        bool next(Row &out) override
        {
            Row row;
            while (child_->next(row))
            {
                const EvalContext ctx(child_->column_names(), row);
                if (condition_->evaluate(ctx).truth_value())
                {
                    out = std::move(row);
                    return true;
                }
            }
            return false;
        }

        const std::vector<std::string> &column_names() const override
        {
            return child_->column_names();
        }

    private:
        std::unique_ptr<RowSource> child_;
        ExpressionPtr condition_;
    };

    // 投影行源：按投影列下标抽选并重排（下标在构建时定位一次）
    class ProjectRowSource : public RowSource
    {
    public:
        ProjectRowSource(std::unique_ptr<RowSource> child, std::vector<size_t> indices,
                         std::vector<std::string> columns)
            : child_(std::move(child)), indices_(std::move(indices)), column_names_(std::move(columns))
        {
        }

        bool next(Row &out) override
        {
            Row row;
            if (!child_->next(row))
            {
                return false;
            }
            Row projected;
            for (const size_t index : indices_)
            {
                projected.append(row.at(index));
            }
            out = std::move(projected);
            return true;
        }

        const std::vector<std::string> &column_names() const override
        {
            return column_names_;
        }

    private:
        std::unique_ptr<RowSource> child_;
        std::vector<size_t> indices_;
        std::vector<std::string> column_names_;
    };

    // 物化行源：接管 join 等「返回完整结果集」的既有实现
    class MaterializedRowSource : public RowSource
    {
    public:
        MaterializedRowSource(std::vector<std::string> columns, std::vector<Row> rows)
            : column_names_(std::move(columns)), rows_(std::move(rows))
        {
        }

        bool next(Row &out) override
        {
            if (index_ >= rows_.size())
            {
                return false;
            }
            out = rows_[index_++];
            return true;
        }

        const std::vector<std::string> &column_names() const override
        {
            return column_names_;
        }

    private:
        std::vector<std::string> column_names_;
        std::vector<Row> rows_;
        size_t index_ = 0;
    };

    // 把 join 等既有实现的结果集 JSON 转成物化行源
    std::unique_ptr<RowSource> materialized_from_node(const nlohmann::json &plan)
    {
        const nlohmann::json result = execute_query_node(plan);
        if (!result.is_object() || !result.value("success", false))
        {
            std::string code = "INTERNAL_ERROR";
            std::string message = "Query child failed";
            if (result.is_object() && result.contains("error") && result.at("error").is_object())
            {
                code = result.at("error").value("code", code);
                message = result.at("error").value("message", message);
            }
            throw StorageError(code, message);
        }
        if (result.value("type", "") != "resultset" || !result.contains("columns") ||
            !result.contains("rows") || !result.at("columns").is_array() || !result.at("rows").is_array())
        {
            throw StorageError("INVALID_PLAN", "Query child did not return a valid result set");
        }

        std::vector<std::string> columns;
        columns.reserve(result.at("columns").size());
        for (const auto &column : result.at("columns"))
        {
            if (!column.is_string())
            {
                throw StorageError("INVALID_PLAN", "Result set column names must be strings");
            }
            columns.push_back(column.get<std::string>());
        }
        std::vector<Row> rows;
        rows.reserve(result.at("rows").size());
        for (const auto &row_json : result.at("rows"))
        {
            rows.push_back(Row::from_json(row_json));
        }
        return std::make_unique<MaterializedRowSource>(std::move(columns), std::move(rows));
    }
} // namespace

std::unique_ptr<RowSource> build_row_source(const nlohmann::json &plan)
{
    if (!plan.is_object())
    {
        throw StorageError("INVALID_PLAN", "Query node must be a JSON object");
    }
    const std::string op = plan.value("op", "");

    if (op == "scan")
    {
        if (!plan.contains("table") || !plan.at("table").is_string() ||
            plan.at("table").get<std::string>().empty())
        {
            throw StorageError("INVALID_PLAN", "Missing or invalid string field: table");
        }
        const std::string table_name = plan.at("table").get<std::string>();
        // 只读表结构（不物化行数据）；表不存在抛 TABLE_NOT_FOUND
        const Table &table = Database::instance().get_table(table_name);
        RowStore &store = Database::instance().row_store();
        return std::make_unique<ScanRowSource>(table.column_names(), store.scan(table_name));
    }

    if (op == "filter")
    {
        if (!plan.contains("child"))
        {
            throw StorageError("INVALID_PLAN", "filter is missing child");
        }
        if (!plan.contains("condition"))
        {
            throw StorageError("INVALID_PLAN", "filter is missing condition");
        }
        std::unique_ptr<RowSource> child = build_row_source(plan.at("child"));
        ExpressionPtr condition = parse_expression(plan.at("condition"));
        return std::make_unique<FilterRowSource>(std::move(child), std::move(condition));
    }

    if (op == "project")
    {
        if (!plan.contains("child"))
        {
            throw StorageError("INVALID_PLAN", "project is missing child");
        }
        if (!plan.contains("columns") || !plan.at("columns").is_array() || plan.at("columns").empty())
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

        std::unique_ptr<RowSource> child = build_row_source(plan.at("child"));
        const std::vector<std::string> &child_columns = child->column_names();
        std::vector<size_t> indices;
        std::vector<std::string> names;
        indices.reserve(columns.size());
        names.reserve(columns.size());
        for (const auto &column : columns)
        {
            const std::string name = column.get<std::string>();
            // 与条件求值共用列解析规则（含点限定宽容匹配），
            // 支持 JOIN 结果 schema 的 "来源.列名" 前缀差异
            const int index = find_column_index(child_columns, name);
            if (index == -2)
            {
                throw StorageError("INVALID_PLAN",
                                   "Ambiguous project column: " + name +
                                       " (matches multiple columns, qualify it explicitly)");
            }
            if (index < 0)
            {
                throw StorageError("COLUMN_NOT_FOUND", "Column " + name + " not found");
            }
            indices.push_back(static_cast<size_t>(index));
            names.push_back(name);
        }
        return std::make_unique<ProjectRowSource>(std::move(child), std::move(indices), std::move(names));
    }

    if (op == "join")
    {
        // join（及其子树）沿用既有整体物化实现，作为行源回退，保证结果完全一致
        return materialized_from_node(plan);
    }

    throw StorageError("INVALID_PLAN", "Invalid query node op: " + op);
}

nlohmann::json drain_to_resultset(RowSource &source)
{
    const std::vector<std::string> &names = source.column_names();
    nlohmann::json columns = nlohmann::json::array();
    for (const std::string &name : names)
    {
        columns.push_back(name);
    }

    nlohmann::json rows = nlohmann::json::array();
    Row row;
    while (source.next(row))
    {
        rows.push_back(row.to_json());
    }

    return {{"success", true},
            {"type", "resultset"},
            {"columns", std::move(columns)},
            {"rows", std::move(rows)}};
}

void stream_to_resultset(RowSource &source, std::ostream &out,
                         std::chrono::steady_clock::time_point start)
{
    const std::vector<std::string> &names = source.column_names();
    nlohmann::json columns = nlohmann::json::array();
    for (const std::string &name : names)
    {
        columns.push_back(name);
    }

    // 首帧：列清单 + streaming 标记（调用方据此判定需续读 rows/end 帧）
    out << nlohmann::json{{"success", true},
                          {"type", "resultset"},
                          {"columns", columns},
                          {"streaming", true}}
               .dump()
        << '\n';
    out.flush();

    // 数据帧：每满一批立即写出并 flush，调用方可以边收边处理；
    // 批次缓冲是唯一的行驻留，因此输出阶段内存占用为常数
    nlohmann::json batch = nlohmann::json::array();
    size_t row_count = 0;
    Row row;
    while (source.next(row))
    {
        batch.push_back(row.to_json());
        ++row_count;
        if (batch.size() >= kResultBatchRows)
        {
            out << nlohmann::json{{"type", "rows"}, {"rows", batch}}.dump() << '\n';
            out.flush();
            batch.clear();
        }
    }
    if (!batch.empty())
    {
        out << nlohmann::json{{"type", "rows"}, {"rows", batch}}.dump() << '\n';
        out.flush();
        batch.clear();
    }

    // 末帧：结束标志 + 总行数 + 本次请求耗时（时间随结果产出完毕而定，只能落在末帧）
    out << nlohmann::json{{"type", "end"},
                          {"rowCount", row_count},
                          {"time", elapsed_millis(start)}}
               .dump()
        << '\n';
    out.flush();
}
