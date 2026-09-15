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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;

/**
 * 程序入口 —— D 组（executor 模块）的命令行前端。
 * <p>职责：启动 SQXDL 交互式 REPL，读取用户 SQL 交给 {@link SqlEngine} 驱动
 * 词法 -> 语法 -> 语义 -> 计划生成 -> 执行 的完整流水线（各阶段由 A/B 组模块
 * 与存储核心完成，见 SqlEngine 类注释）；本类只负责输入输出与元命令，
 * 不含任何解析/执行逻辑。
 * <p>三种输入路径（SQL 部分共用同一条 {@link #processInput} 处理逻辑）：
 * <ul>
 *   <li>真终端 —— JLine LineReader，支持 ↑↓ 翻历史、Ctrl+R 搜索、行内编辑</li>
 *   <li>管道/重定向（无 TTY）—— Scanner 循环，history / !! / !N 元命令等效替代</li>
 *   <li>脚本文件 —— {@code -f <path>} / {@code --file <path>} 指定 .sql 文件，
 *       连续语句批量执行（指导书要求：输入支持 SQL 文件或标准输入）</li>
 * </ul>
 * 元命令：exit 退出、debug 开关流水线调试输出、history 列历史、!N 重放。
 * 执行模式为 AUTO：优先真实存储核心（storage_core.exe，数据落盘持久化），
 * 不可用时自动回退内置示例数据，保证 Demo 完整可演示。
 */
public class Main {

    /** 退出命令（不区分大小写）。 */
    private static final String EXIT_COMMAND = "exit";

    public static void main(String[] args) {
        try {
            SqlEngine engine = new SqlEngine(SqlEngine.Mode.AUTO);
            boolean success = true;
            try {
                String scriptFile = parseScriptFile(args);
                if (scriptFile != null) {
                    success = runScriptFile(engine, scriptFile);
                } else {
                    runRepl(engine);
                }
            } finally {
                // 任何退出路径都结束存储会话：核心在协议 exit 时才把行数据刷入磁盘
                engine.close();
            }
            // 脚本执行失败以非零码退出，便于批处理/CI 判断结果
            if (!success) {
                System.exit(1);
            }
        } catch (Exception e) {
            System.err.println("⚠发生未预期的错误: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * 解析脚本文件参数：{@code -f <path>} / {@code --file <path>} 或首个位置参数。
     *
     * @return 文件路径；未指定时返回 null（走交互模式）
     * @throws IllegalArgumentException -f/--file 后缺失路径时抛出
     */
    private static String parseScriptFile(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-f") || args[i].equals("--file")) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException(args[i] + " 需要指定 SQL 文件路径");
                }
                return args[i + 1];
            }
        }
        return args.length > 0 ? args[0] : null;
    }

    /**
     * 脚本文件路径：逐行读取 .sql 文件执行。连续的 SQL 语句通过
     * {@link SqlEngine#executeBatch} 批量执行（流水线协议，减少逐条往返）；
     * 元命令（exit/debug/history/!N）打断批量段、单条处理。空行与整行
     * 注释（-- 开头）静默跳过；exit 提前结束；读取失败返回 false。
     */
    private static boolean runScriptFile(SqlEngine engine, String path) {
        Executor renderer = new Executor();
        CommandHistory history = new CommandHistory();
        List<String> lines;
        try {
            lines = Files.readAllLines(Path.of(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("⚠无法读取 SQL 文件: " + path + " (" + e.getMessage() + ")");
            return false;
        }
        List<String> pending = new ArrayList<>(); // 待批量执行的连续 SQL 段
        for (String line : lines) {
            String text = line.trim();
            if (text.isEmpty() || text.startsWith("--")) {
                continue; // 空行/注释行不进入历史，也不触发语义报错
            }
            if (isMetaCommand(text)) {
                flushBatch(engine, renderer, pending);
                if (!processInput(engine, renderer, history, text)) {
                    return true; // exit：提前结束
                }
            } else {
                pending.add(text);
            }
        }
        flushBatch(engine, renderer, pending);
        return true;
    }

    /** 批量执行待处理语句并逐条回显+渲染结果，保证执行过程可追溯 */
    private static void flushBatch(SqlEngine engine, Executor renderer, List<String> pending) {
        if (pending.isEmpty()) {
            return;
        }
        List<StorageResult> results = engine.executeBatch(pending);
        for (int i = 0; i < pending.size(); i++) {
            System.out.println("sqxdl> " + pending.get(i));
            renderer.render(results.get(i));
        }
        pending.clear();
    }

    /** 与 processInput 的元命令保持一致：exit/debug/.debug/history/!N 重放 */
    private static boolean isMetaCommand(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.equals(EXIT_COMMAND) || lower.equals("debug") || lower.equals(".debug")
                || lower.equals("history") || text.startsWith("!");
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
