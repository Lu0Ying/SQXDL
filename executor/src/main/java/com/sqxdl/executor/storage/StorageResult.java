package com.sqxdl.executor.storage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 存储核心返回结果的 Java 模型（D 组 storage 子包），对应 storage/readme.md
 * 第 2 节的输出契约：resultset（查询数据集）/ rowcount（影响行数）/ error（错误信息）。
 * <p>resultset 有单行与流式分帧两种形态：单行（join/showTables/describeTable 及兼容）
 * 直接带 rows 数组；流式（scan/filter/project 的顶层查询）首行 header 带
 * {@code "streaming":true}，其后是若干 rows 帧与一条 end 帧，由
 * {@link StorageClient} 续读组装成同一个 {@code resultset}；本类的
 * {@link #isStreamingHeader(String)} / {@link #frameType(String)} /
 * {@link #parseRowBatch(String)} 即用于该分帧解析。
 * <p>三种来源：
 * <ul>
 *   <li>{@link #parse(String)} —— 解析 C 组核心进程输出的一行结果 JSON</li>
 *   <li>{@link #error(String, String)} —— Java 侧构造的本地错误（程序缺失、
 *       超时、语义/语法错误包装等），错误码与核心侧共用一套命名</li>
 *   <li>{@link #resultset(List, List)} / {@link #rowcount(long)} —— LOCAL 模拟层
 *       与测试直接构造的本地结果</li>
 * </ul>
 * 统一的数据形状让 Executor 渲染层、PerfTest 基准与 GUI 表格无需区分结果来自
 * 真实存储核心还是本地模拟。
 */
public class StorageResult {

    public enum Type { RESULTSET, ROWCOUNT, ERROR }

    private final Type type;
    private final List<String> columns;
    private final List<List<Object>> rows;
    private final long rowsAffected;
    private final String errorCode;
    private final String errorMessage;

    private StorageResult(Type type, List<String> columns, List<List<Object>> rows,
                          long rowsAffected, String errorCode, String errorMessage) {
        this.type = type;
        this.columns = columns;
        this.rows = rows;
        this.rowsAffected = rowsAffected;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    /** 解析存储核心输出的一行 JSON；结构不符合契约时抛出 IllegalArgumentException */
    public static StorageResult parse(String json) {
        Object root = Json.parse(json);
        if (!(root instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("结果不是 JSON 对象");
        }
        // 契约约定：success=false 时 error 对象必带错误码与信息
        if (!Boolean.TRUE.equals(map.get("success"))) {
            return fromError(map);
        }
        return switch (String.valueOf(map.get("type"))) {
            case "resultset" -> fromResultSet(map);
            case "rowcount" -> fromRowcount(map);
            default -> throw new IllegalArgumentException("未知的结果类型: " + map.get("type"));
        };
    }

    /** 构造一个供本地使用的错误结果（如找不到存储程序、调用超时） */
    public static StorageResult error(String code, String message) {
        return new StorageResult(Type.ERROR, List.of(), List.of(), 0, code, message);
    }

    /** 构造一个本地查询结果（供模拟执行/测试使用） */
    public static StorageResult resultset(List<String> columns, List<List<Object>> rows) {
        return new StorageResult(Type.RESULTSET, List.copyOf(columns), rows, 0, null, null);
    }

    /** 构造一个本地行数结果（供模拟执行/测试使用） */
    public static StorageResult rowcount(long rowsAffected) {
        return new StorageResult(Type.ROWCOUNT, List.of(), List.of(), rowsAffected, null, null);
    }

    private static StorageResult fromError(Map<?, ?> map) {
        Object error = map.get("error");
        if (error instanceof Map<?, ?> err) {
            return error(String.valueOf(err.get("code")), String.valueOf(err.get("message")));
        }
        return error("INVALID_RESPONSE", "存储核心返回了失败结果但缺少 error 信息");
    }

    private static StorageResult fromResultSet(Map<?, ?> map) {
        return new StorageResult(Type.RESULTSET, toStringList(map.get("columns")),
                toRows(map.get("rows")), 0, null, null);
    }

    /**
     * 首行是否为流式 resultset 的 header（含 {@code "streaming":true}）。
     * 流式响应后续还有若干 {@code {"type":"rows"}} 数据帧与一条 {@code {"type":"end"}}
     * 结束帧，调用方须续读至 end（或中途的 error 帧）才算收完一条响应。
     */
    public static boolean isStreamingHeader(String json) {
        Object root = Json.parse(json);
        return root instanceof Map<?, ?> map
                && Boolean.TRUE.equals(map.get("success"))
                && "resultset".equals(String.valueOf(map.get("type")))
                && Boolean.TRUE.equals(map.get("streaming"));
    }

    /** 读取一条协议行的 {@code type} 字段（rows / end / resultset / rowcount / error）；非对象返回空串 */
    public static String frameType(String json) {
        Object root = Json.parse(json);
        if (root instanceof Map<?, ?> map) {
            Object type = map.get("type");
            return type == null ? "" : String.valueOf(type);
        }
        return "";
    }

    /** 解析一条 {@code {"type":"rows"}} 数据帧中的 rows 数组 */
    public static List<List<Object>> parseRowBatch(String json) {
        Object root = Json.parse(json);
        if (!(root instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("数据帧不是 JSON 对象");
        }
        return toRows(map.get("rows"));
    }

    private static List<List<Object>> toRows(Object value) {
        List<List<Object>> rows = new ArrayList<>();
        if (value instanceof List<?> rawRows) {
            for (Object row : rawRows) {
                rows.add(new ArrayList<>(toObjectList(row)));
            }
        }
        return rows;
    }

    private static StorageResult fromRowcount(Map<?, ?> map) {
        long affected = map.get("rowsAffected") instanceof Number n ? n.longValue() : 0;
        return new StorageResult(Type.ROWCOUNT, List.of(), List.of(), affected, null, null);
    }

    private static List<String> toStringList(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }

    private static List<Object> toObjectList(Object value) {
        List<Object> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            result.addAll(list);
        }
        return result;
    }

    public Type getType() {
        return type;
    }

    public List<String> getColumns() {
        return columns;
    }

    public List<List<Object>> getRows() {
        return rows;
    }

    public long getRowsAffected() {
        return rowsAffected;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
