#include "buffer_pool.h"

#include "storage_error.h"

BufferPoolManager::BufferPoolManager(size_t pool_size, const std::string &db_file)
    : pool_size_(pool_size), frames_(pool_size), disk_manager_(db_file)
{
    if (pool_size == 0)
    {
        throw StorageError("INTERNAL_ERROR", "Buffer pool size must be greater than 0");
    }
    for (size_t i = 0; i < pool_size_; ++i)
    {
        free_frames_.push_back(i);
    }
}

BufferPoolManager::~BufferPoolManager()
{
    try
    {
        flush_all();
    }
    catch (...)
    {
        // 析构中尽力刷盘，失败不抛出
    }
}

Page *BufferPoolManager::fetch_page(page_id_t page_id)
{
    // 命中：pin 计数 +1 并移出可淘汰集合
    auto it = page_table_.find(page_id);
    if (it != page_table_.end())
    {
        Page &page = frames_[it->second];
        page.pin_count_ += 1;
        replacer_.mark_pinned(page_id);
        return &page;
    }
    if (!disk_manager_.is_allocated_page(page_id))
    {
        throw StorageError("INTERNAL_ERROR", "Page " + std::to_string(page_id) + " does not exist or has been deleted");
    }
    Page *frame = acquire_frame();
    if (frame == nullptr)
    {
        throw StorageError("INTERNAL_ERROR",
                           "Buffer pool is full and all pages are pinned, cannot fetch page " +
                               std::to_string(page_id));
    }
    frame->reset(page_id);
    disk_manager_.read_page(page_id, frame->data());
    frame->pin_count_ = 1;
    page_table_[page_id] = static_cast<size_t>(frame - frames_.data());
    return frame;
}

Page *BufferPoolManager::new_page(page_id_t *page_id)
{
    // 先分配磁盘页：即使随后取帧失败，归还磁盘页即可，无副作用
    const page_id_t id = disk_manager_.allocate_page();
    Page *frame = acquire_frame();
    if (frame == nullptr)
    {
        disk_manager_.deallocate_page(id);
        throw StorageError("INTERNAL_ERROR", "Buffer pool is full and all pages are pinned, cannot allocate a new page");
    }
    frame->reset(id);
    frame->pin_count_ = 1;
    page_table_[id] = static_cast<size_t>(frame - frames_.data());
    *page_id = id;
    return frame;
}

bool BufferPoolManager::unpin_page(page_id_t page_id, bool dirty)
{
    auto it = page_table_.find(page_id);
    if (it == page_table_.end())
    {
        return false;
    }
    Page &page = frames_[it->second];
    if (page.pin_count_ <= 0)
    {
        return false;
    }
    page.pin_count_ -= 1;
    if (page.pin_count_ == 0)
    {
        replacer_.mark_evictable(page_id);
    }
    if (dirty)
    {
        page.dirty_ = true;
    }
    return true;
}

bool BufferPoolManager::delete_page(page_id_t page_id)
{
    auto it = page_table_.find(page_id);
    if (it != page_table_.end())
    {
        Page &page = frames_[it->second];
        if (page.pin_count_ > 0)
        {
            return false; // 正被使用，无法删除
        }
        replacer_.mark_pinned(page_id); // 从可淘汰集合移除
        const size_t frame_index = static_cast<size_t>(&page - frames_.data());
        page_table_.erase(it);
        page.reset(INVALID_PAGE_ID);
        free_frames_.push_back(frame_index);
    }
    disk_manager_.deallocate_page(page_id);
    return true;
}

void BufferPoolManager::flush_all()
{
    for (auto &entry : page_table_)
    {
        Page &page = frames_[entry.second];
        if (page.dirty_)
        {
            disk_manager_.write_page(entry.first, page.data());
            page.dirty_ = false;
        }
    }
    disk_manager_.flush();
}

size_t BufferPoolManager::pool_size() const
{
    return pool_size_;
}

uint32_t BufferPoolManager::disk_page_count() const
{
    return disk_manager_.page_count();
}

bool BufferPoolManager::is_allocated_page(page_id_t page_id) const
{
    return disk_manager_.is_allocated_page(page_id);
}

Page *BufferPoolManager::acquire_frame()
{
    if (!free_frames_.empty())
    {
        const size_t index = free_frames_.front();
        free_frames_.pop_front();
        return &frames_[index];
    }
    // 无空闲帧：按 LRU 淘汰
    page_id_t victim = INVALID_PAGE_ID;
    if (!replacer_.evict(&victim))
    {
        return nullptr;
    }
    auto it = page_table_.find(victim);
    if (it == page_table_.end())
    {
        throw StorageError("INTERNAL_ERROR", "Buffer pool internal state inconsistent (evicted page not in page table)");
    }
    const size_t index = it->second;
    page_table_.erase(it);
    Page &page = frames_[index];
    if (page.dirty_)
    {
        disk_manager_.write_page(victim, page.data());
    }
    return &page;
}
