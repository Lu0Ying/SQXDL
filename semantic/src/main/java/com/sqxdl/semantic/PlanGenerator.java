package com.sqxdl.semantic;

import com.sqxdl.parser.ASTNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 执行计划生成器（B 组）。
 * 职责：将通过语义检查的 AST 转换为逻辑执行计划树（{@link PlanNode}），
 * 与执行器/存储适配层对接。
 */
public class PlanGenerator {

    /** 数据字典，用于把 SELECT * 展开为表的完整列清单 */
    private final CatalogImpl catalog;

    public PlanGenerator(CatalogImpl catalog) {
        this.catalog = catalog;
    }

    /**
     * 根据 AST 生成逻辑执行计划。
     *
     * @param ast 语法树根节点
     * @return 计划树根节点
     */
    public PlanNode generate(ASTNode ast) {
        if (ast instanceof ASTNode.SelectStmt stmt) {
            return generateSelect(stmt);
        }
        if (ast instanceof ASTNode.InsertStmt stmt) {
            return new PlanNode.InsertPlan(stmt.getTableName(), stmt.getColumns(), stmt.getValues());
        }
        if (ast instanceof ASTNode.UpdateStmt stmt) {
            return new PlanNode.UpdatePlan(stmt.getTableName(), stmt.getAssignments(), stmt.getWhereCond());
        }
        if (ast instanceof ASTNode.DeleteStmt stmt) {
            return new PlanNode.DeletePlan(stmt.getTableName(), stmt.getWhereCond());
        }
        if (ast instanceof ASTNode.CreateTableStmt stmt) {
            return new PlanNode.CreateTablePlan(stmt.getTableName(), stmt.getColumns());
        }
        throw new IllegalArgumentException("不支持的语句类型: " + ast.getClass().getSimpleName());
    }

    /**
     * 生成查询计划树：Project -> Filter(可选) -> SeqScan。
     * SELECT * 在此展开为表的完整列清单（存储核心不接收 *）。
     */
    private PlanNode generateSelect(ASTNode.SelectStmt stmt) {
        PlanNode plan = new PlanNode.SeqScanPlan(stmt.getTableName());
        if (stmt.getWhereCond() != null) {
            plan = new PlanNode.FilterPlan(stmt.getWhereCond(), plan);
        }
        return new PlanNode.ProjectPlan(expandColumns(stmt), plan);
    }

    /** 把 SELECT * 展开为建表列清单，其余原样返回 */
    private List<String> expandColumns(ASTNode.SelectStmt stmt) {
        List<String> selectList = stmt.getSelectList();
        if (selectList.size() != 1 || !"*".equals(selectList.get(0))) {
            return selectList;
        }
        List<String> allColumns = catalog.getColumns(stmt.getTableName());
        // 语义分析已保证表存在，此处防御性兜底
        return allColumns == null ? new ArrayList<>() : allColumns;
    }
}
