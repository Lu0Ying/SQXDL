#ifndef STORAGE_CORE_BUFFER_POOL_H
#define STORAGE_CORE_BUFFER_POOL_H

#include <cstddef>
#include <list>
#include <string>
#include <unordered_map>
#include <vector>

#include "disk_manager.h"
#include "lru_replacer.h"
#include "page.h"

// 缓冲池管理器：磁盘页的内存缓存 + LRU 淘汰 + 脏页写回。
//
// 工作原理（数据库分页存储的标准结构）：
// - fetch_page 优先命中缓冲池；未命中则从磁盘读入所需页；
// - 无空闲帧时按 LRU 淘汰「pin 计数为 0」的页，脏页淘汰前写回磁盘；
// - new_page 分配磁盘新页并装入缓冲池；
// - 页使用完毕必须调用 unpin_page 释放占用，否则该页无法被淘汰；
// - 析构时将所有脏页刷盘。
//
// 使用范式：
//   Page *page = bpm.fetch_page(pid);   // 或 new_page
//   ...读写 page->data()...              // 修改后 unpin 时置 dirty
//   bpm.unpin_page(pid, dirty);
//
// 单线程使用（存储核心为 stdin 循环的服务），不做并发控制；
// 同一数据库文件同一时刻仅允许一个实例。
class BufferPoolManager
{
public:
    // pool_size：内存可缓存的页数
    BufferPoolManager(size_t pool_size, const std::string &db_file);
    ~BufferPoolManager();

    BufferPoolManager(const BufferPoolManager &) = delete;
    BufferPoolManager &operator=(const BufferPoolManager &) = delete;

    // 获取页：命中缓冲池直接返回（pin 计数 +1），未命中从磁盘读入；
    // 页号非法（含头页、已删除页）抛 StorageError(INTERNAL_ERROR)，
    // 缓冲池满且无可淘汰页抛 StorageError(INTERNAL_ERROR)
    Page *fetch_page(page_id_t page_id);

    // 新建页：分配磁盘页并装入缓冲池，pin 计数为 1，数据为全零；
    // 缓冲池满且无可淘汰页抛 StorageError(INTERNAL_ERROR)
    Page *new_page(page_id_t *page_id);

    // 释放页的使用（pin 计数 -1）；dirty 表示该帧是否被修改（与已有标记取或）；
    // 返回 false 表示页不在缓冲池或 pin 计数已为 0
    bool unpin_page(page_id_t page_id, bool dirty);

    // 删除页：从缓冲池移除并回收磁盘页；
    // 页正被占用（pin > 0）返回 false，页号非法抛 INTERNAL_ERROR
    bool delete_page(page_id_t page_id);

    // 将缓冲池中所有脏页写回磁盘
    void flush_all();

    size_t pool_size() const;

    // 磁盘总页数（含头页）
    uint32_t disk_page_count() const;

private:
    // 获取可复用帧：优先空闲帧，否则淘汰 LRU 页（脏页先写回）；
    // 返回 nullptr 表示缓冲池满且所有页被占用
    Page *acquire_frame();

    size_t pool_size_;
    std::vector<Page> frames_;
    std::list<size_t> free_frames_;                     // 未绑定页的帧下标
    std::unordered_map<page_id_t, size_t> page_table_;  // 页号 -> 帧下标
    LRUReplacer replacer_;
    DiskManager disk_manager_;
};

#endif
