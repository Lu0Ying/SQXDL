package com.sqxdl.semantic;

import com.sqxdl.parser.ASTNode;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;

/**
 * 逻辑执行计划节点基类，是语义分析/优化器与执行器之间的接口契约。
 * 计划以树状结构组织：执行器自顶向下调用，每个节点处理其子节点 child 的输出。
 * 具体计划节点以静态内部类形式定义在本文件中。
 */
public abstract class PlanNode {

    /**
     * 顺序扫描计划：叶子节点，对 tableName 对应表做全表扫描。
     * columns 为 null 时表示全表扫描；非 null 时只读取指定列（投影下推）。
     */
    public static class SeqScanPlan extends PlanNode {

        private final String tableName;
        private final List<String> columns;

        public SeqScanPlan(String tableName) {
            this(tableName, null);
        }

        public SeqScanPlan(String tableName, List<String> columns) {
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
            return columns == null
                    ? "SeqScan{table=" + tableName + "}"
                    : "SeqScan{table=" + tableName + ", columns=" + columns + "}";
        }
    }

    /**
     * 过滤计划：对 child 输出的每一行元组，按 condition（AST 条件表达式）求值，
     * 仅保留结果为真的行，对应 WHERE 子句。
     */
    public static class FilterPlan extends PlanNode {

        private final ASTNode condition;
        private final PlanNode child;

        public FilterPlan(ASTNode condition, PlanNode child) {
            this.condition = condition;
            this.child = child;
        }

        public ASTNode getCondition() {
            return condition;
        }

        public PlanNode getChild() {
            return child;
        }

        @Override
        public String toString() {
            return "Filter{cond=" + condition + ", child=" + child + "}";
        }
    }

    /**
     * 投影计划：从 child 输出的行中只保留 columns 指定的列，对应 SELECT 列清单。
     */
    public static class ProjectPlan extends PlanNode {

        private final List<String> columns;
        private final PlanNode child;

        public ProjectPlan(List<String> columns, PlanNode child) {
            this.columns = columns;
            this.child = child;
        }

        public List<String> getColumns() {
            return columns;
        }

        public PlanNode getChild() {
            return child;
        }

        @Override
        public String toString() {
            return "Project{columns=" + columns + ", child=" + child + "}";
        }
    }

    /**
     * 插入计划：向 tableName 表插入一行，对应 INSERT 语句。
     * values 为字面量清单，顺序与 columns 对应（columns 为空时按建表顺序）。
     */
    public static class InsertPlan extends PlanNode {

        private final String tableName;
        private final List<String> columns;
        private final List<ASTNode.LiteralExpr> values;

        public InsertPlan(String tableName, List<String> columns, List<ASTNode.LiteralExpr> values) {
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

        public List<ASTNode.LiteralExpr> getValues() {
            return values;
        }

        @Override
        public String toString() {
            return "Insert{table=" + tableName + ", columns=" + columns + ", values=" + values + "}";
        }
    }

    /**
     * 更新计划：更新满足条件的行，对应 UPDATE 语句。
     * assignments 为列名 -> 新值；condition 为 null 时作用于全表。
     */
    public static class UpdatePlan extends PlanNode {

        private final String tableName;
        private final Map<String, ASTNode.LiteralExpr> assignments;
        private final ASTNode condition;

        public UpdatePlan(String tableName, Map<String, ASTNode.LiteralExpr> assignments, ASTNode condition) {
            this.tableName = tableName;
            this.assignments = assignments;
            this.condition = condition;
        }

        public String getTableName() {
            return tableName;
        }

        public Map<String, ASTNode.LiteralExpr> getAssignments() {
            return assignments;
        }

        public ASTNode getCondition() {
            return condition;
        }

        @Override
        public String toString() {
            return "Update{table=" + tableName + ", set=" + assignments + ", cond=" + condition + "}";
        }
    }

    /**
     * 删除计划：删除满足条件的行，对应 DELETE 语句。
     * condition 为 null 时作用于全表。
     */
    public static class DeletePlan extends PlanNode {

        private final String tableName;
        private final ASTNode condition;

        public DeletePlan(String tableName, ASTNode condition) {
            this.tableName = tableName;
            this.condition = condition;
        }

        public String getTableName() {
            return tableName;
        }

        public ASTNode getCondition() {
            return condition;
        }

        @Override
        public String toString() {
            return "Delete{table=" + tableName + ", cond=" + condition + "}";
        }
    }

    /**
     * 建表计划：创建表并登记列名清单，对应 CREATE TABLE 语句。
     * 存储核心协议已升级为带类型的列定义，可选携带 columnDefs（为 null 时退化为仅列名）。
     */
    public static class CreateTablePlan extends PlanNode {

        private final String tableName;
        private final List<String> columns;
        private final List<CatalogImpl.ColumnInfo> columnDefs;

        public CreateTablePlan(String tableName, List<String> columns) {
            this(tableName, columns, null);
        }

        public CreateTablePlan(String tableName, List<String> columns,
                               List<CatalogImpl.ColumnInfo> columnDefs) {
            this.tableName = tableName;
            this.columns = columns;
            this.columnDefs = columnDefs;
        }

        public String getTableName() {
            return tableName;
        }

        public List<String> getColumns() {
            return columns;
        }

        /** 带类型的列定义（可能为 null，序列化时按需退化） */
        public List<CatalogImpl.ColumnInfo> getColumnDefs() {
            return columnDefs;
        }

        @Override
        public String toString() {
            return "CreateTable{table=" + tableName + ", columns=" + columns + "}";
        }
    }

    /**
     * 列出所有表计划，对应 SHOW TABLES 语句。
     * 返回单列 table，每行为一个表名。
     */
    public static class ShowTablesPlan extends PlanNode {

        public ShowTablesPlan() {
        }

        @Override
        public String toString() {
            return "ShowTables{}";
        }
    }

    /**
     * 查看表结构计划，对应 SHOW TABLE 表名 语句（存储协议 describeTable）。
     * 返回 column + type 两列数据集，按建表列顺序排列。
     */
    public static class DescribeTablePlan extends PlanNode {

        private final String tableName;

        public DescribeTablePlan(String tableName) {
            this.tableName = tableName;
        }

        public String getTableName() {
            return tableName;
        }

        @Override
        public String toString() {
            return "DescribeTable{table=" + tableName + "}";
        }
    }

    /**
     * 删表计划，对应 DROP TABLE 语句。
     * 删除 tableName 对应的表及其数据。
     */
    public static class DropTablePlan extends PlanNode {

        private final String tableName;

        public DropTablePlan(String tableName) {
            this.tableName = tableName;
        }

        public String getTableName() {
            return tableName;
        }

        @Override
        public String toString() {
            return "DropTable{table=" + tableName + "}";
        }
    }

    /**
     * 连接计划：对多个子计划做笛卡尔积后按 onConditions 过滤。
     * 对应 SQL 的 JOIN ... ON 子句。支持多表连接。
     * 左表为 child（第一个子节点），右表通过 children 列表追加。
     * onConditions 与 children 一一对应：children[i] 的连接条件为 onConditions[i]。
     * 左表（第一个子节点）的 onCondition 约定为 null。
     */
    public static class JoinPlan extends PlanNode {

        private final List<PlanNode> children;
        private final List<ASTNode> onConditions;

        public JoinPlan(List<PlanNode> children, List<ASTNode> onConditions) {
            this.children = children;
            this.onConditions = onConditions;
        }

        public List<PlanNode> getChildren() {
            return children;
        }

        public List<ASTNode> getOnConditions() {
            return onConditions;
        }

        /** 兼容单 child 访问：返回第一个子节点 */
        public PlanNode getChild() {
            return children.isEmpty() ? null : children.get(0);
        }

        @Override
        public String toString() {
            return "Join{tables=" + children.size() + "}";
        }
    }

    /**
     * 分组计划：按 groupBy 列对 child 输出做分组。
     * 对应 SQL 的 GROUP BY 子句。无聚合函数时仅做去重分组。
     */
    public static class GroupByPlan extends PlanNode {

        private final List<String> groupByColumns;
        private final PlanNode child;

        public GroupByPlan(List<String> groupByColumns, PlanNode child) {
            this.groupByColumns = groupByColumns;
            this.child = child;
        }

        public List<String> getGroupByColumns() {
            return groupByColumns;
        }

        public PlanNode getChild() {
            return child;
        }

        @Override
        public String toString() {
            return "GroupBy{columns=" + groupByColumns + ", child=" + child + "}";
        }
    }

    /**
     * 排序计划：按 orderBy 指定的列和方向对 child 输出做排序。
     * 对应 SQL 的 ORDER BY 子句。每个 OrderItem 含列名与方向（ASC/DESC）。
     */
    public static class OrderByPlan extends PlanNode {

        /** 排序项：列名 + 方向 */
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

        private final List<OrderItem> orderByItems;
        private final PlanNode child;

        public OrderByPlan(List<OrderItem> orderByItems, PlanNode child) {
            this.orderByItems = orderByItems;
            this.child = child;
        }

        public List<OrderItem> getOrderByItems() {
            return orderByItems;
        }

        public PlanNode getChild() {
            return child;
        }

        @Override
        public String toString() {
            return "OrderBy{items=" + orderByItems + ", child=" + child + "}";
        }
    }

    // ========== 计划树可视化/调试输出 ==========

    /**
     * 以缩进树形格式打印计划树，便于调试。
     * 示例输出：
     * <pre>
     * ProjectPlan
     *   columns: [id, name]
     *   ├─ FilterPlan
     *     condition: (age > 18)
     *     ├─ SeqScanPlan
     *       table: student
     * </pre>
     *
     * @param plan 计划树根节点
     * @return 格式化后的字符串
     */
    public static String formatPlan(PlanNode plan) {
        StringBuilder sb = new StringBuilder();
        formatPlan(sb, plan, 0, true);
        return sb.toString();
    }

    /**
     * 递归格式化计划树节点。
     *
     * @param sb    字符串构建器
     * @param node  当前节点
     * @param depth 当前缩进深度
     * @param isLast 是否为父节点的最后一个子节点
     */
    private static void formatPlan(StringBuilder sb, PlanNode node, int depth, boolean isLast) {
        if (node == null) {
            indent(sb, depth, isLast);
            sb.append("(null)\n");
            return;
        }

        // 节点标题
        indent(sb, depth, isLast);
        sb.append(nodeName(node)).append("\n");

        // 节点属性
        formatNodeDetails(sb, node, depth + 1);

        // 递归子节点
        if (node instanceof SeqScanPlan) {
            // 叶子节点，无子节点
        } else if (node instanceof FilterPlan p) {
            formatPlan(sb, p.getChild(), depth + 1, true);
        } else if (node instanceof ProjectPlan p) {
            formatPlan(sb, p.getChild(), depth + 1, true);
        } else if (node instanceof GroupByPlan p) {
            formatPlan(sb, p.getChild(), depth + 1, true);
        } else if (node instanceof OrderByPlan p) {
            formatPlan(sb, p.getChild(), depth + 1, true);
        } else if (node instanceof JoinPlan p) {
            List<PlanNode> children = p.getChildren();
            for (int i = 0; i < children.size(); i++) {
                formatPlan(sb, children.get(i), depth + 1, i == children.size() - 1);
            }
        }
    }

    /**
     * 格式化节点的属性列表。
     */
    private static void formatNodeDetails(StringBuilder sb, PlanNode node, int depth) {
        if (node instanceof SeqScanPlan p) {
            detailLine(sb, depth, "table", p.getTableName());
            if (p.getColumns() != null) {
                detailLine(sb, depth, "columns", p.getColumns());
            }
        } else if (node instanceof FilterPlan p) {
            detailLine(sb, depth, "condition", formatExpr(p.getCondition()));
        } else if (node instanceof ProjectPlan p) {
            detailLine(sb, depth, "columns", p.getColumns());
        } else if (node instanceof InsertPlan p) {
            detailLine(sb, depth, "table", p.getTableName());
            detailLine(sb, depth, "columns", p.getColumns());
            detailLine(sb, depth, "values", formatValues(p.getValues()));
        } else if (node instanceof UpdatePlan p) {
            detailLine(sb, depth, "table", p.getTableName());
            detailLine(sb, depth, "set", formatAssignments(p.getAssignments()));
            detailLine(sb, depth, "condition", p.getCondition() == null ? "(全表)" : formatExpr(p.getCondition()));
        } else if (node instanceof DeletePlan p) {
            detailLine(sb, depth, "table", p.getTableName());
            detailLine(sb, depth, "condition", p.getCondition() == null ? "(全表)" : formatExpr(p.getCondition()));
        } else if (node instanceof CreateTablePlan p) {
            detailLine(sb, depth, "table", p.getTableName());
            detailLine(sb, depth, "columns", p.getColumns());
        } else if (node instanceof ShowTablesPlan) {
            // 无属性
        } else if (node instanceof DescribeTablePlan p) {
            detailLine(sb, depth, "table", p.getTableName());
        } else if (node instanceof DropTablePlan p) {
            detailLine(sb, depth, "table", p.getTableName());
        } else if (node instanceof JoinPlan p) {
            // ON 条件列表：on[i] 为 null 表示该子节点为左表
            List<ASTNode> onList = p.getOnConditions();
            List<String> onStrs = new ArrayList<>();
            for (ASTNode on : onList) {
                onStrs.add(on == null ? "(左表)" : formatExpr(on));
            }
            detailLine(sb, depth, "on", onStrs);
        } else if (node instanceof GroupByPlan p) {
            detailLine(sb, depth, "columns", p.getGroupByColumns());
        } else if (node instanceof OrderByPlan p) {
            detailLine(sb, depth, "items", p.getOrderByItems());
        }
    }

    /** 输出一条属性行 */
    private static void detailLine(StringBuilder sb, int depth, String key, Object value) {
        indent(sb, depth, false);
        sb.append(key).append(": ").append(value).append("\n");
    }

    /** 生成缩进 */
    private static void indent(StringBuilder sb, int depth, boolean isLast) {
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
    }

    /** 获取节点简短名称 */
    private static String nodeName(PlanNode node) {
        if (node instanceof SeqScanPlan) return "SeqScanPlan";
        if (node instanceof FilterPlan) return "FilterPlan";
        if (node instanceof ProjectPlan) return "ProjectPlan";
        if (node instanceof InsertPlan) return "InsertPlan";
        if (node instanceof UpdatePlan) return "UpdatePlan";
        if (node instanceof DeletePlan) return "DeletePlan";
        if (node instanceof CreateTablePlan) return "CreateTablePlan";
        if (node instanceof ShowTablesPlan) return "ShowTablesPlan";
        if (node instanceof DescribeTablePlan) return "DescribeTablePlan";
        if (node instanceof DropTablePlan) return "DropTablePlan";
        if (node instanceof JoinPlan) return "JoinPlan";
        if (node instanceof GroupByPlan) return "GroupByPlan";
        if (node instanceof OrderByPlan) return "OrderByPlan";
        return node.getClass().getSimpleName();
    }

    /** 格式化 AST 表达式 */
    private static String formatExpr(ASTNode expr) {
        if (expr == null) return "(null)";
        return expr.toString();
    }

    /** 格式化值列表 */
    private static String formatValues(List<ASTNode.LiteralExpr> values) {
        if (values == null) return "[]";
        return values.toString();
    }

    /** 格式化赋值映射 */
    private static String formatAssignments(Map<String, ASTNode.LiteralExpr> assignments) {
        if (assignments == null) return "{}";
        return assignments.toString();
    }
}
