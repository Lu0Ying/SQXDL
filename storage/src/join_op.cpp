#include "join_op.h"

#include <cctype>
#include <set>
#include <string>
#include <vector>

#include "core/expression.h"
#include "core/row.h"
#include "core/storage_error.h"
#include "query_op.h"

namespace
{
enum class JoinType
{
    Inner,
    Left,
    Right,
    Cross
};

// 归一化 type 字段（大小写不敏感，缺省 inner）
JoinType parse_join_type(const nlohmann::json &plan)
{
    std::string type = plan.value("type", "inner");
    for (char &c : type)
    {
        c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    }
    if (type == "inner" || type == "join")
    {
        return JoinType::Inner;
    }
    if (type == "left" || type == "left outer")
    {
        return JoinType::Left;
    }
    if (type == "right" || type == "right outer")
    {
        return JoinType::Right;
    }
    if (type == "cross")
    {
        return JoinType::Cross;
    }
    throw StorageError("INVALID_PLAN", "不支持的 join 类型: " + type);
}

// 推导一侧的别名：节点上的 alias 优先，其次 scan 用表名，filter/project 递归 child
std::string side_alias(const nlohmann::json &plan, const std::string &fallback)
{
    if (!plan.is_object())
    {
        return fallback;
    }
    if (plan.contains("alias"))
    {
        if (!plan.at("alias").is_string() || plan.at("alias").get<std::string>().empty())
        {
            throw StorageError("INVALID_PLAN", "alias 必须是非空字符串");
        }
        return plan.at("alias").get<std::string>();
    }
    const std::string op = plan.value("op", "");
    if (op == "scan")
    {
        if (plan.contains("table") && plan.at("table").is_string())
        {
            return plan.at("table").get<std::string>();
        }
        return fallback;
    }
    if ((op == "filter" || op == "project") && plan.contains("child"))
    {
        return side_alias(plan.at("child"), fallback);
    }
    return fallback;
}

// 校验数据集结构并取出列名
std::vector<std::string> result_columns(const nlohmann::json &result, const char *who)
{
    if (!result.contains("columns") || !result.at("columns").is_array() ||
        !result.contains("rows") || !result.at("rows").is_array())
    {
        throw StorageError("INVALID_PLAN", std::string(who) + " 未返回合法数据集");
    }
    std::vector<std::string> names;
    names.reserve(result.at("columns").size());
    for (const auto &column : result.at("columns"))
    {
        if (!column.is_string())
        {
            throw StorageError("INVALID_PLAN", "数据集列名必须是字符串");
        }
        names.push_back(column.get<std::string>());
    }
    return names;
}

// 校验每行宽度与列数一致
void check_row_width(const nlohmann::json &result, size_t width, const char *who)
{
    for (const auto &row : result.at("rows"))
    {
        if (!row.is_array() || row.size() != width)
        {
            throw StorageError("INTERNAL_ERROR", std::string(who) + " 数据集行与列定义不一致");
        }
    }
}
} // namespace

// 连接：递归执行 left / right 得到数据集，按 type 生成连接结果；
// 结果列同名时加来源前缀（别名或表名）消歧，condition 引用结果列名
nlohmann::json execute_join(const nlohmann::json &plan)
{
    try
    {
        if (!plan.is_object())
        {
            throw StorageError("INVALID_PLAN", "join 计划必须是 JSON 对象");
        }
        if (!plan.contains("left") || !plan.contains("right"))
        {
            throw StorageError("INVALID_PLAN", "join 缺少 left/right 子计划");
        }
        const JoinType type = parse_join_type(plan);

        // 条件：非 cross 必填，cross 不允许携带
        ExpressionPtr condition;
        if (type == JoinType::Cross)
        {
            if (plan.contains("condition"))
            {
                throw StorageError("INVALID_PLAN", "cross join 不应包含 condition");
            }
        }
        else
        {
            if (!plan.contains("condition"))
            {
                throw StorageError("INVALID_PLAN", "join 缺少 condition");
            }
            condition = parse_expression(plan.at("condition"));
        }

        nlohmann::json left = execute_query_node(plan.at("left"));
        if (!left.is_object() || !left.value("success", false))
        {
            return left; // 传播左子节点错误
        }
        nlohmann::json right = execute_query_node(plan.at("right"));
        if (!right.is_object() || !right.value("success", false))
        {
            return right; // 传播右子节点错误
        }

        const std::vector<std::string> left_names = result_columns(left, "join 的 left");
        const std::vector<std::string> right_names = result_columns(right, "join 的 right");
        check_row_width(left, left_names.size(), "join 的 left");
        check_row_width(right, right_names.size(), "join 的 right");

        const std::string left_alias = side_alias(plan.at("left"), "left");
        const std::string right_alias = side_alias(plan.at("right"), "right");

        // 结果列命名：两侧重名的列加来源前缀，其余保持原名
        const std::set<std::string> left_set(left_names.begin(), left_names.end());
        const std::set<std::string> right_set(right_names.begin(), right_names.end());
        std::vector<std::string> out_names;
        out_names.reserve(left_names.size() + right_names.size());
        for (const auto &name : left_names)
        {
            out_names.push_back(right_set.count(name) ? left_alias + "." + name : name);
        }
        for (const auto &name : right_names)
        {
            out_names.push_back(left_set.count(name) ? right_alias + "." + name : name);
        }
        std::set<std::string> seen;
        for (const auto &name : out_names)
        {
            if (!seen.insert(name).second)
            {
                throw StorageError("INVALID_PLAN",
                                   "连接结果列名重复: " + name + "（可为两侧设置 alias 以区分）");
            }
        }

        const nlohmann::json &left_rows = left.at("rows");
        const nlohmann::json &right_rows = right.at("rows");
        std::vector<bool> left_matched(left_rows.size(), false);
        std::vector<bool> right_matched(right_rows.size(), false);

        nlohmann::json out_rows = nlohmann::json::array();
        for (size_t i = 0; i < left_rows.size(); ++i)
        {
            const nlohmann::json &left_row = left_rows.at(i);
            for (size_t j = 0; j < right_rows.size(); ++j)
            {
                const nlohmann::json &right_row = right_rows.at(j);
                if (condition)
                {
                    // 组合成一行供条件求值：左字段在前、右字段在后，与 out_names 顺序一致
                    Row combined = Row::from_json(left_row);
                    for (const auto &value : right_row)
                    {
                        combined.append(Value::from_json(value));
                    }
                    const EvalContext ctx(out_names, combined);
                    if (!condition->evaluate(ctx).truth_value())
                    {
                        continue;
                    }
                }
                nlohmann::json row = nlohmann::json::array();
                for (const auto &value : left_row)
                {
                    row.push_back(value);
                }
                for (const auto &value : right_row)
                {
                    row.push_back(value);
                }
                out_rows.push_back(std::move(row));
                left_matched[i] = true;
                right_matched[j] = true;
            }
        }

        // 外连接：补未匹配侧（以 NULL 填充另一侧字段）
        if (type == JoinType::Left)
        {
            for (size_t i = 0; i < left_rows.size(); ++i)
            {
                if (left_matched[i])
                {
                    continue;
                }
                nlohmann::json row = left_rows.at(i);
                for (size_t k = 0; k < right_names.size(); ++k)
                {
                    row.push_back(nullptr);
                }
                out_rows.push_back(std::move(row));
            }
        }
        else if (type == JoinType::Right)
        {
            for (size_t j = 0; j < right_rows.size(); ++j)
            {
                if (right_matched[j])
                {
                    continue;
                }
                nlohmann::json row = nlohmann::json::array();
                for (size_t k = 0; k < left_names.size(); ++k)
                {
                    row.push_back(nullptr);
                }
                for (const auto &value : right_rows.at(j))
                {
                    row.push_back(value);
                }
                out_rows.push_back(std::move(row));
            }
        }

        nlohmann::json columns = nlohmann::json::array();
        for (const auto &name : out_names)
        {
            columns.push_back(name);
        }

        return {
            {"success", true},
            {"type", "resultset"},
            {"columns", std::move(columns)},
            {"rows", std::move(out_rows)}
        };
    }
    catch (const StorageError &e)
    {
        return {{"success", false},
                {"type", "error"},
                {"error", {{"code", e.code()}, {"message", e.what()}}}};
    }
}
