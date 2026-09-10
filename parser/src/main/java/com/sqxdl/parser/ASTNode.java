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

    /** 预留类型字段：语法阶段暂为 null，由语义分析阶段补充（如 INT / VARCHAR / BOOLEAN） */
    private String type;

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

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    /**
     * SELECT 语句节点。
     * 支持单表 / 多表 JOIN，对应语法：
     * SELECT selectList FROM tableName { JOIN tableName ON expr }
     *   [WHERE whereCond] [GROUP BY id_list] [ORDER BY order_list]
     * WHERE 与 JOIN ON 条件分离存储，为语义层做跨表谓词下推提供结构基础
     * （语义层可将只涉及单表的谓词下推到对应表扫描）。
     */
    public static class SelectStmt extends ASTNode {

        /** 连接子句：JOIN 的表与 ON 条件（onCond 已做常量折叠 / 逻辑简化） */
        public static class JoinClause {
            private final String tableName;
            private final ASTNode onCond;

            public JoinClause(String tableName, ASTNode onCond) {
                this.tableName = tableName;
                this.onCond = onCond;
            }

            public String getTableName() {
                return tableName;
            }

            public ASTNode getOnCond() {
                return onCond;
            }

            @Override
            public String toString() {
                return "JoinClause{table=" + tableName + ", on=" + onCond + "}";
            }
        }

        /** 排序项：列名 + 方向（"ASC" / "DESC"，缺省为 "ASC"） */
        public static class OrderItem {
            private final String column;
            private final String direction;

            public OrderItem(String column, String direction) {
                this.column = column;
                this.direction = direction;
            }

            public String getColumn() {
                return column;
            }

            public String getDirection() {
                return direction;
            }

            @Override
            public String toString() {
                return column + " " + direction;
            }
        }

        private final String tableName;
        /** 查询列名列表；SELECT * 时约定为 ["*"]，展开由语义层负责 */
        private final List<String> selectList;
        /** WHERE 条件表达式；无 WHERE 子句时为 null */
        private final ASTNode whereCond;
        /** JOIN 连接子句列表（按书写顺序）；无 JOIN 时为空列表 */
        private final List<JoinClause> joins;
        /** GROUP BY 列名列表；无 GROUP BY 时为空列表 */
        private final List<String> groupBy;
        /** ORDER BY 排序项列表（按书写顺序）；无 ORDER BY 时为空列表 */
        private final List<OrderItem> orderBy;

        public SelectStmt(int line, int col, String tableName,
                          List<String> selectList, ASTNode whereCond,
                          List<JoinClause> joins, List<String> groupBy,
                          List<OrderItem> orderBy) {
            super(line, col);
            this.tableName = tableName;
            this.selectList = selectList;
            this.whereCond = whereCond;
            this.joins = joins;
            this.groupBy = groupBy;
            this.orderBy = orderBy;
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

        /** JOIN 子句列表；无 JOIN 时返回空列表（非 null） */
        public List<JoinClause> getJoins() {
            return joins;
        }

        /** GROUP BY 列名列表；无 GROUP BY 时返回空列表（非 null） */
        public List<String> getGroupBy() {
            return groupBy;
        }

        /** ORDER BY 排序项列表；无 ORDER BY 时返回空列表（非 null） */
        public List<OrderItem> getOrderBy() {
            return orderBy;
        }

        @Override
        public String toString() {
            return "SelectStmt{table=" + tableName
                    + ", columns=" + selectList
                    + (joins.isEmpty() ? "" : ", joins=" + joins)
                    + ", where=" + whereCond
                    + (groupBy.isEmpty() ? "" : ", groupBy=" + groupBy)
                    + (orderBy.isEmpty() ? "" : ", orderBy=" + orderBy) + "}";
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
     * 一元运算表达式节点，如 NOT 条件。
     * op 为运算符文本（如 "NOT"），operand 为操作数表达式。
     */
    public static class UnaryExpr extends ASTNode {

        private final String op;
        private final ASTNode operand;

        public UnaryExpr(int line, int col, String op, ASTNode operand) {
            super(line, col);
            this.op = op;
            this.operand = operand;
        }

        public String getOp() {
            return op;
        }

        public ASTNode getOperand() {
            return operand;
        }

        @Override
        public String toString() {
            return "(" + op + " " + operand + ")";
        }
    }

    /**
     * 字面量表达式节点，叶子节点，如 123、'abc'。
     * value 统一以字符串形式保存原文本；kind 记录词法类型，
     * 供语义分析阶段区分数字与字符串（如 WHERE id = 1 与 id = '1'）。
     */
    public static class LiteralExpr extends ASTNode {

        /** 字面量词法类型 */
        public enum Kind { NUMBER, STRING, BOOLEAN }

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
     * 标识符表达式节点（叶子），如 WHERE id &gt; 1 中的 id。
     * 与 LiteralExpr 一起构成条件表达式的两种叶子；语义分析阶段可据此区分"列"与"常量"。
     */
    public static class IdentifierExpr extends ASTNode {

        private final String name;

        public IdentifierExpr(int line, int col, String name) {
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
     * CREATE TABLE 语句节点，对应语法：CREATE TABLE tableName '(' column_def { ',' column_def } ')'
     */
    public static class CreateTableStmt extends ASTNode {

        /** 列定义：列名 + 类型（INT | VARCHAR） */
        public static class ColumnDef {
            private final String name;
            private final String type;

            public ColumnDef(String name, String type) {
                this.name = name;
                this.type = type;
            }

            public String getName() {
                return name;
            }

            public String getType() {
                return type;
            }

            @Override
            public String toString() {
                return name + " " + type;
            }
        }

        private final String tableName;
        /** 列定义清单：按建表书写顺序，含列名与类型 */
        private final List<ColumnDef> columns;

        public CreateTableStmt(int line, int col, String tableName, List<ColumnDef> columns) {
            super(line, col);
            this.tableName = tableName;
            this.columns = columns;
        }

        public String getTableName() {
            return tableName;
        }

        public List<ColumnDef> getColumns() {
            return columns;
        }

        @Override
        public String toString() {
            return "CreateTableStmt{table=" + tableName + ", columns=" + columns + "}";
        }
    }

    /**
     * SHOW 语句节点，对应语法：SHOW TABLES | SHOW TABLE tableName | DESCRIBE tableName | DESC tableName。
     * target 为 "TABLES" 或 "TABLE"；target 为 "TABLE" 时 tableName 为表名，否则为 null。
     * DESCRIBE / DESC 是 SHOW TABLE 的等价写法，归一化为 target=TABLE 输出。
     */
    public static class ShowStmt extends ASTNode {

        /** SHOW 的目标：TABLES（列全部表）或 TABLE（查看指定表） */
        private final String target;
        /** SHOW TABLE 时指定表名；SHOW TABLES 时为 null */
        private final String tableName;

        public ShowStmt(int line, int col, String target, String tableName) {
            super(line, col);
            this.target = target;
            this.tableName = tableName;
        }

        public String getTarget() {
            return target;
        }

        public String getTableName() {
            return tableName;
        }

        @Override
        public String toString() {
            return tableName == null
                    ? "ShowStmt{target=TABLES}"
                    : "ShowStmt{target=TABLE, table=" + tableName + "}";
        }
    }

    /**
     * DROP TABLE 语句节点，对应语法：DROP TABLE tableName。
     * 语义层据此删除指定表及其数据。
     */
    public static class DropTableStmt extends ASTNode {

        private final String tableName;

        public DropTableStmt(int line, int col, String tableName) {
            super(line, col);
            this.tableName = tableName;
        }

        public String getTableName() {
            return tableName;
        }

        @Override
        public String toString() {
            return "DropTableStmt{table=" + tableName + "}";
        }
    }
}
