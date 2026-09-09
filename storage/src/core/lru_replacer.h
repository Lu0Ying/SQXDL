#ifndef STORAGE_CORE_LRU_REPLACER_H
#define STORAGE_CORE_LRU_REPLACER_H

#include <cstddef>
#include <list>
#include <unordered_map>

#include "page.h"

// LRU 页淘汰策略：跟踪缓冲池中「可被淘汰」的页（pin 计数为 0 的页），
// 淘汰最近最少释放的页。仅由 BufferPoolManager 使用。
class LRUReplacer
{
public:
    // 将页标记为可淘汰（其 pin 计数降为 0 时调用）；
    // 若已在候选集合中，则刷新至最近使用端
    void mark_evictable(page_id_t page_id);

    // 将页标记为不可淘汰（页被再次获取、pin 计数 > 0 时调用）；
    // 对不在集合中的页调用无副作用
    void mark_pinned(page_id_t page_id);

    // 淘汰最近最少使用的页并写入 *page_id；无候选页时返回 false
    bool evict(page_id_t *page_id);

    // 当前可淘汰页数量
    size_t size() const;

private:
    std::list<page_id_t> lru_list_; // 队首 = 最久未用，队尾 = 最近使用
    std::unordered_map<page_id_t, std::list<page_id_t>::iterator> positions_;
};

#endif
