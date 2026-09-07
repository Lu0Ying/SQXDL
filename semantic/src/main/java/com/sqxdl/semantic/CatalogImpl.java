package com.sqxdl.semantic;

import com.sqxdl.storage.Catalog;

import java.util.List;

/**
 * 数据字典实现（B 组）。
 * 职责：在内存中维护表名 -> 列名清单的映射，供语义分析校验与执行器查询。
 */
public class CatalogImpl implements Catalog {

    @Override
    public void createTable(String name, List<String> columns) {
        // TODO: 用 HashMap 存储表结构（key=表名，value=列名清单）；
        //       表已存在时应抛出重复建表异常
    }

    @Override
    public boolean tableExists(String name) {
        // TODO: 查询 HashMap 判断表名是否存在
        return false;
    }

    @Override
    public List<String> getColumns(String name) {
        // TODO: 从 HashMap 取出列名清单；表不存在时抛出异常或返回 null（组内约定后统一）
        return null;
    }
}
