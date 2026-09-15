package com.sqxdl.executor.storage;

import com.sqxdl.semantic.PlanNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 存储核心客户端（D 组 storage 子包）：以服务式会话调用 C 组的 storage_core.exe。
 * <p>行协议（契约见 storage/readme.md）：核心进程启动后常驻主循环，
 * 从 stdin 读一行计划 JSON -> 执行 -> 向 stdout 写一行结果 JSON -> 继续。
 * 进程因此跨语句复用，建表目录与数据在会话内保持；进程意外退出或调用
 * 超时时关闭会话，下一次执行自动重启新进程。
 * <p>两种调用方式：
 * <ul>
 *   <li>{@link #execute(PlanNode)} / {@link #call(String)} —— 单条：写一行、等回一行，
 *       交互式 REPL 的常规路径</li>
 *   <li>{@link #executeAll(List)} / {@link #callAll(List)} —— 批量（流水线协议）：
 *       一次写入多行、按序读回等量结果，脚本模式与批量 INSERT 使用，
 *       消灭逐条往返的固定开销</li>
 * </ul>
 * 最近一次调用的分段耗时（序列化/发送/核心执行+回传/响应解析）以纳秒记录在
 * lastXxxNanos 字段，供 {@link com.sqxdl.executor.SqlDebug} 耗时分解与
 * {@link com.sqxdl.executor.PerfTest} 基准展示（REPL/GUI 单线程调用，无并发竞争）。
 * 任何失败（程序缺失、启动失败、超时、返回异常）都封装为带错误码的
 * ERROR 结果返回，不向调用方抛异常，保证 REPL/GUI 永不崩溃。
 */
public class StorageClient {

    /** 单次执行的读取超时时间（秒） */
    private static final long TIMEOUT_SECONDS = 10;

    /** 存储核心可执行文件路径 */
    private final Path exePath;

    /** 常驻存储核心进程（懒启动，跨语句复用） */
    private Process process;

    /** 进程 stdout 的按行读取器（与进程同生命周期，避免缓冲吞行） */
    private BufferedReader stdout;

    /** 单线程读取池：为阻塞 readLine 提供超时控制 */
    private final ExecutorService readerPool = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "storage-core-reader");
        thread.setDaemon(true);
        return thread;
    });

    // ---- 最近一次调用的分段耗时（纳秒）。REPL/GUI 为单线程调用，无并发竞争 ----
    /** 计划对象 -> JSON 文本的序列化耗时 */
    private volatile long lastSerializeNanos;
    /** 写入 stdin 并 flush 的发送耗时 */
    private volatile long lastSendNanos;
    /** 等待核心返回一行的耗时（含核心执行 + 回传 + 管道往返） */
    private volatile long lastWaitNanos;
    /** 结果 JSON 文本 -> StorageResult 的解析耗时 */
    private volatile long lastParseNanos;

    /** 最近一次调用的计划序列化耗时（纳秒） */
    public long lastSerializeNanos() { return lastSerializeNanos; }

    /** 最近一次调用的发送耗时（纳秒） */
    public long lastSendNanos() { return lastSendNanos; }

    /** 最近一次调用的核心等待耗时（纳秒） */
    public long lastWaitNanos() { return lastWaitNanos; }

    /** 最近一次调用的响应解析耗时（纳秒） */
    public long lastParseNanos() { return lastParseNanos; }

    public StorageClient() {
        // 可通过 -Dsqxdl.storage.exe=<路径> 覆盖；默认取仓库约定位置
        String configured = System.getProperty("sqxdl.storage.exe");
        this.exePath = Path.of(configured != null ? configured : "storage/storage_core.exe");
        // JVM 退出时结束存储核心，避免残留子进程
        Runtime.getRuntime().addShutdownHook(new Thread(this::closeSession));
    }

    /** 执行计划：序列化为物理计划 JSON 后经会话调用存储核心 */
    public StorageResult execute(PlanNode plan) {
        long t0 = System.nanoTime();
        String json = PhysicalPlanJson.serialize(plan);
        lastSerializeNanos = System.nanoTime() - t0;
        return call(json);
    }

    /**
     * 批量执行计划（流水线协议）：全部计划序列化后一次性交给
     * {@link #callAll}，由后者一次写入、按序读回。
     *
     * @return 与输入等长的结果列表
     */
    public List<StorageResult> executeAll(List<PlanNode> plans) {
        long t0 = System.nanoTime();
        List<String> jsons = new ArrayList<>(plans.size());
        for (PlanNode plan : plans) {
            jsons.add(PhysicalPlanJson.serialize(plan));
        }
        lastSerializeNanos = System.nanoTime() - t0;
        return callAll(jsons);
    }

    /**
     * 批量调用（流水线协议）：一次写入全部计划 JSON 行，按序读回等量结果行。
     * 存储核心本就是"读一行-执行-立即回一行"的流式循环，无需改动；
     * 写入放在独立线程：Windows 管道缓冲约 64KB，大批量写入会被背压阻塞，
     * 若在主线程"写完再读"，核心回写大结果同样被阻塞会造成死锁，
     * 写读并行后由管道自然节流。任一行失败（超时/进程退出/响应非法）即
     * 关闭会话并让剩余条目统一标记同一错误，保证返回列表与输入等长对齐。
     *
     * @return 与 planJsons 等长的结果列表
     */
    public List<StorageResult> callAll(List<String> planJsons) {
        if (planJsons.isEmpty()) {
            return List.of();
        }
        if (!Files.isExecutable(exePath)) {
            return failedAll(planJsons.size(), StorageResult.error("STORAGE_UNAVAILABLE",
                    "找不到存储核心程序: " + exePath.toAbsolutePath()
                            + "（可用 -Dsqxdl.storage.exe=<路径> 指定）"));
        }
        try {
            Process current = ensureSession();
            long send0 = System.nanoTime();
            IOException[] writeError = new IOException[1];
            Thread writer = new Thread(() -> {
                try {
                    var out = current.getOutputStream();
                    for (String json : planJsons) {
                        out.write((json + "\n").getBytes(StandardCharsets.UTF_8));
                    }
                    out.flush();
                } catch (IOException e) {
                    writeError[0] = e;
                }
            }, "storage-core-writer");
            writer.start();

            List<StorageResult> results = new ArrayList<>(planJsons.size());
            long wait0 = System.nanoTime();
            for (int i = 0; i < planJsons.size(); i++) {
                String line;
                try {
                    Future<String> pendingLine = readerPool.submit(() -> stdout.readLine());
                    line = pendingLine.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    closeSession();
                    return failedRemainder(results, planJsons.size(),
                            StorageResult.error("STORAGE_TIMEOUT",
                                    "存储核心执行超时（批量第 " + (i + 1) + " 条处）"));
                } catch (ExecutionException e) {
                    closeSession();
                    return failedRemainder(results, planJsons.size(),
                            StorageResult.error("STORAGE_UNAVAILABLE",
                                    "读取存储核心输出失败: " + e.getCause().getMessage()));
                }
                if (line == null) {
                    closeSession();
                    return failedRemainder(results, planJsons.size(),
                            StorageResult.error("STORAGE_UNAVAILABLE",
                                    "存储核心进程已退出（批量第 " + (i + 1) + " 条处）"));
                }
                try {
                    results.add(StorageResult.parse(line.trim()));
                } catch (RuntimeException e) {
                    closeSession();
                    return failedRemainder(results, planJsons.size(),
                            StorageResult.error("INVALID_RESPONSE",
                                    "存储核心返回了无法解析的结果: " + line.trim()));
                }
            }
            try {
                writer.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            lastSendNanos = System.nanoTime() - send0;
            lastWaitNanos = System.nanoTime() - wait0;
            lastParseNanos = 0; // 响应解析已在 wait 段内逐行完成
            return results;
        } catch (IOException e) {
            closeSession();
            return failedAll(planJsons.size(),
                    StorageResult.error("STORAGE_UNAVAILABLE", "无法启动存储核心: " + e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failedAll(planJsons.size(),
                    StorageResult.error("STORAGE_UNAVAILABLE", "等待存储核心时被中断"));
        }
    }

    /** 用指定错误补齐到 total 长度（results 为已完成前缀） */
    private static List<StorageResult> failedRemainder(List<StorageResult> results, int total, StorageResult error) {
        List<StorageResult> all = new ArrayList<>(results);
        while (all.size() < total) {
            all.add(error);
        }
        return all;
    }

    /** 全部条目填充同一错误 */
    private static List<StorageResult> failedAll(int total, StorageResult error) {
        List<StorageResult> all = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            all.add(error);
        }
        return all;
    }

    /** 直接以计划 JSON 调用存储核心（联调时可手工构造计划） */
    public StorageResult call(String planJson) {
        if (!Files.isExecutable(exePath)) {
            return StorageResult.error("STORAGE_UNAVAILABLE",
                    "找不到存储核心程序: " + exePath.toAbsolutePath()
                            + "（可用 -Dsqxdl.storage.exe=<路径> 指定）");
        }
        try {
            Process current = ensureSession();
            // 计划 JSON 经标准输入按行传递：Windows 下命令行参数中的双引号
            // 会被 CRT 当定界符剥离，跨语言传 JSON 必须走 stdin
            long t0 = System.nanoTime();
            current.getOutputStream().write((planJson + "\n").getBytes(StandardCharsets.UTF_8));
            current.getOutputStream().flush();
            lastSendNanos = System.nanoTime() - t0;

            // 阻塞 readLine 交给单线程池执行，主线程限时等待，防止核心无响应卡死 REPL
            t0 = System.nanoTime();
            Future<String> pendingLine = readerPool.submit(() -> stdout.readLine());
            String line;
            try {
                line = pendingLine.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                pendingLine.cancel(true);
                closeSession();
                return StorageResult.error("STORAGE_TIMEOUT", "存储核心执行超时");
            } catch (ExecutionException e) {
                closeSession();
                return StorageResult.error("STORAGE_UNAVAILABLE",
                        "读取存储核心输出失败: " + e.getCause().getMessage());
            } finally {
                lastWaitNanos = System.nanoTime() - t0;
            }
            if (line == null) {
                // 核心进程已退出（EOF），关闭会话待下次重启
                closeSession();
                return StorageResult.error("STORAGE_UNAVAILABLE", "存储核心进程已退出");
            }
            t0 = System.nanoTime();
            try {
                return StorageResult.parse(line.trim());
            } catch (RuntimeException e) {
                // 返回内容不是协议 JSON，行序可能已错乱，重建会话保证后续对齐
                closeSession();
                return StorageResult.error("INVALID_RESPONSE",
                        "存储核心返回了无法解析的结果: " + line.trim());
            } finally {
                lastParseNanos = System.nanoTime() - t0;
            }
        } catch (IOException e) {
            closeSession();
            return StorageResult.error("STORAGE_UNAVAILABLE", "无法启动存储核心: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return StorageResult.error("STORAGE_UNAVAILABLE", "等待存储核心时被中断");
        }
    }

    /** 获取存活会话进程；无会话或进程已死则重新启动 */
    private Process ensureSession() throws IOException {
        if (process != null && process.isAlive()) {
            return process;
        }
        closeSession();
        // stderr 直接透传到控制台，避免错误输出堆积撑满管道导致核心阻塞
        ProcessBuilder builder = new ProcessBuilder(exePath.toString());
        builder.redirectError(ProcessBuilder.Redirect.INHERIT);
        process = builder.start();
        stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        return process;
    }

    /** 显式结束存储服务会话：协议 exit 正常落盘退出（幂等） */
    public void close() {
        closeSession();
    }

    /** 关闭当前会话：先发协议 exit 让核心正常落盘，超时再温和终止、最后强杀（幂等） */
    private void closeSession() {
        if (process != null) {
            try {
                // 协议退出：存储核心收到 exit 后正常结束并把行数据刷入磁盘。
                // flush 耗时与脏页数量相关，超时强杀会把 db 写成半文件（页校验失败），
                // 因此分级等待：5s 正常等待 -> destroy 温和终止 -> 再等 2s -> 才强杀兜底
                process.getOutputStream().write("exit\n".getBytes(StandardCharsets.UTF_8));
                process.getOutputStream().flush();
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroy();
                    if (!process.waitFor(2, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                }
            } catch (IOException | InterruptedException e) {
                process.destroyForcibly();
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            } finally {
                process = null;
                stdout = null;
            }
        }
    }
}
