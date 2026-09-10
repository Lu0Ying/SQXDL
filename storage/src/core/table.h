#ifndef STORAGE_CORE_TABLE_H
#define STORAGE_CORE_TABLE_H

#include <string>
#include <vector>

#include "column.h"
#include "nlohmann/json.hpp"
#include "row.h"

// 表：列定义 + 行数据（内存表），列顺序即行内字段顺序；
// 行数据经 Database 写入独立页文件，进程重启后从页文件恢复
class Table
{
public:
    Table();
    Table(std::string name, std::vector<Column> columns);

    const std::string &name() const;
    const std::vector<Column> &columns() const;
    std::vector<std::string> column_names() const;

    size_t row_count() const;
    const std::vector<Row> &rows() const;

    // 按索引访问行；越界抛 StorageError(INTERNAL_ERROR)
    const Row &row(size_t index) const;
    Row &row(size_t index);

    bool has_column(const std::string &name) const;
    // 列不存在抛 StorageError(COLUMN_NOT_FOUND)
    size_t column_index(const std::string &name) const;

    // 追加一行：字段数须与列数一致，否则抛 StorageError(INVALID_PLAN)
    void append_row(Row row);

    // 按索引删除一行（越界抛 StorageError(INTERNAL_ERROR)），供 delete 使用
    void remove_row(size_t index);

    // 序列化为结果集数据：{"columns":[...],"rows":[[...],...]}
    nlohmann::json to_json() const;

private:
    std::string name_;
    std::vector<Column> columns_;
    std::vector<Row> rows_;
};

#endif
