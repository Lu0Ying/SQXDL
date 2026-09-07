package com.sqxdl.core.semantic;

import com.sqxdl.core.parser.ASTNode;

import java.util.List;

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
}
