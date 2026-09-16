#include <chrono>
#include <cmath>
#include <iostream>
#include <string>
#include "nlohmann/json.hpp"

#include "core/storage_error.h"

#include "query_op.h"
#include "row_source.h"
#include "insert_op.h"
#include "update_op.h"
#include "delete_op.h"
#include "create_table_op.h"
#include "show_tables_op.h"
#include "delete_table_op.h"
#include "describe_table_op.h"

// 服务式运行：从 stdin 逐行读取 physic plan，每行执行一次并输出一行结果；
// 读取到 "exit" 或 {"op":"exit"} 时退出进程；EOF 同样退出。
int main()
{
    std::string line;
    while (std::getline(std::cin, line))
    {
        // 去除首尾空白（兼容 \r\n、空行）
        const char *whitespace = " \t\r\n";
        const size_t start = line.find_first_not_of(whitespace);
        if (start == std::string::npos)
        {
            continue; // 空行跳过
        }
        const size_t end = line.find_last_not_of(whitespace);
        const std::string input = line.substr(start, end - start + 1);

        if (input == "exit")
        {
            break;
        }

        const auto start_time = std::chrono::steady_clock::now();

        nlohmann::json result;
        try
        {
            const nlohmann::json physic_plan = nlohmann::json::parse(input);

            if (physic_plan.value("op", "") == "exit")
            {
                break;
            }

            const std::string op = physic_plan.value("op", "");

            if (op == "scan" || op == "filter" || op == "project")
            {
                // 流式路径：按分帧协议边拉取边写出，不整体物化结果集
                std::unique_ptr<RowSource> source = build_row_source(physic_plan);
                stream_to_resultset(*source, std::cout, start_time);
                continue; // 耗时已随 end 帧写出，无需再走通用单行输出
            }
            if (op == "join")
            {
                // join（含子树）沿用整体物化实现，仍以单行结果集返回
                result = execute_query_node(physic_plan);
            }
            else if (op == "insert")
            {
                result = execute_insert(physic_plan);
            }
            else if (op == "update")
            {
                result = execute_update(physic_plan);
            }
            else if (op == "delete")
            {
                result = execute_delete(physic_plan);
            }
            else if (op == "createTable")
            {
                result = execute_create_table(physic_plan);
            }
            else if (op == "showTables")
            {
                result = execute_show_tables(physic_plan);
            }
            else if (op == "deleteTable")
            {
                result = execute_delete_table(physic_plan);
            }
            else if (op == "describeTable")
            {
                result = execute_describe_table(physic_plan);
            }
            else
            {
                result = {
                    {"success", false},
                    {"type", "error"},
                    {"error", {{"code", "INVALID_PLAN"}, {"message", "Unknown operation type"}}}};
            }
        }
        catch (const nlohmann::json::parse_error &)
        {
            result = {
                {"success", false},
                {"type", "error"},
                {"error", {{"code", "INVALID_PLAN"}, {"message", "Physic plan JSON parse failed"}}}};
        }
        catch (const nlohmann::json::exception &)
        {
            result = {
                {"success", false},
                {"type", "error"},
                {"error", {{"code", "INVALID_PLAN"}, {"message", "Invalid physical plan structure"}}}};
        }
        catch (const StorageError &e)
        {
            result = {
                {"success", false},
                {"type", "error"},
                {"error", {{"code", e.code()}, {"message", e.what()}}}};
        }
        catch (const std::exception &e)
        {
            result = {
                {"success", false},
                {"type", "error"},
                {"error", {{"code", "INTERNAL_ERROR"}, {"message", e.what()}}}};
        }
        catch (...)
        {
            result = {
                {"success", false},
                {"type", "error"},
                {"error", {{"code", "INTERNAL_ERROR"}, {"message", "Unknown error"}}}};
        }

        // 单行结果（rowcount / error / 非流式 resultset）的耗时统一按
        // steady_clock（单调时钟）差值换算为毫秒小数，四舍五入到微秒，
        // 避免浮点误差在 JSON 中产生 9.943999999 一类的长尾小数
        result["time"] = elapsed_millis(start_time);

        std::cout << result.dump() << std::endl;
    }
    return 0;
}
