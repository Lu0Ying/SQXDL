package com.sqxdl.executor;

import com.sqxdl.executor.storage.StorageResult;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.Console;
import java.io.IOException;
import java.util.Scanner;

/**
 * 程序入口（D 组）。
 * 职责：启动 SQXDL 的交互式 REPL，读取用户 SQL 并交给 {@link SqlEngine}
 *       驱动 词法 -> 语法 -> 语义 -> 计划生成 -> 执行 的完整流水线。
 * 输入分两种路径（共用同一条 {@link #processInput} 处理逻辑）：
 *       真终端 —— JLine LineReader，支持 ↑↓ 翻历史、Ctrl+R 搜索、行内编辑；
 *       管道/重定向（无 TTY）—— Scanner 循环，history / !N / !! 元命令等效替代。
 * 执行模式为 AUTO：优先真实存储核心（storage_core.exe），
 * 存储核心不可用时自动回退内置示例数据，保证 Demo 完整可演示。
 */
public class Main {

    /** 退出命令（不区分大小写）。 */
    private static final String EXIT_COMMAND = "exit";

    public static void main(String[] args) {
        try {
            SqlEngine engine = new SqlEngine(SqlEngine.Mode.AUTO);
            try {
                runRepl(engine);
            } finally {
                // 任何退出路径都结束存储会话：核心在协议 exit 时才把行数据刷入磁盘
                engine.close();
            }
        } catch (Exception e) {
            System.err.println("⚠发生未预期的错误: " + e.getMessage());
        }
    }

    /** 按 TTY 可用性选择输入路径。 */
    private static void runRepl(SqlEngine engine) {
        Executor renderer = new Executor();
        CommandHistory history = new CommandHistory();
        // IDEA Run 窗口/输入重定向没有真终端：直接走 Scanner 管道路径，
        // 避免初始化 JLine 触发 JDK 24 native access 警告与 dumb terminal 日志
        if (!hasRealConsole()) {
            runPiped(engine, renderer, history);
            return;
        }
        Terminal terminal = openSystemTerminal();
        if (terminal != null) {
            runInteractive(engine, renderer, history, terminal);
        } else {
            runPiped(engine, renderer, history);
        }
    }

    /** 真终端路径：JLine 提供方向键历史与行编辑。 */
    private static void runInteractive(SqlEngine engine, Executor renderer,
                                       CommandHistory history, Terminal terminal) {
        System.out.println("SQXDL 交互式终端已启动。↑↓ 翻历史、Ctrl+R 搜索、"
                + "history/!N 也可用；exit 退出。");
        LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();
        while (true) {
            String line;
            try {
                line = reader.readLine("sqxdl> ");
            } catch (UserInterruptException e) {
                continue; // Ctrl+C：清当前行，会话继续
            } catch (EndOfFileException e) {
                System.out.println();
                break; // Ctrl+D：退出
            }
            if (!processInput(engine, renderer, history, line.trim())) {
                break;
            }
        }
        try {
            terminal.close();
        } catch (IOException ignore) {
            // 关闭失败不影响退出
        }
    }

    /** 管道路径：Scanner 循环（IDEA Run 窗口/重定向；方向键不可用，用元命令等效替代）。 */
    private static void runPiped(SqlEngine engine, Executor renderer, CommandHistory history) {
        System.out.println("SQXDL 交互式终端已启动。输入 SQL 后回车执行，输入 exit 退出。");
        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print("sqxdl> "); // IDEA Run 窗口下保持交互提示符
                System.out.flush(); // print 不触发行缓冲刷新，需显式清空，否则提示符会迟到
                // hasNextLine 先探测输入流结束（Ctrl+Z / Ctrl+D），避免直接 nextLine 抛异常
                if (!scanner.hasNextLine()) {
                    break;
                }
                if (!processInput(engine, renderer, history, scanner.nextLine().trim())) {
                    break;
                }
            }
        }
    }

    /**
     * 处理一条输入：空行跳过、exit 退出、history / !N / !! 元命令、正常执行。
     *
     * @return false 表示收到退出命令
     */
    private static boolean processInput(SqlEngine engine, Executor renderer,
                                        CommandHistory history, String sql) {
        if (sql.isEmpty()) {
            return true;
        }
        if (EXIT_COMMAND.equalsIgnoreCase(sql)) {
            System.out.println("Bye!");
            return false;
        }
        // debug 元命令：随时开关流水线 DEBUG 输出（等价于 -Dsqxdl.debug=true 的运行时版）
        if (sql.equalsIgnoreCase("debug") || sql.equalsIgnoreCase(".debug")) {
            SqlDebug.ENABLED = !SqlDebug.ENABLED;
            System.out.println(SqlDebug.ENABLED
                    ? "DEBUG 已开启：每条 SQL 将打印 Token 流 / AST / 语义检查 / 优化前后 Plan 树"
                    : "DEBUG 已关闭");
            return true;
        }
        if (sql.equalsIgnoreCase("history")) {
            if (history.size() == 0) {
                System.out.println("(暂无历史)");
            } else {
                history.numbered().forEach(System.out::println);
            }
            return true;
        }
        if (history.isBang(sql)) {
            String resolved = history.resolve(sql);
            if (resolved == null) {
                System.out.println("⚠没有可重新执行的输入（编号超范围或历史为空）");
                return true;
            }
            // 回显实际执行的语句，保持 REPL 输入可追溯
            System.out.println("sqxdl> " + resolved);
            sql = resolved;
        }
        history.add(sql);
        try {
            StorageResult result = engine.execute(sql);
            // A 组 Lexer 的拼写自动纠错提示（如 SELEC -> SELECT）
            for (String warning : engine.getSpellWarnings()) {
                System.out.println("提示: " + warning);
            }
            // 回退到模拟执行时给出提示，便于区分真实存储与内置数据
            if (engine.getFallbackReason() != null) {
                System.out.println("提示: " + engine.getFallbackReason());
            }
            renderer.render(result);
        } catch (Exception e) {
            // REPL 循环内统一走 stdout：与提示符同通道保证输出顺序（见 Executor.render）
            System.out.println("⚠执行出错: " + e.getMessage());
        }
        return true;
    }

    /**
     * 判断是否挂接了可交互的真实终端（真 TTY）。
     * 注意：JDK 22 起即使无终端，{@link System#console()} 也可能返回非 null，
     * 需用 JDK 22 新增的 {@code Console.isTerminal()} 精确判断；
     * 该 API 不在项目编译目标（17）内，故反射调用以同时兼容 JDK 21/24 运行时。
     */
    private static boolean hasRealConsole() {
        Console console = System.console();
        if (console == null) {
            return false; // JDK 21 及以下无终端、或被重定向
        }
        try {
            Object real = Console.class.getMethod("isTerminal").invoke(console);
            return (Boolean) real; // JDK 22+：仅真终端返回 true
        } catch (NoSuchMethodException e) {
            return true; // JDK 21 及以下：console 非 null 即真终端
        } catch (ReflectiveOperationException e) {
            return true;
        }
    }

    /** 尝试打开系统终端；无 TTY（管道/重定向）或无可用 provider 时返回 null。 */
    private static Terminal openSystemTerminal() {
        try {
            Terminal terminal = TerminalBuilder.builder().system(true).build();
            // dumb 终端 = 没有真交互终端，行编辑与方向键无意义，走 Scanner 路径
            if (terminal.getType().startsWith("dumb")) {
                try {
                    terminal.close();
                } catch (IOException ignore) {
                    // 忽略
                }
                return null;
            }
            return terminal;
        } catch (Exception e) {
            return null;
        }
    }
}
