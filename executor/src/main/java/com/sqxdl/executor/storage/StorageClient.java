package com.sqxdl.executor.storage;

import com.sqxdl.semantic.PlanNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 存储核心客户端：以服务式会话调用 storage_core.exe。
 * 按协议（storage/readme.md），核心进程启动后常驻主循环：
 * 读一行计划 JSON -> 执行 -> 输出一行结果 JSON -> 继续。
 * 因此进程跨语句复用，建表目录与数据在会话内保持；
 * 进程意外退出或调用超时时关闭会话，下一次执行自动重启新进程。
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

    public StorageClient() {
        // 可通过 -Dsqxdl.storage.exe=<路径> 覆盖；默认取仓库约定位置
        String configured = System.getProperty("sqxdl.storage.exe");
        this.exePath = Path.of(configured != null ? configured : "storage/storage_core.exe");
        // JVM 退出时结束存储核心，避免残留子进程
        Runtime.getRuntime().addShutdownHook(new Thread(this::closeSession));
    }

    /** 执行计划：序列化为物理计划 JSON 后经会话调用存储核心 */
    public StorageResult execute(PlanNode plan) {
        return call(PhysicalPlanJson.serialize(plan));
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
            current.getOutputStream().write((planJson + "\n").getBytes(StandardCharsets.UTF_8));
            current.getOutputStream().flush();

            // 阻塞 readLine 交给单线程池执行，主线程限时等待，防止核心无响应卡死 REPL
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
            }
            if (line == null) {
                // 核心进程已退出（EOF），关闭会话待下次重启
                closeSession();
                return StorageResult.error("STORAGE_UNAVAILABLE", "存储核心进程已退出");
            }
            try {
                return StorageResult.parse(line.trim());
            } catch (RuntimeException e) {
                // 返回内容不是协议 JSON，行序可能已错乱，重建会话保证后续对齐
                closeSession();
                return StorageResult.error("INVALID_RESPONSE",
                        "存储核心返回了无法解析的结果: " + line.trim());
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
