package com.sqxdl.executor.storage;

import com.sqxdl.semantic.PlanNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * 存储核心客户端：通过进程边界调用 storage_core.exe，
 * 输入物理计划 JSON（命令行参数），输出结果 JSON（stdout 一行）。
 * 协议见 storage/readme.md。
 * 任何失败（程序缺失、启动失败、超时、返回异常）都封装为 error 结果返回，
 * 不向调用方抛异常，保证 REPL 不崩溃。
 */
public class StorageClient {

    /** 等待存储核心完成的超时时间（秒） */
    private static final long TIMEOUT_SECONDS = 10;

    /** 存储核心可执行文件路径 */
    private final Path exePath;

    public StorageClient() {
        // 可通过 -Dsqxdl.storage.exe=<路径> 覆盖；默认取仓库约定位置
        String configured = System.getProperty("sqxdl.storage.exe");
        this.exePath = Path.of(configured != null ? configured : "storage/storage_core.exe");
    }

    /** 执行计划：序列化为物理计划 JSON 后调用存储核心 */
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
            // 计划 JSON 经标准输入传递：Windows 下命令行参数中的双引号
            // 会被 CRT 当定界符剥离，跨语言传 JSON 必须走 stdin
            Process process = new ProcessBuilder(exePath.toString()).start();
            process.getOutputStream().write(planJson.getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return StorageResult.error("STORAGE_TIMEOUT", "存储核心执行超时");
            }
            String stdout = readAll(process, true);
            String stderr = readAll(process, false);
            return parseResponse(stdout, stderr, process.exitValue());
        } catch (IOException e) {
            return StorageResult.error("STORAGE_UNAVAILABLE", "无法启动存储核心: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return StorageResult.error("STORAGE_UNAVAILABLE", "等待存储核心时被中断");
        }
    }

    /**
     * 解析存储核心的响应。
     * 协议约定 stdout 输出一行 JSON；这里取最后一个非空行解析，
     * 以兼容核心在结果前输出状态行（如当前骨架的 "JSON OK"）。
     */
    private StorageResult parseResponse(String stdout, String stderr, int exitCode) {
        String line = lastNonEmptyLine(stdout);
        if (line != null) {
            try {
                return StorageResult.parse(line);
            } catch (RuntimeException ignored) {
                // 返回内容不是协议 JSON，落入下方错误分支
            }
        }
        String detail = !stderr.isBlank() ? stderr.trim() : String.valueOf(stdout).trim();
        if (exitCode != 0) {
            return StorageResult.error("INTERNAL_ERROR",
                    "存储核心异常退出(退出码 " + exitCode + "): " + detail);
        }
        return StorageResult.error("INVALID_RESPONSE", "存储核心返回了无法解析的结果: " + detail);
    }

    private String readAll(Process process, boolean stdout) throws IOException {
        return new String((stdout ? process.getInputStream() : process.getErrorStream())
                .readAllBytes(), StandardCharsets.UTF_8);
    }

    /** 取文本中最后一个非空行；无内容时返回 null */
    private String lastNonEmptyLine(String text) {
        String[] lines = text.split("\r?\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].isBlank()) {
                return lines[i].trim();
            }
        }
        return null;
    }
}
