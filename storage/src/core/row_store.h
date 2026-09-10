#ifndef STORAGE_CORE_ROW_STORE_H
#define STORAGE_CORE_ROW_STORE_H

#include <map>
#include <memory>
#include <string>
#include <vector>

#include "page.h"
#include "row.h"

// 行数据存储（分页）：行数据与表结构（catalog.json）分离，存放于独立数据库文件。
//
// 文件组织：整张表的行序列化为一个 JSON 字节流，按「页链」分块存放：
// 每个数据页布局为 [4 字节 下一页页号][4 字节 块长][块数据(最大 PAGE_SIZE-8)]，
// 页的分配/复用/回收、内存缓存（LRU）与脏页写回均由 BufferPoolManager 承担。
// 表名 -> 数据页链 的目录同样以页链形式存放在同一文件内，链首固定为 1 号页
// （文件创建时预留、永不被回收），因此进程重启后可通过 1 号页恢复目录。
//
// 约定：目录中缺失的表视为无数据；空表不占用任何数据页。
class BufferPoolManager;

class RowStore
{
public:
    RowStore();
    ~RowStore();

    RowStore(const RowStore &) = delete;
    RowStore &operator=(const RowStore &) = delete;

    // 打开数据库文件（不存在则创建目录与文件）；必要时分配 1 号目录根页并载入目录
    void open(const std::string &db_file);

    // 将整表 rows 写为数据页链（空表则回收旧页，不占页）
    void save_table(const std::string &table_name, const std::vector<Row> &rows);

    // 读回整表行数据；目录中无此表（或数据页链为空）返回空
    std::vector<Row> load_table(const std::string &table_name);

    // 删除表：回收其全部数据页并从目录移除
    void remove_table(const std::string &table_name);

    // 把内存目录（表名 -> 数据页链）写回磁盘
    void flush();

private:
    // 以 start 为链首，沿 next 指针顺序读取页链（0 / 无效页号视为链尾）
    std::vector<page_id_t> read_chain(page_id_t start);

    // 将字节流写入页链：复用旧页、不足追加、多余回收；返回新页链
    std::vector<page_id_t> write_blob(const std::string &text,
                                      const std::vector<page_id_t> &old_pages);

    // 顺序读取页链内容并拼接为字节流
    std::string read_blob(const std::vector<page_id_t> &pages);

    void load_directory();  // 从磁盘目录页链恢复 dir_
    void write_directory(); // 把 dir_ 序列化并写为目录页链

    std::unique_ptr<BufferPoolManager> pool_;
    page_id_t root_page_ = INVALID_PAGE_ID;
    std::vector<page_id_t> dir_pages_;                  // 目录页链（首元素为根页）
    std::map<std::string, std::vector<page_id_t>> dir_; // 表名 -> 数据页链
};

#endif
