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
        VARCHAR
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
}
