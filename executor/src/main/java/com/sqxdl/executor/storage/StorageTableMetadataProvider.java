package com.sqxdl.executor.storage;

import com.sqxdl.semantic.CatalogImpl;
import com.sqxdl.semantic.TableMetadataProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于 {@link StorageClient} 的表元数据提供者（D 组 storage 子包）。
 * <p>实现 B 组定义的 {@link TableMetadataProvider} 接口：启动时通过存储核心的
 * {@code showTables} 与 {@code describeTable} 操作获取表名与列结构（列名+类型），
 * 供 {@link CatalogImpl#syncFromStorage} 把核心侧已持久化的表结构同步进 Java
 * 数据字典——这样重启后无需重新建表，语义校验即认识全部存量表。
 * <p>describeTable 协议（带类型版本）：
 * <pre>
 *   请求: {"op":"describeTable","table":"student"}
 *   返回: {"success":true,"type":"resultset","columns":["column","type"],
 *          "rows":[["id","INT"],["name","VARCHAR"]]}
 * </pre>
 * 兼容旧协议（columns=["field"]，仅有列名无类型）：此时全部按 VARCHAR 处理。
 */
public class StorageTableMetadataProvider implements TableMetadataProvider {

    private final StorageClient client;

    public StorageTableMetadataProvider(StorageClient client) {
        this.client = client;
    }

    @Override
    public List<String> getTableNames() {
        StorageResult result = client.call("{\"op\":\"showTables\"}");
        if (result.getType() != StorageResult.Type.RESULTSET) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (List<Object> row : result.getRows()) {
            if (row != null && !row.isEmpty()) {
                names.add(String.valueOf(row.get(0)));
            }
        }
        return names;
    }

    @Override
    public List<CatalogImpl.ColumnInfo> getTableColumns(String tableName) {
        String plan = buildDescribeTablePlan(tableName);
        StorageResult result = client.call(plan);
        if (result.getType() != StorageResult.Type.RESULTSET) {
            return List.of();
        }

        List<CatalogImpl.ColumnInfo> columns = new ArrayList<>();
        boolean hasType = result.getColumns() != null && result.getColumns().size() >= 2;
        for (List<Object> row : result.getRows()) {
            if (row == null || row.isEmpty()) {
                continue;
            }
            String colName = String.valueOf(row.get(0));
            CatalogImpl.DataType dataType = hasType
                    ? parseDataType(String.valueOf(row.get(1)))
                    : CatalogImpl.DataType.VARCHAR;
            columns.add(new CatalogImpl.ColumnInfo(colName, dataType));
        }
        return columns;
    }

    /**
     * 构造 describeTable 请求 JSON，对表名做转义。
     */
    private String buildDescribeTablePlan(String tableName) {
        String escaped = tableName.replace("\\", "\\\\")
                                  .replace("\"", "\\\"");
        return "{\"op\":\"describeTable\",\"table\":\"" + escaped + "\"}";
    }

    /**
     * 将存储引擎返回的类型字符串解析为 {@link CatalogImpl.DataType}。
     * 大小写不敏感，支持常见别名。
     */
    private CatalogImpl.DataType parseDataType(String typeName) {
        if (typeName == null) {
            return CatalogImpl.DataType.VARCHAR;
        }
        return switch (typeName.toUpperCase().trim()) {
            case "INT", "INTEGER" -> CatalogImpl.DataType.INT;
            case "VARCHAR", "STRING", "TEXT", "CHAR" -> CatalogImpl.DataType.VARCHAR;
            case "BOOLEAN", "BOOL" -> CatalogImpl.DataType.BOOLEAN;
            default -> CatalogImpl.DataType.VARCHAR;
        };
    }
}
