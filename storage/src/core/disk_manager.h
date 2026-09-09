#ifndef STORAGE_CORE_DISK_MANAGER_H
#define STORAGE_CORE_DISK_MANAGER_H

#include <cstdint>
#include <fstream>
#include <set>
#include <string>

#include "page.h"

// 磁盘管理器：以页为单位读写数据库文件。
//
// 文件按页对齐组织：页号即偏移量（页号 * PAGE_SIZE），
// 第 0 页固定为头页（魔数、版本、总页数、空闲页表），数据页从 1 开始编号。
// 分配优先复用空闲页表中的页，否则在文件尾追加；回收页进入空闲页表。
// 头页在每次分配/回收后立即持久化。
//
// 注意：
// - 单线程使用，不做并发控制；
// - 同一数据库文件同一时刻仅允许一个实例；
// - 空闲页表存储在头页中，容量上限 1020 项（超出抛 INTERNAL_ERROR）。
class DiskManager
{
public:
    // 打开（不存在则创建并初始化）数据库文件
    explicit DiskManager(const std::string &db_file);
    ~DiskManager();

    DiskManager(const DiskManager &) = delete;
    DiskManager &operator=(const DiskManager &) = delete;

    // 读取整页到 data（须指向 PAGE_SIZE 字节的缓冲区）；
    // 页号非法或读失败抛 StorageError(INTERNAL_ERROR)
    void read_page(page_id_t page_id, char *data);

    // 将整页写回磁盘对应页号位置
    void write_page(page_id_t page_id, const char *data);

    // 分配新页：优先复用空闲页，否则追加；
    // 分配后磁盘上即为全零页，并持久化头页
    page_id_t allocate_page();

    // 回收页加入空闲页表；
    // 页号非法或重复回收抛 StorageError(INTERNAL_ERROR)
    void deallocate_page(page_id_t page_id);

    // 文件当前总页数（含头页），即有效页号上界（页号 < page_count）
    uint32_t page_count() const;

    // 页号在文件范围内（含空闲页）
    bool is_valid_page(page_id_t page_id) const;

    // 页号有效且当前已分配（未回收）
    bool is_allocated_page(page_id_t page_id) const;

    // 持久化头页与文件流
    void flush();

private:
    // 头页布局（偏移）：
    // [0,4) 魔数  [4,8) 版本  [8,12) 总页数  [12,16) 空闲页数
    // [16, 16+4*n) 空闲页号列表（每项 4 字节）
    void write_header();
    void load_header();

    std::string db_file_;
    std::fstream file_;
    uint32_t page_count_;            // 总页数（含头页），下一新页号即 page_count_
    std::set<page_id_t> free_pages_; // 已回收、待复用的空闲页
};

#endif
