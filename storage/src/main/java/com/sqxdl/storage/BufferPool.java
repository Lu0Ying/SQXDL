package com.sqxdl.storage;

/**
 * 缓冲池（C 组）。
 * 职责：管理内存中的数据页缓存，缓存未命中时从磁盘读入，
 *       缓存满时按 LRU 策略淘汰脏页回写。
 */
public class BufferPool {

    /**
     * 获取指定页号的数据页字节内容；缓存未命中时负责从磁盘加载。
     *
     * @param pageId 页号
     * @return 该页的字节数组（长度为 {@link Page#PAGE_SIZE}）
     */
    public byte[] getPage(int pageId) {
        // TODO: 实现 LRU 缓存淘汰：
        //       1) 命中缓存则将该页移到最近使用位置并返回；
        //       2) 未命中则从磁盘文件读入一页；
        //       3) 缓存已满时淘汰最久未使用页，脏页需先回写磁盘
        return null;
    }
}
