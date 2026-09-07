package com.sqxdl.storage;

/**
 * 数据页（C 组）。
 * 职责：页式存储的最小 I/O 单位，以字节数组承载定长数据，
 *       提供按偏移量的二进制读写能力。
 */
public class Page {

    /** 页大小（字节），全系统统一约定为 4KB */
    public static final int PAGE_SIZE = 4096;

    /** 页内原始字节数据 */
    private byte[] data = new byte[PAGE_SIZE];

    /**
     * 从页内指定偏移量读取一个 4 字节整数（大端序）。
     *
     * @param offset 字节偏移量（0 <= offset <= PAGE_SIZE - 4）
     * @return 读到的整数值
     */
    public int readInt(int offset) {
        // TODO: 实现二进制读取：组合 data[offset..offset+3] 还原 int，注意边界校验
        return 0;
    }

    /**
     * 将一个 4 字节整数写入页内指定偏移量（大端序）。
     *
     * @param offset 字节偏移量（0 <= offset <= PAGE_SIZE - 4）
     * @param value  待写入的整数值
     */
    public void writeInt(int offset, int value) {
        // TODO: 实现二进制写入：拆分 int 为 4 个字节写入 data，并标记该页为脏页
    }
}
