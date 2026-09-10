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

size_t Table::row_count() const
{
    return rows_.size();
}

const std::vector<Row> &Table::rows() const
{
    return rows_;
}

const Row &Table::row(size_t index) const
{
    if (index >= rows_.size())
    {
        throw StorageError("INTERNAL_ERROR", "Row index out of range: " + std::to_string(index));
    }
    return rows_[index];
}

Row &Table::row(size_t index)
{
    if (index >= rows_.size())
    {
        throw StorageError("INTERNAL_ERROR", "Row index out of range: " + std::to_string(index));
    }
    return rows_[index];
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

void Table::append_row(Row row)
{
    if (row.size() != columns_.size())
    {
        throw StorageError("INTERNAL_ERROR",
                           "Insert value count " + std::to_string(row.size()) +
                               " does not match column count " +
                               std::to_string(columns_.size()));
    }
    rows_.push_back(std::move(row));
}

void Table::remove_row(size_t index)
{
    if (index >= rows_.size())
    {
        throw StorageError("INTERNAL_ERROR", "Row index out of range: " + std::to_string(index));
    }
    rows_.erase(rows_.begin() + static_cast<std::ptrdiff_t>(index));
}

nlohmann::json Table::to_json() const
{
    nlohmann::json columns_json = nlohmann::json::array();
    for (const auto &column : columns_)
    {
        columns_json.push_back(column.name);
    }
    nlohmann::json rows_json = nlohmann::json::array();
    for (const auto &row : rows_)
    {
        rows_json.push_back(row.to_json());
    }
    return {{"columns", columns_json}, {"rows", rows_json}};
}
