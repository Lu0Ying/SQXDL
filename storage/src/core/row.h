#ifndef STORAGE_CORE_ROW_H
#define STORAGE_CORE_ROW_H

#include <vector>

#include "value.h"

// 行：一行数据的有序值序列，字段顺序与所属表的列定义一一对应
class Row
{
public:
    Row();

    size_t size() const;
    bool empty() const;

    // 越界索引抛 StorageError(INTERNAL_ERROR)
    const Value &at(size_t index) const;
    Value &at(size_t index);

    void append(Value value);

    // JSON 数组 <-> 行：用于 insert 的 values 与结果集 rows 的序列化
    static Row from_json(const nlohmann::json &array);
    nlohmann::json to_json() const;

private:
    std::vector<Value> values_;
};

#endif
