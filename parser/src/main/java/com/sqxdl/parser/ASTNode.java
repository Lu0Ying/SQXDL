package com.sqxdl.parser;

import java.util.List;
import java.util.Map;

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
     * value 统一以字符串形式保存原文本；kind 记录词法类型，
     * 供语义分析阶段区分数字与字符串（如 WHERE id = 1 与 id = '1'）。
     */
    public static class LiteralExpr extends ASTNode {

        /** 字面量词法类型 */
        public enum Kind { NUMBER, STRING }

        private final String value;
        private final Kind kind;

        public LiteralExpr(int line, int col, String value, Kind kind) {
            super(line, col);
            this.value = value;
            this.kind = kind;
        }

        public String getValue() {
            return value;
        }

        public Kind getKind() {
            return kind;
        }

        @Override
        public String toString() {
            return kind == Kind.NUMBER ? value : "'" + value + "'";
        }
    }

    /**
     * 列引用表达式节点，如 WHERE id &gt; 1 中的 id。
     * 与 LiteralExpr 一起构成条件表达式的两种叶子。
     */
    public static class ColumnRef extends ASTNode {

        private final String name;

        public ColumnRef(int line, int col, String name) {
            super(line, col);
            this.name = name;
        }

        public String getName() {
            return name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * INSERT 语句节点，对应语法：INSERT INTO tableName [(colList)] VALUES (valueList)
     * columns 为空时表示按建表顺序对应 values。
     */
    public static class InsertStmt extends ASTNode {

        private final String tableName;
        /** 目标列名清单；未指定时为空列表 */
        private final List<String> columns;
        private final List<LiteralExpr> values;

        public InsertStmt(int line, int col, String tableName,
                          List<String> columns, List<LiteralExpr> values) {
            super(line, col);
            this.tableName = tableName;
            this.columns = columns;
            this.values = values;
        }

        public String getTableName() {
            return tableName;
        }

        public List<String> getColumns() {
            return columns;
        }

        public List<LiteralExpr> getValues() {
            return values;
        }

        @Override
        public String toString() {
            return "InsertStmt{table=" + tableName + ", columns=" + columns + ", values=" + values + "}";
        }
    }

    /**
     * UPDATE 语句节点，对应语法：UPDATE tableName SET col = value,... [WHERE cond]
     * assignments 保持 SET 子句中的书写顺序。
     */
    public static class UpdateStmt extends ASTNode {

        private final String tableName;
        /** 列名 -> 新值（LinkedHashMap 保持书写顺序） */
        private final Map<String, LiteralExpr> assignments;
        /** WHERE 条件表达式；无 WHERE 子句时为 null */
        private final ASTNode whereCond;

        public UpdateStmt(int line, int col, String tableName,
                          Map<String, LiteralExpr> assignments, ASTNode whereCond) {
            super(line, col);
            this.tableName = tableName;
            this.assignments = assignments;
            this.whereCond = whereCond;
        }

        public String getTableName() {
            return tableName;
        }

        public Map<String, LiteralExpr> getAssignments() {
            return assignments;
        }

        public ASTNode getWhereCond() {
            return whereCond;
        }

        @Override
        public String toString() {
            return "UpdateStmt{table=" + tableName + ", set=" + assignments + ", where=" + whereCond + "}";
        }
    }

    /**
     * DELETE 语句节点，对应语法：DELETE FROM tableName [WHERE cond]
     */
    public static class DeleteStmt extends ASTNode {

        private final String tableName;
        /** WHERE 条件表达式；无 WHERE 子句时为 null */
        private final ASTNode whereCond;

        public DeleteStmt(int line, int col, String tableName, ASTNode whereCond) {
            super(line, col);
            this.tableName = tableName;
            this.whereCond = whereCond;
        }

        public String getTableName() {
            return tableName;
        }

        public ASTNode getWhereCond() {
            return whereCond;
        }

        @Override
        public String toString() {
            return "DeleteStmt{table=" + tableName + ", where=" + whereCond + "}";
        }
    }

    /**
     * CREATE TABLE 语句节点，对应语法：CREATE TABLE tableName (colList)
     */
    public static class CreateTableStmt extends ASTNode {

        private final String tableName;
        private final List<String> columns;

        public CreateTableStmt(int line, int col, String tableName, List<String> columns) {
            super(line, col);
            this.tableName = tableName;
            this.columns = columns;
        }

        public String getTableName() {
            return tableName;
        }

        public List<String> getColumns() {
            return columns;
        }

        @Override
        public String toString() {
            return "CreateTableStmt{table=" + tableName + ", columns=" + columns + "}";
        }
    }
}
