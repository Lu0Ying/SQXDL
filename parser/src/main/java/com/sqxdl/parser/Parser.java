package com.sqxdl.parser;

/**
 * 语法分析器（A 组）。
 * 职责：消费 {@link Lexer} 产出的 Token 流，递归下降构建 {@link ASTNode} 语法树。
 */
public class Parser {

    /**
     * 解析完整 SQL 语句，返回 AST 根节点。
     *
     * @return 语法树根节点（如 SelectStmt）
     */
    public ASTNode parse() {
        // TODO: 递归下降构建 AST：SELECT 列清单 FROM 表名 [WHERE 条件表达式]，
        //       遇到语法错误时抛出带行号列号的异常
        return null;
    }
}
