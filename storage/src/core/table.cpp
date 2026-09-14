#include "table.h"

#include "storage_error.h"

Table::Table()
{
}

Table::Table(std::string name, std::vector<Column> columns)
    : name_(std::move(name)), columns_(std::move(columns))
{
}

const std::string &Table::name() const
{
    return name_;
}

const std::vector<Column> &Table::columns() const
{
    return columns_;
}

std::vector<std::string> Table::column_names() const
{
    std::vector<std::string> names;
    names.reserve(columns_.size());
    for (const auto &column : columns_)
    {
        names.push_back(column.name);
    }
    return names;
}

bool Table::has_column(const std::string &name) const
{
    for (const auto &column : columns_)
    {
        if (column.name == name)
        {
            return true;
        }
    }
    return false;
}

size_t Table::column_index(const std::string &name) const
{
    for (size_t i = 0; i < columns_.size(); ++i)
    {
        if (columns_[i].name == name)
        {
            return i;
        }
    }
    throw StorageError("COLUMN_NOT_FOUND", "Column " + name + " not found");
}
