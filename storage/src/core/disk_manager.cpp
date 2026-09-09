#include "disk_manager.h"

#include <cstring>

#include "storage_error.h"

namespace
{
constexpr char MAGIC[4] = {'S', 'Q', 'X', 'D'}; // 数据库文件魔数
constexpr uint32_t DISK_FORMAT_VERSION = 1;

// 头页中空闲页表容量上限：(4096 - 16) / 4 = 1020 项
constexpr uint32_t MAX_FREE_PAGE_ENTRIES = (PAGE_SIZE - 16) / 4;
} // namespace

DiskManager::DiskManager(const std::string &db_file)
    : db_file_(db_file), page_count_(0)
{
    file_.open(db_file_, std::ios::in | std::ios::out | std::ios::binary);
    if (!file_.is_open())
    {
        // 文件不存在：创建后以读写模式重新打开
        std::fstream create(db_file_, std::ios::out | std::ios::binary);
        create.close();
        file_.open(db_file_, std::ios::in | std::ios::out | std::ios::binary);
    }
    if (!file_.is_open())
    {
        throw StorageError("INTERNAL_ERROR", "无法打开数据库文件: " + db_file_);
    }
    load_header();
}

DiskManager::~DiskManager()
{
    try
    {
        write_header();
    }
    catch (...)
    {
        // 析构中尽力刷盘，失败不抛出
    }
    if (file_.is_open())
    {
        file_.close();
    }
}

void DiskManager::read_page(page_id_t page_id, char *data)
{
    if (!is_valid_page(page_id))
    {
        throw StorageError("INTERNAL_ERROR", "读取非法页号: " + std::to_string(page_id));
    }
    file_.clear();
    file_.seekg(static_cast<std::streamoff>(page_id) * PAGE_SIZE, std::ios::beg);
    file_.read(data, PAGE_SIZE);
    if (file_.gcount() != static_cast<std::streamsize>(PAGE_SIZE))
    {
        throw StorageError("INTERNAL_ERROR",
                           "读取页 " + std::to_string(page_id) + " 失败（文件损坏或被截断）");
    }
}

void DiskManager::write_page(page_id_t page_id, const char *data)
{
    if (!is_valid_page(page_id))
    {
        throw StorageError("INTERNAL_ERROR", "写入非法页号: " + std::to_string(page_id));
    }
    file_.clear();
    file_.seekp(static_cast<std::streamoff>(page_id) * PAGE_SIZE, std::ios::beg);
    file_.write(data, PAGE_SIZE);
    if (!file_)
    {
        throw StorageError("INTERNAL_ERROR", "写入页 " + std::to_string(page_id) + " 失败");
    }
}

page_id_t DiskManager::allocate_page()
{
    page_id_t page_id;
    if (!free_pages_.empty())
    {
        page_id = *free_pages_.begin();
        free_pages_.erase(free_pages_.begin());
    }
    else
    {
        page_id = page_count_;
        page_count_ += 1;
    }
    // 先写数据页（全零）再写头页：若中途崩溃，
    // 多出的零页无害，而已分配页号必然物理存在
    char zeros[PAGE_SIZE];
    std::memset(zeros, 0, sizeof(zeros));
    write_page(page_id, zeros);
    write_header();
    return page_id;
}

void DiskManager::deallocate_page(page_id_t page_id)
{
    if (!is_valid_page(page_id))
    {
        throw StorageError("INTERNAL_ERROR", "回收非法页号: " + std::to_string(page_id));
    }
    if (free_pages_.find(page_id) != free_pages_.end())
    {
        throw StorageError("INTERNAL_ERROR", "页已被回收: " + std::to_string(page_id));
    }
    if (free_pages_.size() >= MAX_FREE_PAGE_ENTRIES)
    {
        throw StorageError("INTERNAL_ERROR", "空闲页表超出头页容量上限");
    }
    free_pages_.insert(page_id);
    write_header();
}

uint32_t DiskManager::page_count() const
{
    return page_count_;
}

bool DiskManager::is_valid_page(page_id_t page_id) const
{
    return page_id >= 1 && page_id < page_count_;
}

bool DiskManager::is_allocated_page(page_id_t page_id) const
{
    return is_valid_page(page_id) && free_pages_.find(page_id) == free_pages_.end();
}

void DiskManager::flush()
{
    write_header();
}

void DiskManager::write_header()
{
    char buffer[PAGE_SIZE];
    std::memset(buffer, 0, sizeof(buffer));
    std::memcpy(buffer + 0, MAGIC, sizeof(MAGIC));
    uint32_t version = DISK_FORMAT_VERSION;
    std::memcpy(buffer + 4, &version, sizeof(version));
    std::memcpy(buffer + 8, &page_count_, sizeof(page_count_));
    const uint32_t free_count = static_cast<uint32_t>(free_pages_.size());
    std::memcpy(buffer + 12, &free_count, sizeof(free_count));
    size_t offset = 16;
    for (page_id_t id : free_pages_)
    {
        std::memcpy(buffer + offset, &id, sizeof(id));
        offset += sizeof(id);
    }
    file_.clear();
    file_.seekp(0, std::ios::beg);
    file_.write(buffer, PAGE_SIZE);
    if (!file_)
    {
        throw StorageError("INTERNAL_ERROR", "写入数据库文件头失败: " + db_file_);
    }
    file_.flush();
}

void DiskManager::load_header()
{
    file_.clear();
    file_.seekg(0, std::ios::end);
    const std::streamoff file_size = file_.tellg();
    if (file_size == 0)
    {
        // 空文件：初始化为仅含头页的新库
        page_count_ = 1;
        free_pages_.clear();
        write_header();
        return;
    }
    if (file_size < static_cast<std::streamoff>(PAGE_SIZE))
    {
        throw StorageError("INTERNAL_ERROR", "数据库文件损坏（小于一页）: " + db_file_);
    }
    char buffer[PAGE_SIZE];
    file_.clear();
    file_.seekg(0, std::ios::beg);
    file_.read(buffer, PAGE_SIZE);
    if (file_.gcount() != static_cast<std::streamsize>(PAGE_SIZE) ||
        std::memcmp(buffer + 0, MAGIC, sizeof(MAGIC)) != 0)
    {
        throw StorageError("INTERNAL_ERROR", "数据库文件格式非法（魔数不匹配）: " + db_file_);
    }
    uint32_t version = 0;
    std::memcpy(&version, buffer + 4, sizeof(version));
    if (version != DISK_FORMAT_VERSION)
    {
        throw StorageError("INTERNAL_ERROR", "数据库文件版本不支持: " + std::to_string(version));
    }
    uint32_t free_count = 0;
    std::memcpy(&page_count_, buffer + 8, sizeof(page_count_));
    std::memcpy(&free_count, buffer + 12, sizeof(free_count));
    if (page_count_ < 1 || free_count > MAX_FREE_PAGE_ENTRIES ||
        free_count >= page_count_)
    {
        throw StorageError("INTERNAL_ERROR", "数据库文件头损坏: " + db_file_);
    }
    free_pages_.clear();
    size_t offset = 16;
    for (uint32_t i = 0; i < free_count; ++i)
    {
        page_id_t id;
        std::memcpy(&id, buffer + offset, sizeof(id));
        offset += sizeof(id);
        if (!is_valid_page(id))
        {
            throw StorageError("INTERNAL_ERROR", "数据库文件头损坏（非法空闲页号）");
        }
        free_pages_.insert(id);
    }
}
