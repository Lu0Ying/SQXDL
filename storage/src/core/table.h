#ifndef STORAGE_CORE_TABLE_H
#define STORAGE_CORE_TABLE_H

#include <string>
#include <vector>

#include "column.h"

// 表结构：表名 + 列定义（schema），列顺序即行内字段顺序。
// 行数据不再驻留内存，统一由 RowStore 以页式增量方式读写（见 row_store.h），
// 因此本类只承担表结构（catalog）职责。
class Table
{
public:
    Table();
    Table(std::string name, std::vector<Column> columns);

    const std::string &name() const;
    const std::vector<Column> &columns() const;
    std::vector<std::string> column_names() const;

    bool has_column(const std::string &name) const;
    // 列不存在抛 StorageError(COLUMN_NOT_FOUND)
    size_t column_index(const std::string &name) const;

private:
    std::string name_;
    std::vector<Column> columns_;
};

#endif
