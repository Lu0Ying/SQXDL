package com.sqxdl.parser;

import java.util.List;

/**
 * 抽象语法树（AST）节点基类，是语法分析器的输出契约。
 * 所有节点携带源码行列号，便于语义分析阶段定位错误。
 * 具体语句/表达式节点以静态内部类形式定义在本文件中。
 */
public abstract class ASTNode {

    private final int line;
    private final int col;

    protected ASTNode(int line, int col) {
        this.line = line;
        this.col = col;
    }

    public int getLine() {
        return line;
    }

    public int getCol() {
        return col;
    }

    /**
     * SELECT 语句节点。
     * 目前仅支持单表查询，对应语法：SELECT selectList FROM tableName [WHERE whereCond]
     */
    public static class SelectStmt extends ASTNode {

        private final String tableName;
        /** 查询列名列表；SELECT * 时约定为表的全部列 */
        private final List<String> selectList;
        /** WHERE 条件表达式；无 WHERE 子句时为 null */
        private final ASTNode whereCond;

        public SelectStmt(int line, int col, String tableName,
                          List<String> selectList, ASTNode whereCond) {
            super(line, col);
            this.tableName = tableName;
            this.selectList = selectList;
            this.whereCond = whereCond;
        }

        public String getTableName() {
            return tableName;
        }

        public List<String> getSelectList() {
            return selectList;
        }

        public ASTNode getWhereCond() {
            return whereCond;
        }

        @Override
        public String toString() {
            return "SelectStmt{table=" + tableName
                    + ", columns=" + selectList
                    + ", where=" + whereCond + "}";
        }
    }

    /**
     * 二元运算表达式节点，如 id &gt; 1、a = b。
     * op 为运算符文本，left/right 为左右操作数表达式。
     */
    public static class BinaryExpr extends ASTNode {

        private final String op;
        private final ASTNode left;
        private final ASTNode right;

        public BinaryExpr(int line, int col, String op, ASTNode left, ASTNode right) {
            super(line, col);
            this.op = op;
            this.left = left;
            this.right = right;
        }

        public String getOp() {
            return op;
        }

        public ASTNode getLeft() {
            return left;
        }

        public ASTNode getRight() {
            return right;
        }

        @Override
        public String toString() {
            return "(" + left + " " + op + " " + right + ")";
        }
    }

    /**
     * 字面量表达式节点，叶子节点，如 123、'abc'。
     * value 统一以字符串形式保存，具体类型由语义分析阶段推断。
     */
    public static class LiteralExpr extends ASTNode {

        private final String value;

        public LiteralExpr(int line, int col, String value) {
            super(line, col);
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        @Override
        public String toString() {
            return "'" + value + "'";
        }
    }
}
