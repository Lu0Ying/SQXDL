#ifndef STORAGE_CORE_PAGE_H
#define STORAGE_CORE_PAGE_H

#include <cstdint>

// 页大小：4KB，与操作系统页对齐，是磁盘 I/O 与缓冲池的基本单位
constexpr uint32_t PAGE_SIZE = 4096;

// 页号：0 号页固定为数据库文件头页（元数据），数据页从 1 开始编号
using page_id_t = uint32_t;

// 无效页号
constexpr page_id_t INVALID_PAGE_ID = 0xFFFFFFFFu;

// 缓冲池中的页（帧）：磁盘页在内存中的映像
// 存储核心为单线程服务，无需页级并发控制
class Page
{
public:
    Page();

    page_id_t id() const;
    bool is_dirty() const;
    int pin_count() const;

    // 页数据（PAGE_SIZE 字节），页内容的读写均通过该指针进行
    char *data();
    const char *data() const;

private:
    friend class BufferPoolManager;

    // 复用该帧承载新页：设置页号、清零数据、清标记（pin 计数归 0）
    void reset(page_id_t page_id);

    page_id_t id_;
    int pin_count_;
    bool dirty_;
    char data_[PAGE_SIZE]{};
};

#endif
