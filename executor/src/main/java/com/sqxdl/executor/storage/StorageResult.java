package com.sqxdl.executor.storage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 存储核心返回结果的 Java 模型，对应 storage/readme.md 第 2 节的输出契约：
 * resultset（查询数据集）/ rowcount（影响行数）/ error（错误信息）。
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
        List<String> columns = toStringList(map.get("columns"));
        List<List<Object>> rows = new ArrayList<>();
        if (map.get("rows") instanceof List<?> rawRows) {
            for (Object row : rawRows) {
                rows.add(new ArrayList<>(toObjectList(row)));
            }
        }
        return new StorageResult(Type.RESULTSET, columns, rows, 0, null, null);
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
