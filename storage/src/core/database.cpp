#include "database.h"

#include <filesystem>
#include <fstream>
#include <set>

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
                                   "无法访问存储目录 " + path.string() + ": " + ec.message());
            }
            return nlohmann::json::object();
        }
        std::ifstream in(path);
        if (!in.is_open())
        {
            throw StorageError("INTERNAL_ERROR", "无法打开目录文件 " + path.string());
        }
        nlohmann::json catalog;
        try
        {
            in >> catalog;
        }
        catch (const std::exception &)
        {
            throw StorageError("INTERNAL_ERROR", "目录文件损坏，无法解析: " + path.string());
        }
        if (!catalog.is_object())
        {
            throw StorageError("INTERNAL_ERROR", "目录文件格式非法: " + path.string());
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
    ensure_loaded();
    return tables_.find(name) != tables_.end();
}

Table &Database::get_table(const std::string &name)
{
    ensure_loaded();
    auto it = tables_.find(name);
    if (it == tables_.end())
    {
        throw StorageError("TABLE_NOT_FOUND", "表 " + name + " 不存在");
    }
    return it->second;
}

const Table &Database::get_table(const std::string &name) const
{
    ensure_loaded();
    auto it = tables_.find(name);
    if (it == tables_.end())
    {
        throw StorageError("TABLE_NOT_FOUND", "表 " + name + " 不存在");
    }
    return it->second;
}

void Database::create_table(const std::string &name, std::vector<Column> columns)
{
    ensure_loaded();
    if (tables_.find(name) != tables_.end())
    {
        throw StorageError("TABLE_ALREADY_EXISTS", "表 " + name + " 已存在");
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
    ensure_loaded();
    auto it = tables_.find(name);
    if (it == tables_.end())
    {
        throw StorageError("TABLE_NOT_FOUND", "表 " + name + " 不存在");
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
    ensure_loaded();
    std::vector<std::string> names;
    names.reserve(tables_.size());
    for (const auto &entry : tables_)
    {
        names.push_back(entry.first);
    }
    return names;
}

void Database::flush()
{
    open_row_store();
    for (const auto &entry : tables_)
    {
        row_store_->save_table(entry.first, entry.second.rows());
    }
    row_store_->flush();
}

void Database::ensure_loaded() const
{
    if (loaded_)
    {
        return;
    }
    nlohmann::json catalog = read_catalog();

    // 兼容历史上行数据曾内嵌于 catalog.json 的格式：剥离并迁移到页文件
    std::set<std::string> migrate_tables;
    for (auto it = catalog.begin(); it != catalog.end(); ++it)
    {
        const std::string name = it.key();
        const nlohmann::json &entry = it.value();
        const nlohmann::json &columns_json =
            entry.is_array() ? entry : entry.value("columns", nlohmann::json());
        if (!columns_json.is_array())
        {
            throw StorageError("INTERNAL_ERROR", "目录文件中表 " + name + " 的列定义非法");
        }
        std::vector<Column> columns;
        try
        {
            columns = columns_from_json(columns_json);
        }
        catch (const StorageError &)
        {
            throw StorageError("INTERNAL_ERROR", "目录文件中表 " + name + " 的列定义损坏");
        }
        Table table(name, std::move(columns));
        if (!entry.is_array() && entry.contains("rows") && entry.at("rows").is_array() &&
            !entry.at("rows").empty())
        {
            for (const auto &row_json : entry.at("rows"))
            {
                try
                {
                    table.append_row(Row::from_json(row_json));
                }
                catch (const StorageError &)
                {
                    throw StorageError("INTERNAL_ERROR", "目录文件中表 " + name + " 的行数据损坏");
                }
            }
            migrate_tables.insert(name);
        }
        tables_.emplace(name, std::move(table));
    }

    // 从页文件恢复行数据；内嵌于旧 catalog 的行先迁移写页，再瘦身 catalog
    open_row_store();
    for (const auto &entry : tables_)
    {
        if (migrate_tables.find(entry.first) != migrate_tables.end())
        {
            continue; // 旧格式内嵌行已就位，稍后整体写页
        }
        std::vector<Row> rows = row_store_->load_table(entry.first);
        Table &table = tables_[entry.first];
        for (auto &row : rows)
        {
            table.append_row(std::move(row));
        }
    }
    if (!migrate_tables.empty())
    {
        for (const auto &name : migrate_tables)
        {
            row_store_->save_table(name, tables_[name].rows());
        }
        row_store_->flush();
        save_catalog(); // 重写为仅含表结构的 catalog
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
                           "无法创建存储目录 " + data_dir().string() + ": " + ec.message());
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
        throw StorageError("INTERNAL_ERROR", "无法写入目录文件 " + catalog_path().string());
    }
    out << catalog.dump(2);
    out.close();
    if (!out)
    {
        throw StorageError("INTERNAL_ERROR", "写入目录文件失败 " + catalog_path().string());
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
