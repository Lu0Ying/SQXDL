package com.sqxdl.parser;

/**
 * 词法分析器（A 组）。
 * 职责：扫描 SQL 源码文本，逐个产出 {@link Token}。
 */
public class Lexer {

    /**
     * 读取并返回下一个 Token；源码读完时返回 EOF 标记（具体约定由 A 组确定）。
     *
     * @return 下一个词法记号
     */
    public Token nextToken() {
        // TODO: 识别关键字/标识符/常量/运算符/分隔符，跳过空白并维护 line/col；
        //       支持 "se" -> "select" 的拼写纠错（建议：标识符匹配失败且与关键字编辑距离为 1 时自动纠正）
        return null;
    }
}
