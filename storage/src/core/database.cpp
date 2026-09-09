#include "database.h"

#include "storage_error.h"

Database &Database::instance()
{
    static Database database;
    return database;
}

bool Database::has_table(const std::string &name) const
{
    return tables_.find(name) != tables_.end();
}

Table &Database::get_table(const std::string &name)
{
    auto it = tables_.find(name);
    if (it == tables_.end())
    {
        throw StorageError("TABLE_NOT_FOUND", "表 " + name + " 不存在");
    }
    return it->second;
}

const Table &Database::get_table(const std::string &name) const
{
    auto it = tables_.find(name);
    if (it == tables_.end())
    {
        throw StorageError("TABLE_NOT_FOUND", "表 " + name + " 不存在");
    }
    return it->second;
}

void Database::create_table(const std::string &name, std::vector<Column> columns)
{
    if (has_table(name))
    {
        throw StorageError("TABLE_ALREADY_EXISTS", "表 " + name + " 已存在");
    }
    tables_.emplace(name, Table(name, std::move(columns)));
}

void Database::drop_table(const std::string &name)
{
    auto it = tables_.find(name);
    if (it == tables_.end())
    {
        throw StorageError("TABLE_NOT_FOUND", "表 " + name + " 不存在");
    }
    tables_.erase(it);
}

std::vector<std::string> Database::table_names() const
{
    std::vector<std::string> names;
    names.reserve(tables_.size());
    for (const auto &entry : tables_)
    {
        names.push_back(entry.first);
    }
    return names;
}
