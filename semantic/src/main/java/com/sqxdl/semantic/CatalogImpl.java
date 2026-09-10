package com.sqxdl.semantic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据字典实现（B 组）。
 * 职责：在内存中维护表名 -> 列信息清单的映射，供语义分析校验与执行器查询。
 * 注：存储引擎已改为独立 C++ 程序（见 storage/readme.md），本类只维护
 *     建表后的元数据副本，实际数据存取由存储核心完成。
 */
public class CatalogImpl {

    /**
     * 数据类型枚举。
     */
    public enum DataType {
        INT,
        VARCHAR,
        BOOLEAN
    }

    /**
     * 列信息：列名 + 类型。
     */
    public static class ColumnInfo {
        private final String name;
        private final DataType type;

        public ColumnInfo(String name, DataType type) {
            this.name = name;
            this.type = type;
        }

        public String getName() {
            return name;
        }

        public DataType getType() {
            return type;
        }
    }

    /** 表名 -> 列信息清单（保持建表顺序） */
    private final Map<String, List<ColumnInfo>> tables = new HashMap<>();

    /**
     * 建表：登记表名与列名清单（默认所有列为 VARCHAR 类型）。
     *
     * @param name    表名
     * @param columns 列名清单
     * @throws IllegalArgumentException 表已存在时抛出
     */
    public void createTable(String name, List<String> columns) {
        List<ColumnInfo> columnInfos = new ArrayList<>();
        for (String col : columns) {
            columnInfos.add(new ColumnInfo(col, DataType.VARCHAR));
        }
        createTableWithTypes(name, columnInfos);
    }

    /**
     * 建表：登记表名与带类型的列信息清单。
     *
     * @param name    表名
     * @param columns 列信息清单
     * @throws IllegalArgumentException 表已存在时抛出
     */
    public void createTableWithTypes(String name, List<ColumnInfo> columns) {
        if (tables.containsKey(name)) {
            throw new IllegalArgumentException("表 " + name + " 已存在");
        }
        tables.put(name, new ArrayList<>(columns));
    }

    /**
     * 判断表是否存在。
     */
    public boolean tableExists(String name) {
        return tables.containsKey(name);
    }

    /**
     * 删除表。如果表不存在则抛出 IllegalArgumentException。
     *
     * @param name 要删除的表名
     */
    public void dropTable(String name) {
        if (!tables.containsKey(name)) {
            throw new IllegalArgumentException("Table does not exist: " + name);
        }
        tables.remove(name);
    }

    /**
     * 查询表的列名清单。
     *
     * @return 列名清单的副本；表不存在时返回 null（由调用方判断）
     */
    public List<String> getColumns(String name) {
        List<ColumnInfo> columns = tables.get(name);
        if (columns == null) {
            return null;
        }
        List<String> names = new ArrayList<>();
        for (ColumnInfo col : columns) {
            names.add(col.getName());
        }
        return names;
    }

    /**
     * 查询指定列的类型。
     *
     * @param tableName  表名
     * @param columnName 列名
     * @return 列类型；表不存在或列不存在时返回 null
     */
    public DataType getColumnType(String tableName, String columnName) {
        List<ColumnInfo> columns = tables.get(tableName);
        if (columns == null) {
            return null;
        }
        for (ColumnInfo col : columns) {
            if (col.getName().equals(columnName)) {
                return col.getType();
            }
        }
        return null;
    }

    /**
     * 判断列是否存在。
     *
     * @param tableName  表名
     * @param columnName 列名
     * @return 列是否存在；表不存在时返回 false
     */
    public boolean columnExists(String tableName, String columnName) {
        return getColumnType(tableName, columnName) != null;
    }

    /**
     * 查询列信息完整清单。
     *
     * @param name 表名
     * @return 列信息清单的副本；表不存在时返回 null
     */
    public List<ColumnInfo> getColumnInfos(String name) {
        List<ColumnInfo> columns = tables.get(name);
        return columns == null ? null : new ArrayList<>(columns);
    }

    // ========== 与存储引擎同步（方案 B） ==========

    /**
     * 从存储引擎同步所有表的元数据到本地数据字典。
     * <p>
     * 流程：
     * <pre>
     *   1. provider.getTableNames() → 拿到所有表名（对应 showTables）
     *   2. 对每张表 provider.getTableColumns() → 拿到列名 + 类型（对应 describeTable）
     *   3. 注册到 CatalogImpl
     * </pre>
     * 同步前会清空本地已登记的全部表，保证与存储引擎一致。
     *
     * @param provider 存储引擎元数据提供者
     */
    public void syncFromStorage(TableMetadataProvider provider) {
        tables.clear();
        List<String> tableNames = provider.getTableNames();
        for (String tableName : tableNames) {
            List<ColumnInfo> columns = provider.getTableColumns(tableName);
            if (columns != null && !columns.isEmpty()) {
                tables.put(tableName, new ArrayList<>(columns));
            }
        }
    }

    /**
     * 从存储引擎同步单张表的元数据（用于 CREATE TABLE 之后增量更新）。
     * <p>
     * 若表已存在于本地，则覆盖更新；若 provider 返回空列表则不做任何操作
     * （如表刚被删除）。
     *
     * @param tableName 表名
     * @param provider  存储引擎元数据提供者
     */
    public void syncTableFromStorage(String tableName, TableMetadataProvider provider) {
        List<ColumnInfo> columns = provider.getTableColumns(tableName);
        if (columns == null || columns.isEmpty()) {
            tables.remove(tableName);
            return;
        }
        tables.put(tableName, new ArrayList<>(columns));
    }

    /**
     * 获取所有已登记的表名（用于调试/测试）。
     *
     * @return 表名列表的副本
     */
    public List<String> getAllTableNames() {
        return new ArrayList<>(tables.keySet());
    }
}
