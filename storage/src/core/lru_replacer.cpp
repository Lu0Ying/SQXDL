#include "lru_replacer.h"

void LRUReplacer::mark_evictable(page_id_t page_id)
{
    auto it = positions_.find(page_id);
    if (it != positions_.end())
    {
        lru_list_.erase(it->second); // 已在集合中：先移出旧位置，稍后刷新至最近端
    }
    lru_list_.push_back(page_id);
    positions_[page_id] = std::prev(lru_list_.end());
}

void LRUReplacer::mark_pinned(page_id_t page_id)
{
    auto it = positions_.find(page_id);
    if (it != positions_.end())
    {
        lru_list_.erase(it->second);
        positions_.erase(it);
    }
}

bool LRUReplacer::evict(page_id_t *page_id)
{
    if (lru_list_.empty())
    {
        return false;
    }
    *page_id = lru_list_.front();
    lru_list_.pop_front();
    positions_.erase(*page_id);
    return true;
}

size_t LRUReplacer::size() const
{
    return lru_list_.size();
}
