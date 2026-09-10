#include "row_store.h"

#include <algorithm>
#include <cstring>
#include <filesystem>

#include "buffer_pool.h"
#include "nlohmann/json.hpp"
#include "storage_error.h"

namespace
{
// 页头占 8 字节（下一页页号 + 块长），块数据区容量
constexpr size_t PAGE_PAYLOAD_CAPACITY = PAGE_SIZE - 8;
constexpr size_t POOL_SIZE = 64; // 缓冲池页数
} // namespace

RowStore::RowStore() = default;

RowStore::~RowStore() = default;

void RowStore::open(const std::string &db_file)
{
    // 确保数据目录存在（DiskManager 无法自动创建目录）
    const std::filesystem::path dir = std::filesystem::path(db_file).parent_path();
    if (!dir.empty())
    {
        std::error_code ec;
        std::filesystem::create_directories(dir, ec);
        if (ec)
        {
            throw StorageError("INTERNAL_ERROR",
                               "无法创建数据目录 " + dir.string() + ": " + ec.message());
        }
    }

    pool_ = std::make_unique<BufferPoolManager>(POOL_SIZE, db_file);
    if (pool_->disk_page_count() == 1)
    {
        // 全新文件：分配 1 号页作目录根页（无空闲页时即文件第 1 个数据页），
        // 并立即写入空目录，保证后续总能从 1 号页恢复
        pool_->new_page(&root_page_);
        dir_pages_ = {root_page_};
        write_directory();
        return;
    }

    // 已有文件：目录链首固定为 1 号页（本类创建文件时即预留，从不回收）
    root_page_ = 1;
    load_directory();
}

void RowStore::save_table(const std::string &table_name, const std::vector<Row> &rows)
{
    const std::vector<page_id_t> old_pages = dir_[table_name];
    if (rows.empty())
    {
        dir_[table_name] = write_blob("", old_pages); // 空表：回收全部旧页
        return;
    }
    nlohmann::json row_array = nlohmann::json::array();
    for (const auto &row : rows)
    {
        row_array.push_back(row.to_json());
    }
    const std::string text = row_array.dump();
    dir_[table_name] = write_blob(text, old_pages);
}

std::vector<Row> RowStore::load_table(const std::string &table_name)
{
    auto it = dir_.find(table_name);
    if (it == dir_.end() || it->second.empty())
    {
        return {};
    }
    const std::string text = read_blob(it->second);
    nlohmann::json row_array;
    try
    {
        row_array = nlohmann::json::parse(text);
    }
    catch (const std::exception &)
    {
        throw StorageError("INTERNAL_ERROR", "表 " + table_name + " 的行数据损坏（无法解析）");
    }
    if (!row_array.is_array())
    {
        throw StorageError("INTERNAL_ERROR", "表 " + table_name + " 的行数据格式非法");
    }
    std::vector<Row> rows;
    rows.reserve(row_array.size());
    for (const auto &element : row_array)
    {
        try
        {
            rows.push_back(Row::from_json(element));
        }
        catch (const StorageError &)
        {
            throw StorageError("INTERNAL_ERROR", "表 " + table_name + " 的行数据损坏");
        }
    }
    return rows;
}

void RowStore::remove_table(const std::string &table_name)
{
    auto it = dir_.find(table_name);
    if (it == dir_.end())
    {
        return;
    }
    for (const page_id_t page_id : it->second)
    {
        if (!pool_->delete_page(page_id))
        {
            throw StorageError("INTERNAL_ERROR",
                               "回收表 " + table_name + " 的页失败: " + std::to_string(page_id));
        }
    }
    dir_.erase(it);
}

void RowStore::flush()
{
    write_directory();
}

std::vector<page_id_t> RowStore::read_chain(page_id_t start)
{
    std::vector<page_id_t> chain;
    page_id_t current = start;
    while (true)
    {
        if (std::find(chain.begin(), chain.end(), current) != chain.end())
        {
            throw StorageError("INTERNAL_ERROR", "数据文件页链成环: " + std::to_string(current));
        }
        Page *page = pool_->fetch_page(current);
        page_id_t next = INVALID_PAGE_ID;
        std::memcpy(&next, page->data(), sizeof(next));
        pool_->unpin_page(current, false);
        chain.push_back(current);
        if (next == 0 || next == INVALID_PAGE_ID)
        {
            break; // 0 / 无效页号均视为链尾（0 号页为文件头，永不作为数据页）
        }
        current = next;
    }
    return chain;
}

std::vector<page_id_t> RowStore::write_blob(const std::string &text,
                                            const std::vector<page_id_t> &old_pages)
{
    const size_t page_count = (text.size() + PAGE_PAYLOAD_CAPACITY - 1) / PAGE_PAYLOAD_CAPACITY;

    // 回收超出新长度的旧页
    for (size_t i = page_count; i < old_pages.size(); ++i)
    {
        if (!pool_->delete_page(old_pages[i]))
        {
            throw StorageError("INTERNAL_ERROR",
                               "回收数据页失败: " + std::to_string(old_pages[i]));
        }
    }

    std::vector<page_id_t> pages(old_pages.begin(),
                                 old_pages.begin() + std::min(page_count, old_pages.size()));
    while (pages.size() < page_count)
    {
        page_id_t page_id;
        pool_->new_page(&page_id);
        pages.push_back(page_id);
    }

    for (size_t i = 0; i < pages.size(); ++i)
    {
        Page *page = pool_->fetch_page(pages[i]);
        char *data = page->data();
        const size_t offset = i * PAGE_PAYLOAD_CAPACITY;
        const size_t chunk_length = std::min(PAGE_PAYLOAD_CAPACITY, text.size() - offset);
        const page_id_t next = (i + 1 < pages.size()) ? pages[i + 1] : INVALID_PAGE_ID;
        const uint32_t chunk_len_u32 = static_cast<uint32_t>(chunk_length);
        std::memcpy(data, &next, sizeof(next));
        std::memcpy(data + 4, &chunk_len_u32, sizeof(chunk_len_u32));
        if (chunk_length > 0)
        {
            std::memcpy(data + 8, text.data() + offset, chunk_length);
        }
        pool_->unpin_page(pages[i], true);
    }
    return pages;
}

std::string RowStore::read_blob(const std::vector<page_id_t> &pages)
{
    std::string text;
    text.reserve(pages.size() * PAGE_PAYLOAD_CAPACITY);
    for (size_t i = 0; i < pages.size(); ++i)
    {
        Page *page = pool_->fetch_page(pages[i]);
        const char *data = page->data();
        page_id_t next = INVALID_PAGE_ID;
        uint32_t chunk_length = 0;
        std::memcpy(&next, data, sizeof(next));
        std::memcpy(&chunk_length, data + 4, sizeof(chunk_length));
        const page_id_t expected_next =
            (i + 1 < pages.size()) ? pages[i + 1] : INVALID_PAGE_ID;
        if (next != expected_next || chunk_length > PAGE_PAYLOAD_CAPACITY)
        {
            pool_->unpin_page(pages[i], false);
            throw StorageError("INTERNAL_ERROR", "数据文件页链损坏");
        }
        text.append(data + 8, chunk_length);
        pool_->unpin_page(pages[i], false);
    }
    return text;
}

void RowStore::load_directory()
{
    dir_pages_ = read_chain(root_page_);
    const std::string text = read_blob(dir_pages_);
    dir_.clear();
    if (text.empty())
    {
        return;
    }
    nlohmann::json directory;
    try
    {
        directory = nlohmann::json::parse(text);
    }
    catch (const std::exception &)
    {
        throw StorageError("INTERNAL_ERROR", "数据文件目录损坏（无法解析）");
    }
    if (!directory.is_object())
    {
        throw StorageError("INTERNAL_ERROR", "数据文件目录格式非法");
    }
    for (auto it = directory.begin(); it != directory.end(); ++it)
    {
        const nlohmann::json &pages_json = it.value();
        if (!pages_json.is_array())
        {
            throw StorageError("INTERNAL_ERROR", "数据文件目录中表 " + it.key() + " 的页链非法");
        }
        std::vector<page_id_t> pages;
        pages.reserve(pages_json.size());
        for (const auto &page_json : pages_json)
        {
            if (!page_json.is_number_unsigned())
            {
                throw StorageError("INTERNAL_ERROR", "数据文件目录中表 " + it.key() + " 的页链损坏");
            }
            pages.push_back(page_json.get<page_id_t>());
        }
        dir_[it.key()] = std::move(pages);
    }
}

void RowStore::write_directory()
{
    nlohmann::json directory = nlohmann::json::object();
    for (const auto &entry : dir_)
    {
        directory[entry.first] = entry.second;
    }
    dir_pages_ = write_blob(directory.dump(), dir_pages_);
}
