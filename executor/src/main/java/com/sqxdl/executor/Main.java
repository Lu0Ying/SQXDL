package com.sqxdl.executor;

import com.sqxdl.executor.storage.StorageResult;

import java.util.Scanner;

/**
 * 程序入口（D 组）。
 * 职责：启动 SQXDL 的交互式 REPL，读取用户 SQL 并交给 {@link SqlEngine}
 *       驱动 词法 -> 语法 -> 语义 -> 计划生成 -> 执行 的完整流水线。
 * 执行模式为 AUTO：优先真实存储核心（storage_core.exe），
 * 存储核心不可用时自动回退内置示例数据，保证 Demo 完整可演示。
 */
public class Main {

    /** 退出命令（不区分大小写）。 */
    private static final String EXIT_COMMAND = "exit";

    public static void main(String[] args) {
        // 外层兜底：防止任何未预料的异常导致进程异常退出
        try (Scanner scanner = new Scanner(System.in)) {
            System.out.println("SQXDL 交互式终端已启动。输入 SQL 后回车执行，输入 exit 退出。");

            // 执行引擎与结果渲染器在 REPL 生命周期内复用；
            // 引擎持有的数据字典与存储核心会话跨语句保持（建表元数据/数据可见）
            SqlEngine engine = new SqlEngine(SqlEngine.Mode.AUTO);
            Executor renderer = new Executor();

            while (true) {
                System.out.print("sqxdl> ");
                System.out.flush();

                // 输入流结束（Windows Ctrl+Z / Unix Ctrl+D）时优雅退出，
                // 避免直接 nextLine 抛出 NoSuchElementException
                if (!scanner.hasNextLine()) {
                    System.out.println();
                    break;
                }

                // 单轮逻辑全部包裹在 try-catch 中，任何异常只打印并继续下一轮
                try {
                    String sql = scanner.nextLine().trim();

                    // 跳过空输入
                    if (sql.isEmpty()) {
                        continue;
                    }

                    // 收到退出命令则结束循环
                    if (EXIT_COMMAND.equalsIgnoreCase(sql)) {
                        System.out.println("Bye!");
                        break;
                    }

                    StorageResult result = engine.execute(sql);
                    // 回退到模拟执行时给出提示，便于区分真实存储与内置数据
                    if (engine.getFallbackReason() != null) {
                        System.out.println("提示: " + engine.getFallbackReason());
                    }
                    renderer.render(result);
                } catch (Exception e) {
                    System.err.println("⚠执行出错: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("⚠发生未预期的错误: " + e.getMessage());
        }
    }
}
