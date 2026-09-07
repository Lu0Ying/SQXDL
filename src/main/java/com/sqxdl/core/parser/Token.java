package com.sqxdl.core.parser;

/**
 * 词法分析产生的记号（Token），是词法分析器与语法分析器之间的接口契约。
 * 字段全部不可变，保证 Token 在各阶段间安全传递。
 */
public class Token {

    /** Token 类别 */
    public enum Type {
        /** 关键字，如 SELECT、FROM、WHERE、CREATE */
        KEYWORD,
        /** 标识符，如表名、列名 */
        IDENTIFIER,
        /** 字面常量，如整数、字符串 */
        CONST,
        /** 运算符，如 =、&lt;、&gt;、+、- */
        OPERATOR,
        /** 分隔符，如 ,、;、(、) */
        DELIMITER
    }

    private final Type type;
    /** 词法单元的原始文本 */
    private final String lexeme;
    /** 源码行号（从 1 开始），用于报错定位 */
    private final int line;
    /** 源码列号（从 1 开始），用于报错定位 */
    private final int col;

    public Token(Type type, String lexeme, int line, int col) {
        this.type = type;
        this.lexeme = lexeme;
        this.line = line;
        this.col = col;
    }

    public Type getType() {
        return type;
    }

    public String getLexeme() {
        return lexeme;
    }

    public int getLine() {
        return line;
    }

    public int getCol() {
        return col;
    }

    @Override
    public String toString() {
        return "Token{" + type + ", '" + lexeme + "', 位置=" + line + ":" + col + "}";
    }
}
