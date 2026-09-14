#include "database.h"

#include <filesystem>
#include <fstream>

#include "row_store.h"
#include "storage_error.h"

namespace
{
    // 默认存储目录（readme 规范），目录不存在时自动创建
    const std::filesystem::path &data_dir()
    {
        static const std::filesystem::path dir = std::filesystem::path("/SQXDL/data/");
        return dir;
    }

    std::filesystem::path catalog_path()
    {
        return data_dir() / "catalog.json";
    }

    // 行数据页文件（与表结构 catalog.json 分离，页式存储见 RowStore）
    std::filesystem::path row_file_path()
    {
        return data_dir() / "storage.db";
    }

    // 读取表结构文件：不存在视为空目录（首次运行）；存在但损坏抛 INTERNAL_ERROR
    nlohmann::json read_catalog()
    {
        const std::filesystem::path path = catalog_path();
        std::error_code ec;
        if (!std::filesystem::exists(path, ec))
        {
            if (ec)
            {
                throw StorageError("INTERNAL_ERROR",
                                   "Failed to access storage directory " + path.string() + ": " + ec.message());
            }
            return nlohmann::json::object();
        }
        std::ifstream in(path);
        if (!in.is_open())
        {
            throw StorageError("INTERNAL_ERROR", "Failed to open catalog file " + path.string());
        }
        nlohmann::json catalog;
        try
        {
            in >> catalog;
        }
        catch (const std::exception &)
        {
            throw StorageError("INTERNAL_ERROR", "Catalog file is corrupted (parse failed): " + path.string());
        }
        if (!catalog.is_object())
        {
            throw StorageError("INTERNAL_ERROR", "Catalog file has invalid format: " + path.string());
        }
        return catalog;
    }
} // namespace

Database::~Database() = default;

Database &Database::instance()
{
    static Database database;
    return database;
}

bool Database::has_table(const std::string &name) const
{
    ensure_schema();
    return tables_.find(name) != tables_.end();
}

Table &Database::get_table(const std::string &name)
{
    ensure_schema();
    auto it = tables_.find(name);
    if (it == tables_.end())
    {
        throw StorageError("TABLE_NOT_FOUND", "Table " + name + " not found");
    }
    return it->second;
}

const Table &Database::get_table(const std::string &name) const
{
    ensure_schema();
    auto it = tables_.find(name);
    if (it == tables_.end())
    {
        throw StorageError("TABLE_NOT_FOUND", "Table " + name + " not found");
    }
    return it->second;
}

void Database::create_table(const std::string &name, std::vector<Column> columns)
{
    ensure_schema();
    if (tables_.find(name) != tables_.end())
    {
        throw StorageError("TABLE_ALREADY_EXISTS", "Table " + name + " already exists");
    }
    tables_.emplace(name, Table(name, std::move(columns)));
    try
    {
        save_catalog();
    }
    catch (...)
    {
        tables_.erase(name); // 写盘失败回滚内存变更，保持与磁盘一致
        throw;
    }
}

void Database::drop_table(const std::string &name)
{
    ensure_schema();
    auto it = tables_.find(name);
    if (it == tables_.end())
    {
        throw StorageError("TABLE_NOT_FOUND", "Table " + name + " not found");
    }
    Table removed = std::move(it->second);
    tables_.erase(it);
    try
    {
        // 回收行数据页并更新目录，再写回表结构
        row_store_->remove_table(name);
        row_store_->flush();
        save_catalog();
    }
    catch (...)
    {
        tables_.emplace(name, std::move(removed)); // 写盘失败回滚内存变更
        throw;
    }
}

std::vector<std::string> Database::table_names() const
{
    ensure_schema();
    std::vector<std::string> names;
    names.reserve(tables_.size());
    for (const auto &entry : tables_)
    {
        names.push_back(entry.first);
    }
    return names;
}

RowStore &Database::row_store() const
{
    ensure_schema();
    return *row_store_;
}

void Database::flush() const
{
    ensure_schema();
    row_store_->flush();
}

void Database::ensure_schema() const
{
    if (loaded_)
    {
        return;
    }
    const nlohmann::json catalog = read_catalog();

    // 兼容历史上「行数据内嵌于 catalog.json」的格式：先收集，稍后迁移进页文件
    std::map<std::string, std::vector<Row>> legacy_rows;
    for (auto it = catalog.begin(); it != catalog.end(); ++it)
    {
        const std::string name = it.key();
        const nlohmann::json &entry = it.value();
        const nlohmann::json &columns_json =
            entry.is_array() ? entry : entry.value("columns", nlohmann::json());
        if (!columns_json.is_array())
        {
            throw StorageError("INTERNAL_ERROR", "Invalid column definition for table " + name + " in catalog file");
        }
        std::vector<Column> columns;
        try
        {
            columns = columns_from_json(columns_json);
        }
        catch (const StorageError &)
        {
            throw StorageError("INTERNAL_ERROR", "Corrupted column definition for table " + name + " in catalog file");
        }
        tables_.emplace(name, Table(name, std::move(columns)));

        if (!entry.is_array() && entry.contains("rows") && entry.at("rows").is_array() &&
            !entry.at("rows").empty())
        {
            std::vector<Row> rows;
            rows.reserve(entry.at("rows").size());
            for (const auto &row_json : entry.at("rows"))
            {
                try
                {
                    rows.push_back(Row::from_json(row_json));
                }
                catch (const StorageError &)
                {
                    throw StorageError("INTERNAL_ERROR", "Corrupted row data for table " + name + " in catalog file");
                }
            }
            legacy_rows.emplace(name, std::move(rows));
        }
    }

    open_row_store();

    // 旧格式内嵌行：写入页文件后把 catalog 重写为仅含表结构
    if (!legacy_rows.empty())
    {
        for (const auto &entry : legacy_rows)
        {
            for (const Row &row : entry.second)
            {
                row_store_->insert_row(entry.first, row);
            }
        }
        row_store_->flush();
        save_catalog();
    }

    loaded_ = true;
}

void Database::save_catalog() const
{
    std::error_code ec;
    std::filesystem::create_directories(data_dir(), ec);
    if (ec)
    {
        throw StorageError("INTERNAL_ERROR",
                           "Failed to create storage directory " + data_dir().string() + ": " + ec.message());
    }
    nlohmann::json catalog = nlohmann::json::object();
    for (const auto &entry : tables_)
    {
        nlohmann::json columns_json = nlohmann::json::array();
        for (const auto &column : entry.second.columns())
        {
            columns_json.push_back({{"name", column.name}, {"type", column.type}});
        }
        catalog[entry.first] = std::move(columns_json);
    }
    std::ofstream out(catalog_path(), std::ios::trunc);
    if (!out.is_open())
    {
        throw StorageError("INTERNAL_ERROR", "Failed to open catalog file for writing " + catalog_path().string());
    }
    out << catalog.dump(2);
    out.close();
    if (!out)
    {
        throw StorageError("INTERNAL_ERROR", "Failed to write catalog file " + catalog_path().string());
    }
}

void Database::open_row_store() const
{
    if (row_store_)
    {
        return;
    }
    auto store = std::make_unique<RowStore>();
    store->open(row_file_path().string());
    row_store_ = std::move(store);
}
