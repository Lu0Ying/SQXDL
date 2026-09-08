package com.sqxdl.semantic;

import com.sqxdl.parser.ASTNode;

import java.util.List;
import java.util.Map;

/**
 * 逻辑执行计划节点基类，是语义分析/优化器与执行器之间的接口契约。
 * 计划以树状结构组织：执行器自顶向下调用，每个节点处理其子节点 child 的输出。
 * 具体计划节点以静态内部类形式定义在本文件中。
 */
public abstract class PlanNode {

    /**
     * 顺序扫描计划：叶子节点，对 tableName 对应表做全表扫描。
     */
    public static class SeqScanPlan extends PlanNode {

        private final String tableName;

        public SeqScanPlan(String tableName) {
            this.tableName = tableName;
        }

        public String getTableName() {
            return tableName;
        }

        @Override
        public String toString() {
            return "SeqScan{table=" + tableName + "}";
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
     */
    public static class CreateTablePlan extends PlanNode {

        private final String tableName;
        private final List<String> columns;

        public CreateTablePlan(String tableName, List<String> columns) {
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
            return "CreateTable{table=" + tableName + ", columns=" + columns + "}";
        }
    }
}
