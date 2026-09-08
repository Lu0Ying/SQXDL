package com.sqxdl.executor;

import com.sqxdl.parser.ASTNode;
import com.sqxdl.parser.Parser;
import com.sqxdl.semantic.CatalogImpl;
import com.sqxdl.semantic.PlanGenerator;
import com.sqxdl.semantic.SemanticAnalyzer;

import java.util.Scanner;

/**
 * 程序入口（D 组）。
 * 职责：启动 SQXDL 的交互式 REPL，读取用户 SQL 并驱动
 *       词法 -> 语法 -> 语义 -> 计划生成 -> 执行 的完整流水线。
 */
public class Main {

    /** 退出命令（不区分大小写）。 */
    private static final String EXIT_COMMAND = "exit";

    public static void main(String[] args) {
        // 外层兜底：防止任何未预料的异常导致进程异常退出
        try (Scanner scanner = new Scanner(System.in)) {
            System.out.println("SQXDL 交互式终端已启动。输入 SQL 后回车执行，输入 exit 退出。");

            // 数据字典与执行器在 REPL 生命周期内复用，保证建表元数据跨语句可见
            CatalogImpl catalog = new CatalogImpl();
            SemanticAnalyzer analyzer = new SemanticAnalyzer(catalog);
            PlanGenerator generator = new PlanGenerator(catalog);
            Executor executor = new Executor();

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

                    // 流水线：语法 -> 语义 -> 计划生成 -> 执行。
                    // Lexer/Parser 为 A 组 TODO（parse 当前返回 null），
                    // 完成后此处无需改动即可生效
                    ASTNode ast = new Parser().parse();
                    if (ast == null) {
                        System.out.println("语法分析尚未实现（Parser TODO），已跳过该语句。");
                        continue;
                    }
                    analyzer.analyze(ast);
                    executor.execute(generator.generate(ast));
                } catch (Exception e) {
                    System.err.println("⚠执行出错: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("⚠发生未预期的错误: " + e.getMessage());
        }
    }
}
