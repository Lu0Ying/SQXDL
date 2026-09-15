#ifndef STORAGE_CORE_DATABASE_H
#define STORAGE_CORE_DATABASE_H

#include <map>
#include <memory>
#include <string>
#include <vector>

#include "column.h"
#include "table.h"

class RowStore;

// 数据库：进程内所有表结构组成的目录（catalog），存储核心各操作通过 instance()
// 访问同一实例。表结构持久化在默认存储目录的 catalog.json 中，
// 表内行数据持久化在独立数据库文件（storage.db，页式存储，见 RowStore）。
//
// 加载策略（惰性、只加载结构）：首次访问任一接口时只读入表结构并打开行数据页文件，
// 不加载任何行数据；行数据的读取由各操作通过 RowStore 逐页流式完成，
// 因此进程内存占用与库中数据总量无关（页缓存上限由缓冲池页数 n 决定）。
class Database
{
public:
    ~Database();

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

    // 行数据页式存储：行级增量读写（insert/update/delete）与流式扫描的唯一入口
    RowStore &row_store() const;

    // 把行数据目录与缓冲池脏页刷盘，供行级写操作在变更后调用；
    // 写盘失败抛 StorageError(INTERNAL_ERROR)
    void flush() const;

private:
    Database() = default;

    // 惰性加载：读入表结构（catalog）并打开行数据页文件，不加载行数据；
    // 目录缺失视为空目录，文件损坏抛 StorageError(INTERNAL_ERROR)
    void ensure_schema() const;

    // 把当前内存中的表结构（schema）写回 catalog.json
    void save_catalog() const;

    // 惰性打开行数据页文件（RowStore）
    void open_row_store() const;

    mutable bool loaded_ = false;
    mutable std::map<std::string, Table> tables_; // 仅表结构，不含行数据
    mutable std::unique_ptr<RowStore> row_store_;
};

#endif
