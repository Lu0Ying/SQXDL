#ifndef STORAGE_CORE_DATABASE_H
#define STORAGE_CORE_DATABASE_H

#include <map>
#include <string>
#include <vector>

#include "column.h"
#include "table.h"

// 数据库：进程内所有表组成的目录（catalog），存储核心各操作通过 instance()
// 访问同一实例。目录以默认存储目录 /data/ 下的 catalog.json 持久化：
// 首次访问任一接口时从磁盘加载已建表，建表/删表后把目录写回磁盘，
// 进程重启后表结构仍可从磁盘恢复。
class Database
{
public:
    // 进程内全局唯一实例
    static Database &instance();

    bool has_table(const std::string &name) const;

    // 表不存在抛 StorageError(TABLE_NOT_FOUND)
    Table &get_table(const std::string &name);
    const Table &get_table(const std::string &name) const;

    // 表已存在抛 StorageError(TABLE_ALREADY_EXISTS)；
    // 目录写回失败抛 StorageError(INTERNAL_ERROR)
    void create_table(const std::string &name, std::vector<Column> columns);

    // 表不存在抛 StorageError(TABLE_NOT_FOUND)；
    // 目录写回失败抛 StorageError(INTERNAL_ERROR)
    void drop_table(const std::string &name);

    // 所有表名（按名称排序，供 showTables 使用）
    std::vector<std::string> table_names() const;

private:
    Database() = default;

    // 惰性加载：仅首次访问时把 catalog.json 中的表读入内存；
    // 目录缺失视为空目录，目录文件损坏抛 StorageError(INTERNAL_ERROR)
    void ensure_loaded() const;

    // 把当前内存目录整体写回 catalog.json
    void save_catalog() const;

    mutable bool loaded_ = false;
    mutable std::map<std::string, Table> tables_;
};

#endif
