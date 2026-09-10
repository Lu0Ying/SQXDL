package com.sqxdl.semantic;

import java.util.List;

/**
 * 表元数据提供者接口（方案 B）。
 * <p>
 * 抽象「从存储引擎获取表结构信息」的能力，使 {@link CatalogImpl}
 * 能在启动时与存储引擎同步，而无需直接依赖 executor 模块。
 * <p>
 * 同步流程：
 * <pre>
 *   1. {@link #getTableNames()} —— 对应 storage 的 showTables，拿到所有表名
 *   2. {@link #getTableColumns(String)} —— 对应 storage 的 describeTable，拿到列名 + 类型
 *   3. 注册到 CatalogImpl
 * </pre>
 * <p>
 * 具体实现由 executor 模块提供（基于 {@code StorageClient}），
 * 也可用 mock 实现做单元测试。
 */
public interface TableMetadataProvider {

    /**
     * 获取存储引擎中所有表名。
     *
     * @return 表名列表（无表时返回空列表，而非 null）
     */
    List<String> getTableNames();

    /**
     * 获取指定表的列信息（列名 + 类型），按建表顺序排列。
     * <p>
     * 对应 storage 的 describeTable 操作（方案 B 新增），
     * 返回 {@code columns:["column","type"]} 的结果集。
     *
     * @param tableName 表名
     * @return 列信息列表（表不存在时返回空列表，而非 null）
     */
    List<CatalogImpl.ColumnInfo> getTableColumns(String tableName);
}
