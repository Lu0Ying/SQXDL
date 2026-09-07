package com.sqxdl.storage;

import java.util.List;

/**
 * 数据字典（系统目录）接口，是元数据管理的契约。
 * 记录表名与列名等表结构信息：语义分析阶段据此校验表/列是否存在，
 * 执行器据此获取表结构。具体持久化方式由存储层实现类决定。
 */
public interface Catalog {

    /**
     * 建表登记：在数据字典中记录表名及其列名清单。
     *
     * @param name    表名
     * @param columns 列名清单（按定义顺序）
     */
    void createTable(String name, List<String> columns);

    /**
     * 判断表是否已存在。
     *
     * @param name 表名
     * @return 存在返回 true，否则返回 false
     */
    boolean tableExists(String name);

    /**
     * 获取表的列名清单。
     *
     * @param name 表名
     * @return 列名清单（按定义顺序）；表不存在时的行为由实现类约定
     */
    List<String> getColumns(String name);
}
