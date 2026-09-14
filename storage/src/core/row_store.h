#ifndef STORAGE_CORE_ROW_STORE_H
#define STORAGE_CORE_ROW_STORE_H

#include <cstddef>
#include <cstdint>
#include <cstring>
#include <map>
#include <memory>
#include <string>
#include <utility>
#include <vector>

#include "page.h"
#include "row.h"

// 行数据存储（页对齐的 slotted 堆页文件）：行数据与表结构（catalog.json）分离，
// 存放于独立数据库文件。
//
// 文件组织：
// - 0 号页为文件头（DiskManager 管理）；1 号页固定为目录根页（文件创建时预留、
//   永不被回收），目录内容为 JSON {表名: 表首页页号}，本身以「页链 + 字节块」存放
//   （见 write_blob / read_blob），仅在建表首行、删表等极少数时刻重写；
// - 每张表的数据页组成一条「页链」，页与页之间按页号串联（页头第一个字段）；
// - 每个数据页为 slotted 堆页：行元组（该行的 JSON 文本）按 4KB 页边界对齐存放，
//   页内用槽位目录（slot directory）定位每个元组。因此单行插入/更新/删除只会
//   改写 1~2 个页，不会触碰整张表，避免了「整表重写」的写放大。
//
// 页缓存（最近 n 次使用的页）与缺页处理由 BufferPoolManager 承担：本类所有页访问
// 都经 fetch_page / new_page 走缓冲池，缺页时由缓冲池从磁盘读入并按 LRU 淘汰脏页。
//
// 约定：目录中缺失的表视为无数据；空表不占用任何数据页。
class BufferPoolManager;

// 行号：定位一行的物理位置（数据页号 + 页内槽位号），供 update / delete 使用
struct RowId
{
    page_id_t page_id = 0;
    uint16_t slot = 0;
};

// slotted 堆页布局（4KB）：
//   偏移 0  : uint32 下一页页号（0 表示链尾）
//   偏移 4  : uint16 槽位数量
//   偏移 6  : uint16 元组数据区起始偏移（元组自页尾向下增长）
//   偏移 8  : 槽位目录，每项 4 字节 {uint16 偏移, uint16 长度}；长度 0 表示空槽
namespace heap_page
{
    constexpr size_t HEADER_SIZE = 8;
    constexpr size_t SLOT_SIZE = 4;
    constexpr size_t MAX_SLOT_COUNT = (PAGE_SIZE - HEADER_SIZE) / SLOT_SIZE; // 1022
    // 单行元组上限：页头 + 一个槽位之外的全部空间
    constexpr size_t MAX_TUPLE_SIZE = PAGE_SIZE - HEADER_SIZE - SLOT_SIZE; // 4084

    inline uint32_t next_page(const char *data)
    {
        uint32_t value = 0;
        std::memcpy(&value, data, sizeof(value));
        return value;
    }

    inline void set_next_page(char *data, uint32_t next)
    {
        std::memcpy(data, &next, sizeof(next));
    }

    inline uint16_t slot_count(const char *data)
    {
        uint16_t value = 0;
        std::memcpy(&value, data + 4, sizeof(value));
        return value;
    }

    inline void set_slot_count(char *data, size_t count)
    {
        const uint16_t value = static_cast<uint16_t>(count);
        std::memcpy(data + 4, &value, sizeof(value));
    }

    inline uint16_t free_area_start(const char *data)
    {
        uint16_t value = 0;
        std::memcpy(&value, data + 6, sizeof(value));
        return value;
    }

    inline void set_free_area_start(char *data, size_t offset)
    {
        const uint16_t value = static_cast<uint16_t>(offset);
        std::memcpy(data + 6, &value, sizeof(value));
    }

    inline size_t slot_offset(const char *data, size_t slot)
    {
        uint16_t value = 0;
        std::memcpy(&value, data + HEADER_SIZE + slot * SLOT_SIZE, sizeof(value));
        return value;
    }

    inline size_t slot_length(const char *data, size_t slot)
    {
        uint16_t value = 0;
        std::memcpy(&value, data + HEADER_SIZE + slot * SLOT_SIZE + 2, sizeof(value));
        return value;
    }

    inline void set_slot(char *data, size_t slot, size_t offset, size_t length)
    {
        const uint16_t offset_u16 = static_cast<uint16_t>(offset);
        const uint16_t length_u16 = static_cast<uint16_t>(length);
        std::memcpy(data + HEADER_SIZE + slot * SLOT_SIZE, &offset_u16, sizeof(offset_u16));
        std::memcpy(data + HEADER_SIZE + slot * SLOT_SIZE + 2, &length_u16, sizeof(length_u16));
    }

    // 槽位目录末端（空闲区起点 = free_area_start，二者之间即空闲字节数）
    inline size_t directory_end(const char *data)
    {
        return HEADER_SIZE + static_cast<size_t>(slot_count(data)) * SLOT_SIZE;
    }

    inline size_t free_space(const char *data)
    {
        const size_t start = free_area_start(data);
        const size_t end = directory_end(data);
        return start > end ? start - end : 0;
    }

    inline void init(char *data)
    {
        std::memset(data, 0, PAGE_SIZE);
        set_next_page(data, 0);
        set_slot_count(data, 0);
        set_free_area_start(data, PAGE_SIZE);
    }
} // namespace heap_page

// 表内行的流式迭代器：一次只在缓冲池驻留一页的元组，内存占用与表规模无关。
// 迭代期间若页被回收（整页清空），已拷贝的页号链表与页缓冲仍自洽：
// 被回收的页只可能是「刚刚读完、缓冲已耗尽」的当前页。
class RowIterator
{
public:
    RowIterator(BufferPoolManager *pool, std::vector<page_id_t> pages, bool reverse);

    // 产出一行；无更多行返回 false
    bool next(Row &out);

    // 最近一次 next 产出行所在位置（供 update / delete 原地定位）
    const RowId &last_rid() const;

private:
    // 载入下一非空页的所有活行到缓冲区；无页可载返回 false
    bool load_next_page();

    BufferPoolManager *pool_;
    std::vector<page_id_t> pages_;
    bool reverse_;
    size_t pages_consumed_ = 0;
    std::vector<std::pair<RowId, Row>> buffer_;
    size_t buffer_pos_ = 0;
    RowId last_rid_;
};

class RowStore
{
public:
    RowStore();
    ~RowStore();

    RowStore(const RowStore &) = delete;
    RowStore &operator=(const RowStore &) = delete;

    // 打开数据库文件（不存在则创建目录与文件）；必要时分配 1 号目录根页并载入目录
    void open(const std::string &db_file);

    // 追加一行（写入链尾页，必要时分配新页）；行过大抛 StorageError(INTERNAL_ERROR)
    void insert_row(const std::string &table_name, const Row &row);

    // 就地更新一行（放不下时删除旧槽位并追加到链尾）；行已不存在返回 false
    bool update_row(const std::string &table_name, const RowId &rid, const Row &row);

    // 删除一行（槽位置空；整页清空时回收该页）；行已不存在返回 false
    bool delete_row(const std::string &table_name, const RowId &rid);

    // 删除表：回收其全部数据页并从目录移除
    void remove_table(const std::string &table_name);

    // 流式扫描整表（reverse = true 时按页链逆序产出）
    std::unique_ptr<RowIterator> scan(const std::string &table_name, bool reverse = false);

    // 持久化目录变更（无变更则不做任何磁盘 I/O）。
    // 行数据页本身由缓冲池按 LRU 淘汰时写回、进程退出时统一刷盘，
    // 因此行级写操作不会整表重写，也不会每次操作都强制写出全部脏页
    void flush();

private:
    // 表的数据页链（惰性载入后常驻内存，占用与页数成正比、与行数无关）
    struct TableState
    {
        page_id_t head = 0; // 0 表示空表
        std::vector<page_id_t> pages;
    };

    TableState &table_state(const std::string &table_name);

    // 在链尾追加一个已初始化的新页并链接，返回其页号
    page_id_t append_new_page(TableState &state);

    // 回收一个整页：从页链摘除并归还磁盘页；表因此变空时清掉目录项
    void drop_page(const std::string &table_name, TableState &state, page_id_t page_id);

    // 把元组追加到链尾（链尾放不下则新建页）
    void append_tuple_at_tail(const std::string &table_name, const std::string &tuple);

    // 目录本身仍以「页链 + 字节块」形式存放在根页链上（内容极小、极少重写）
    std::vector<page_id_t> read_chain(page_id_t start);
    std::vector<page_id_t> write_blob(const std::string &text,
                                      const std::vector<page_id_t> &old_pages);
    std::string read_blob(const std::vector<page_id_t> &pages);

    void load_directory();  // 从磁盘目录页链恢复 dir_
    void write_directory(); // 把 dir_ 序列化并写为目录页链

    std::unique_ptr<BufferPoolManager> pool_;
    page_id_t root_page_ = INVALID_PAGE_ID;
    std::vector<page_id_t> dir_pages_;         // 目录页链（首元素为根页）
    std::map<std::string, page_id_t> dir_;     // 表名 -> 数据页链首页（缺失 = 空表）
    std::map<std::string, TableState> states_; // 表名 -> 页链缓存
    bool dir_dirty_ = false;                   // 目录是否有未落盘的变更
};

#endif
