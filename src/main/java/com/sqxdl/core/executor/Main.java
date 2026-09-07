package com.sqxdl.core.executor;

/**
 * 程序入口（D 组）。
 * 职责：启动 SQXDL 的交互式 REPL，读取用户 SQL 并驱动
 *       词法 -> 语法 -> 语义 -> 计划生成 -> 执行 的完整流水线。
 */
public class Main {

    public static void main(String[] args) {
        // TODO: 实现 REPL 循环：
        //       1) 用 Scanner 从控制台逐行读取 SQL（建议以 ';' 作为一条语句结束）；
        //       2) 死循环处理，输入 "exit" 时退出；
        //       3) 每条语句依次调用 Parser -> SemanticAnalyzer -> PlanGenerator -> Executor；
        //       4) 捕获各阶段异常，打印错误信息后继续下一轮，不让 REPL 崩溃
    }
}
