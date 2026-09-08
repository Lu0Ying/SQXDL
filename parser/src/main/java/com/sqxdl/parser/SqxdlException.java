package com.sqxdl.parser;

/**
 * 全组统一异常：parser/semantic/executor 模块报错均抛出本异常。（storage 为独立 C++ 程序，不适用本异常类）
 * 构造时必须携带源码行列号，消息格式统一为 "[行:列] 内容"，方便 D 组统一捕获打印。
 */
public class SqxdlException extends RuntimeException {

    private final int line;
    private final int col;

    public SqxdlException(int line, int col, String message) {
        super("[" + line + ":" + col + "] " + message);
        this.line = line;
        this.col = col;
    }

    public int getLine() {
        return line;
    }

    public int getCol() {
        return col;
    }
}
