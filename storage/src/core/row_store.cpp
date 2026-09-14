#include "row_store.h"

#include <algorithm>
#include <filesystem>

#include "buffer_pool.h"
#include "nlohmann/json.hpp"
#include "storage_error.h"

namespace
{
    // 缓冲池页数 n：内存中最多驻留「最近使用的 n 页」，缺页时按 LRU 淘汰脏页写回
    constexpr size_t POOL_SIZE = 64;

    // 目录字节块页的载荷容量（页头 8 字节：下一页页号 + 块长）
    constexpr size_t BLOB_PAYLOAD_CAPACITY = PAGE_SIZE - 8;

    // 行 <-> 元组文本（行在页内以 JSON 文本形式存放）
    std::string encode_row(const Row &row)
    {
        return row.to_json().dump();
    }

    Row decode_row(const std::string &text)
    {
        nlohmann::json value;
        try
        {
            value = nlohmann::json::parse(text);
        }
        catch (const std::exception &)
        {
            throw StorageError("INTERNAL_ERROR", "Row data is corrupted (parse failed)");
        }
        try
        {
            return Row::from_json(value);
        }
        catch (const StorageError &)
        {
            throw StorageError("INTERNAL_ERROR", "Row data is corrupted (invalid format)");
        }
    }

    // 在页内追加一个元组（不复用空槽）；空间不足或槽位用尽返回 false
    bool page_append_tuple(char *data, const std::string &tuple)
    {
        const size_t count = heap_page::slot_count(data);
        if (count >= heap_page::MAX_SLOT_COUNT)
        {
            return false;
        }
        if (tuple.size() + heap_page::SLOT_SIZE > heap_page::free_space(data))
        {
            return false;
        }
        const size_t offset = static_cast<size_t>(heap_page::free_area_start(data)) - tuple.size();
        std::memcpy(data + offset, tuple.data(), tuple.size());
        heap_page::set_free_area_start(data, offset);
        heap_page::set_slot(data, count, offset, tuple.size());
        heap_page::set_slot_count(data, count + 1);
        return true;
    }

    // 重建页内元组区，回收空槽与碎片（槽位下标保持不变，行号仍然有效）
    void page_compact(char *data)
    {
        const size_t count = heap_page::slot_count(data);
        std::vector<std::pair<size_t, std::string>> live;
        for (size_t slot = 0; slot < count; ++slot)
        {
            const size_t length = heap_page::slot_length(data, slot);
            if (length != 0)
            {
                live.emplace_back(slot, std::string(data + heap_page::slot_offset(data, slot), length));
            }
        }
        std::memset(data + heap_page::HEADER_SIZE, 0, PAGE_SIZE - heap_page::HEADER_SIZE);
        heap_page::set_free_area_start(data, PAGE_SIZE);
        for (const auto &entry : live)
        {
            const size_t offset = static_cast<size_t>(heap_page::free_area_start(data)) - entry.second.size();
            std::memcpy(data + offset, entry.second.data(), entry.second.size());
            heap_page::set_free_area_start(data, offset);
            heap_page::set_slot(data, entry.first, offset, entry.second.size());
        }
    }
} // namespace

RowIterator::RowIterator(BufferPoolManager *pool, std::vector<page_id_t> pages, bool reverse)
    : pool_(pool), pages_(std::move(pages)), reverse_(reverse)
{
}

bool RowIterator::next(Row &out)
{
    while (true)
    {
        if (buffer_pos_ < buffer_.size())
        {
            const std::pair<RowId, Row> &entry = buffer_[buffer_pos_];
            ++buffer_pos_;
            out = entry.second;
            last_rid_ = entry.first;
            return true;
        }
        if (!load_next_page())
        {
            return false;
        }
    }
}

const RowId &RowIterator::last_rid() const
{
    return last_rid_;
}

bool RowIterator::load_next_page()
{
    while (pages_consumed_ < pages_.size())
    {
        const size_t index = reverse_ ? pages_.size() - 1 - pages_consumed_ : pages_consumed_;
        ++pages_consumed_;
        const page_id_t page_id = pages_[index];

        Page *page = pool_->fetch_page(page_id);
        const char *data = page->data();
        buffer_.clear();
        buffer_pos_ = 0;
        const uint16_t count = heap_page::slot_count(data);
        if (heap_page::directory_end(data) > PAGE_SIZE)
        {
            pool_->unpin_page(page_id, false);
            throw StorageError("INTERNAL_ERROR", "Data page is corrupted (invalid slot directory)");
        }
        for (uint16_t slot = 0; slot < count; ++slot)
        {
            const size_t length = heap_page::slot_length(data, slot);
            if (length == 0)
            {
                continue; // 空槽（已删除的行）
            }
            const size_t offset = heap_page::slot_offset(data, slot);
            if (offset < heap_page::directory_end(data) || offset + length > PAGE_SIZE)
            {
                pool_->unpin_page(page_id, false);
                throw StorageError("INTERNAL_ERROR", "Data page is corrupted (invalid slot)");
            }
            RowId rid;
            rid.page_id = page_id;
            rid.slot = slot;
            buffer_.emplace_back(rid, decode_row(std::string(data + offset, length)));
        }
        pool_->unpin_page(page_id, false);
        if (!buffer_.empty())
        {
            return true;
        }
        // 整页无活行：继续下一页
    }
    return false;
}

RowStore::RowStore() = default;

RowStore::~RowStore()
{
    try
    {
        if (dir_dirty_)
        {
            write_directory();
        }
    }
    catch (...)
    {
        // 析构中尽力写回目录，失败不抛出
    }
}

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
                               "Failed to create data directory " + dir.string() + ": " + ec.message());
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

void RowStore::insert_row(const std::string &table_name, const Row &row)
{
    const std::string tuple = encode_row(row);
    if (tuple.size() > heap_page::MAX_TUPLE_SIZE)
    {
        throw StorageError("INTERNAL_ERROR",
                           "Row is too large to fit in one page (max " +
                               std::to_string(heap_page::MAX_TUPLE_SIZE) + " bytes)");
    }
    append_tuple_at_tail(table_name, tuple);
}

bool RowStore::update_row(const std::string &table_name, const RowId &rid, const Row &row)
{
    const std::string tuple = encode_row(row);
    if (tuple.size() > heap_page::MAX_TUPLE_SIZE)
    {
        throw StorageError("INTERNAL_ERROR",
                           "Row is too large to fit in one page (max " +
                               std::to_string(heap_page::MAX_TUPLE_SIZE) + " bytes)");
    }
    TableState &state = table_state(table_name);
    if (std::find(state.pages.begin(), state.pages.end(), rid.page_id) == state.pages.end())
    {
        return false;
    }

    Page *page = pool_->fetch_page(rid.page_id);
    char *data = page->data();
    if (rid.slot >= heap_page::slot_count(data) || heap_page::slot_length(data, rid.slot) == 0)
    {
        pool_->unpin_page(rid.page_id, false);
        return false; // 该行已被删除
    }

    const size_t old_offset = heap_page::slot_offset(data, rid.slot);
    const size_t old_length = heap_page::slot_length(data, rid.slot);
    if (tuple.size() <= old_length)
    {
        // 原槽位空间足够：就地覆盖，不额外消耗页内空闲空间
        std::memcpy(data + old_offset, tuple.data(), tuple.size());
        heap_page::set_slot(data, rid.slot, old_offset, tuple.size());
        pool_->unpin_page(rid.page_id, true);
        return true;
    }
    if (heap_page::free_space(data) < tuple.size())
    {
        page_compact(data); // 回收空槽与碎片后重试
    }
    if (heap_page::free_space(data) >= tuple.size())
    {
        // 落到本页页尾空闲区（旧槽位空间作废，等待后续压缩回收）
        const size_t offset = static_cast<size_t>(heap_page::free_area_start(data)) - tuple.size();
        std::memcpy(data + offset, tuple.data(), tuple.size());
        heap_page::set_free_area_start(data, offset);
        heap_page::set_slot(data, rid.slot, offset, tuple.size());
        pool_->unpin_page(rid.page_id, true);
        return true;
    }

    // 本页放不下：删除旧槽位，把新元组追加到链尾。
    // 追加目标只可能是「已访问过的链尾页」或新分配的页（新页不在迭代快照内），
    // 因此 UPDATE 采用逆序扫描时，同一行不会被产出两次。
    pool_->unpin_page(rid.page_id, true);
    delete_row(table_name, rid);
    append_tuple_at_tail(table_name, tuple);
    return true;
}

bool RowStore::delete_row(const std::string &table_name, const RowId &rid)
{
    TableState &state = table_state(table_name);
    if (std::find(state.pages.begin(), state.pages.end(), rid.page_id) == state.pages.end())
    {
        return false;
    }

    Page *page = pool_->fetch_page(rid.page_id);
    char *data = page->data();
    if (rid.slot >= heap_page::slot_count(data) || heap_page::slot_length(data, rid.slot) == 0)
    {
        pool_->unpin_page(rid.page_id, false);
        return false; // 该行已被删除
    }

    heap_page::set_slot(data, rid.slot, 0, 0); // 置空槽位（墓碑）
    bool empty = true;
    const uint16_t count = heap_page::slot_count(data);
    for (uint16_t slot = 0; slot < count; ++slot)
    {
        if (heap_page::slot_length(data, slot) != 0)
        {
            empty = false;
            break;
        }
    }
    pool_->unpin_page(rid.page_id, true);

    if (empty)
    {
        drop_page(table_name, state, rid.page_id); // 整页已空：回收该页
    }
    return true;
}

void RowStore::remove_table(const std::string &table_name)
{
    TableState &state = table_state(table_name);
    for (const page_id_t page_id : state.pages)
    {
        if (!pool_->delete_page(page_id))
        {
            throw StorageError("INTERNAL_ERROR",
                               "Failed to delete page " + std::to_string(page_id) +
                                   " of table " + table_name);
        }
    }
    state.pages.clear();
    state.head = 0;
    if (dir_.erase(table_name) > 0)
    {
        dir_dirty_ = true;
    }
}

std::unique_ptr<RowIterator> RowStore::scan(const std::string &table_name, bool reverse)
{
    const TableState &state = table_state(table_name);
    return std::make_unique<RowIterator>(pool_.get(), state.pages, reverse);
}

void RowStore::flush()
{
    if (dir_dirty_)
    {
        write_directory(); // 只有目录真正变化时才写盘，行数据页由缓冲池惰性写回
    }
}

RowStore::TableState &RowStore::table_state(const std::string &table_name)
{
    auto it = states_.find(table_name);
    if (it != states_.end())
    {
        return it->second;
    }

    TableState state;
    const auto entry = dir_.find(table_name);
    if (entry != dir_.end() && entry->second != 0)
    {
        state.head = entry->second;
        page_id_t current = state.head;
        while (current != 0 && current != INVALID_PAGE_ID)
        {
            if (std::find(state.pages.begin(), state.pages.end(), current) != state.pages.end())
            {
                throw StorageError("INTERNAL_ERROR", "Data file page chain contains a cycle");
            }
            state.pages.push_back(current);
            Page *page = pool_->fetch_page(current);
            const page_id_t next = heap_page::next_page(page->data());
            pool_->unpin_page(current, false);
            current = next;
        }
    }
    return states_.emplace(table_name, std::move(state)).first->second;
}

page_id_t RowStore::append_new_page(TableState &state)
{
    page_id_t page_id = INVALID_PAGE_ID;
    Page *page = pool_->new_page(&page_id);
    heap_page::init(page->data());
    pool_->unpin_page(page_id, true);

    if (state.pages.empty())
    {
        state.head = page_id;
    }
    else
    {
        // 旧链尾指向新页
        const page_id_t tail = state.pages.back();
        Page *tail_page = pool_->fetch_page(tail);
        heap_page::set_next_page(tail_page->data(), page_id);
        pool_->unpin_page(tail, true);
    }
    state.pages.push_back(page_id);
    return page_id;
}

void RowStore::drop_page(const std::string &table_name, TableState &state, page_id_t page_id)
{
    const auto it = std::find(state.pages.begin(), state.pages.end(), page_id);
    if (it == state.pages.end())
    {
        return;
    }
    const size_t index = static_cast<size_t>(it - state.pages.begin());

    Page *page = pool_->fetch_page(page_id);
    const page_id_t next = heap_page::next_page(page->data());
    pool_->unpin_page(page_id, false);

    if (index > 0)
    {
        const page_id_t prev = state.pages[index - 1];
        Page *prev_page = pool_->fetch_page(prev);
        heap_page::set_next_page(prev_page->data(), next); // 前驱跳过该页
        pool_->unpin_page(prev, true);
    }
    else
    {
        state.head = next;
    }
    state.pages.erase(it);

    if (!pool_->delete_page(page_id))
    {
        throw StorageError("INTERNAL_ERROR", "Failed to delete data page " + std::to_string(page_id));
    }

    if (state.pages.empty())
    {
        state.head = 0;
        if (dir_.erase(table_name) > 0)
        {
            dir_dirty_ = true; // 表已无数据页：目录中不再保留任何页号
        }
    }
    else if (index == 0)
    {
        dir_[table_name] = state.head; // 原首页被回收：目录改指新首页
        dir_dirty_ = true;
    }
}

void RowStore::append_tuple_at_tail(const std::string &table_name, const std::string &tuple)
{
    TableState &state = table_state(table_name);
    const bool new_table = state.pages.empty();

    page_id_t page_id = new_table ? append_new_page(state) : state.pages.back();
    Page *page = pool_->fetch_page(page_id);
    const bool inserted = page_append_tuple(page->data(), tuple);
    pool_->unpin_page(page_id, inserted);
    if (!inserted)
    {
        // 链尾页已满：新建一页承接（空页必然放得下 MAX_TUPLE_SIZE 的元组）
        page_id = append_new_page(state);
        page = pool_->fetch_page(page_id);
        if (!page_append_tuple(page->data(), tuple))
        {
            pool_->unpin_page(page_id, false);
            throw StorageError("INTERNAL_ERROR", "Failed to append row to a new data page");
        }
        pool_->unpin_page(page_id, true);
    }

    if (new_table)
    {
        dir_[table_name] = state.head; // 表首次落盘：登记数据页链首页
        dir_dirty_ = true;
    }
}

std::vector<page_id_t> RowStore::read_chain(page_id_t start)
{
    std::vector<page_id_t> chain;
    page_id_t current = start;
    while (true)
    {
        if (std::find(chain.begin(), chain.end(), current) != chain.end())
        {
            throw StorageError("INTERNAL_ERROR", "Data file page chain contains a cycle: " + std::to_string(current));
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
    const size_t page_count = (text.size() + BLOB_PAYLOAD_CAPACITY - 1) / BLOB_PAYLOAD_CAPACITY;

    // 回收超出新长度的旧页
    for (size_t i = page_count; i < old_pages.size(); ++i)
    {
        if (!pool_->delete_page(old_pages[i]))
        {
            throw StorageError("INTERNAL_ERROR",
                               "Failed to delete data page: " + std::to_string(old_pages[i]));
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
        const size_t offset = i * BLOB_PAYLOAD_CAPACITY;
        const size_t chunk_length = std::min(BLOB_PAYLOAD_CAPACITY, text.size() - offset);
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
    text.reserve(pages.size() * BLOB_PAYLOAD_CAPACITY);
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
        if (next != expected_next || chunk_length > BLOB_PAYLOAD_CAPACITY)
        {
            pool_->unpin_page(pages[i], false);
            throw StorageError("INTERNAL_ERROR", "Data file page chain is corrupted");
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
    states_.clear();
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
        throw StorageError("INTERNAL_ERROR", "Data file directory is corrupted (parse failed)");
    }
    if (!directory.is_object())
    {
        throw StorageError("INTERNAL_ERROR", "Data file directory has invalid format");
    }
    for (auto it = directory.begin(); it != directory.end(); ++it)
    {
        if (!it.value().is_number_unsigned())
        {
            // 旧版格式：目录存的是「表 -> 页号数组」，行数据按整表字节块切页，与本版不兼容
            throw StorageError("INTERNAL_ERROR",
                               "Unsupported data format for table " + it.key() +
                                   " (legacy row layout, please rebuild the database)");
        }
        const page_id_t head = it.value().get<page_id_t>();
        if (head != 0 && !pool_->is_allocated_page(head))
        {
            throw StorageError("INTERNAL_ERROR", "Data file directory is corrupted (invalid page id)");
        }
        dir_[it.key()] = head;
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
    dir_dirty_ = false;
}
