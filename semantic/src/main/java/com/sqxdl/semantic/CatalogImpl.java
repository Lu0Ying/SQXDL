package com.sqxdl.semantic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据字典实现（B 组）。
 * 职责：在内存中维护表名 -> 列名清单的映射，供语义分析校验与执行器查询。
 * 注：存储引擎已改为独立 C++ 程序（见 storage/readme.md），本类只维护
 *     建表后的元数据副本，实际数据存取由存储核心完成。
 */
public class CatalogImpl {

    /** 表名 -> 列名清单（保持建表顺序） */
    private final Map<String, List<String>> tables = new HashMap<>();

    /**
     * 建表：登记表名与列名清单。
     *
     * @param name    表名
     * @param columns 列名清单
     * @throws IllegalArgumentException 表已存在时抛出
     */
    public void createTable(String name, List<String> columns) {
        if (tables.containsKey(name)) {
            throw new IllegalArgumentException("表 " + name + " 已存在");
        }
        // 复制一份，避免外部修改影响元数据
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
        List<String> columns = tables.get(name);
        return columns == null ? null : new ArrayList<>(columns);
    }
}
