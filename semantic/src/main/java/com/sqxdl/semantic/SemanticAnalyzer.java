package com.sqxdl.semantic;

import com.sqxdl.parser.ASTNode;

import java.util.List;

/**
 * 语义分析器（B 组）。
 * 职责：遍历 AST，借助 {@link CatalogImpl} 校验语义合法性。
 * 校验失败时抛出带行列号定位的 IllegalArgumentException，由上层捕获后
 * 打印错误并继续下一轮 REPL，保证程序不崩溃。
 */
public class SemanticAnalyzer {

    /** 数据字典，提供表/列元数据（与 PlanGenerator 共享同一实例） */
    private final CatalogImpl catalog;

    public SemanticAnalyzer(CatalogImpl catalog) {
        this.catalog = catalog;
    }

    /**
     * 对 AST 做语义检查，发现错误时抛出带定位信息的异常。
     *
     * @param ast 语法树根节点
     */
    public void analyze(ASTNode ast) {
        if (ast instanceof ASTNode.SelectStmt stmt) {
            checkTable(stmt.getTableName(), stmt);
            checkSelectColumns(stmt);
            checkCondition(stmt.getWhereCond(), stmt.getTableName(), stmt);
        } else if (ast instanceof ASTNode.InsertStmt stmt) {
            checkTable(stmt.getTableName(), stmt);
            checkInsert(stmt);
        } else if (ast instanceof ASTNode.UpdateStmt stmt) {
            checkTable(stmt.getTableName(), stmt);
            checkColumns(stmt.getTableName(), stmt.getAssignments().keySet(), stmt);
            checkCondition(stmt.getWhereCond(), stmt.getTableName(), stmt);
        } else if (ast instanceof ASTNode.DeleteStmt stmt) {
            checkTable(stmt.getTableName(), stmt);
            checkCondition(stmt.getWhereCond(), stmt.getTableName(), stmt);
        } else if (ast instanceof ASTNode.CreateTableStmt stmt) {
            if (catalog.tableExists(stmt.getTableName())) {
                throw semanticError("表 " + stmt.getTableName() + " 已存在", stmt);
            }
        } else {
            throw semanticError("不支持的语句类型: " + ast.getClass().getSimpleName(), ast);
        }
    }

    /** 校验表存在性 */
    private void checkTable(String tableName, ASTNode node) {
        if (!catalog.tableExists(tableName)) {
            throw semanticError("表 " + tableName + " 不存在", node);
        }
    }

    /** 校验 SELECT 列清单：* 表示全部列（由计划生成阶段展开），其余列必须属于该表 */
    private void checkSelectColumns(ASTNode.SelectStmt stmt) {
        checkColumns(stmt.getTableName(), stmt.getSelectList(), stmt);
    }

    /** 校验 INSERT：列与值数量一致，且列属于该表 */
    private void checkInsert(ASTNode.InsertStmt stmt) {
        List<String> columns = stmt.getColumns();
        if (!columns.isEmpty()) {
            checkColumns(stmt.getTableName(), columns, stmt);
        }
        List<ASTNode.LiteralExpr> values = stmt.getValues();
        // 未指定列清单时，值的个数必须等于建表列数
        int expected = columns.isEmpty()
                ? catalog.getColumns(stmt.getTableName()).size()
                : columns.size();
        if (values.size() != expected) {
            throw semanticError("值的个数(" + values.size() + ")与列数(" + expected + ")不一致", stmt);
        }
    }

    /** 校验一组列名是否都属于指定表；"*" 表示全部列，跳过校验 */
    private void checkColumns(String tableName, Iterable<String> columns, ASTNode node) {
        for (String column : columns) {
            if ("*".equals(column)) {
                continue;
            }
            if (!catalog.getColumns(tableName).contains(column)) {
                throw semanticError("表 " + tableName + " 中不存在列 " + column, node);
            }
        }
    }

    /**
     * 校验条件表达式：仅支持 列 op 字面量 的二元比较，
     * 要求列属于指定表，且数字列不与字符串字面量比较。
     */
    private void checkCondition(ASTNode cond, String tableName, ASTNode node) {
        // 无 WHERE 子句时 cond 为 null，直接通过
        if (cond == null) {
            return;
        }
        if (!(cond instanceof ASTNode.BinaryExpr expr)) {
            throw semanticError("不支持的条件表达式: " + cond, cond);
        }
        if (!(expr.getLeft() instanceof ASTNode.ColumnRef column)) {
            throw semanticError("比较运算左侧必须是列名", expr.getLeft());
        }
        if (!(expr.getRight() instanceof ASTNode.LiteralExpr)) {
            throw semanticError("比较运算右侧必须是常量", expr.getRight());
        }
        checkColumns(tableName, List.of(column.getName()), node);
        // 说明：建表契约只有列名没有列类型（见 storage/readme.md），
        // 列与字面量的类型兼容性由存储核心在比较时校验（TYPE_MISMATCH 错误码）
    }

    /** 构造带行列号定位的错误信息 */
    private IllegalArgumentException semanticError(String message, ASTNode node) {
        return new IllegalArgumentException(message + " (位置 " + node.getLine() + ":" + node.getCol() + ")");
    }
}
