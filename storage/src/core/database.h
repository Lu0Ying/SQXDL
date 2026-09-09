#ifndef STORAGE_CORE_DATABASE_H
#define STORAGE_CORE_DATABASE_H

#include <map>
#include <string>
#include <vector>

#include "column.h"
#include "table.h"

// 数据库：进程内所有表组成的目录（catalog），
// 存储核心各操作通过 instance() 访问同一实例；数据驻留内存，
// 生命周期与服务进程一致（readme 未定义持久化要求）
class Database
{
public:
    // 进程内全局唯一实例
    static Database &instance();

    bool has_table(const std::string &name) const;

    // 表不存在抛 StorageError(TABLE_NOT_FOUND)
    Table &get_table(const std::string &name);
    const Table &get_table(const std::string &name) const;

    // 表已存在抛 StorageError(TABLE_ALREADY_EXISTS)
    void create_table(const std::string &name, std::vector<Column> columns);

    // 表不存在抛 StorageError(TABLE_NOT_FOUND)
    void drop_table(const std::string &name);

    // 所有表名（按名称排序，供 showTables 使用）
    std::vector<std::string> table_names() const;

private:
    Database() = default;

    std::map<std::string, Table> tables_;
};

#endif
