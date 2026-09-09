package com.sqxdl.semantic;

import com.sqxdl.parser.ASTNode;
import com.sqxdl.parser.SqxdlException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 语义分析器（B 组）。
 * 职责：遍历 AST，借助 {@link CatalogImpl} 校验语义合法性。
 * 校验失败时抛出 {@link SqxdlException}，由上层捕获后
 * 打印错误并继续下一轮 REPL，保证程序不崩溃。
 *
 * 语义分析阶段完成的工作：
 *   1. 表存在性检查
 *   2. 列存在性检查
 *   3. 类型兼容性检查
 *   4. SELECT * 展开为具体列名（通过 getExpandedColumns 获取）
 */
public class SemanticAnalyzer {

    private final CatalogImpl catalog;
    private final Map<ASTNode.SelectStmt, List<String>> expandedSelectLists = new HashMap<>();

    public SemanticAnalyzer(CatalogImpl catalog) {
        this.catalog = catalog;
    }

    /**
     * 对 AST 做语义检查，发现错误时抛出带定位信息的 {@link SqxdlException}。
     *
     * @param ast 语法树根节点
     */
    public void analyze(ASTNode ast) {
        if (ast instanceof ASTNode.SelectStmt stmt) {
            analyzeSelectStmt(stmt);
        } else if (ast instanceof ASTNode.InsertStmt stmt) {
            analyzeInsertStmt(stmt);
        } else if (ast instanceof ASTNode.UpdateStmt stmt) {
            analyzeUpdateStmt(stmt);
        } else if (ast instanceof ASTNode.DeleteStmt stmt) {
            analyzeDeleteStmt(stmt);
        } else if (ast instanceof ASTNode.CreateTableStmt stmt) {
            analyzeCreateTableStmt(stmt);
        } else if (ast instanceof ASTNode.ShowTablesStmt stmt) {
            analyzeShowTablesStmt(stmt);
        } else if (ast instanceof ASTNode.DropTableStmt stmt) {
            analyzeDropTableStmt(stmt);
        } else {
            throw error("不支持的语句类型: " + ast.getClass().getSimpleName(), ast);
        }
    }

    /**
     * 获取 SELECT * 展开后的列名清单。
     * 必须在 analyze() 之后调用。
     *
     * @param stmt SELECT 语句节点
     * @return 展开后的列名列表
     */
    public List<String> getExpandedColumns(ASTNode.SelectStmt stmt) {
        return expandedSelectLists.get(stmt);
    }

    // ========== SELECT ==========

    private void analyzeSelectStmt(ASTNode.SelectStmt stmt) {
        String tableName = stmt.getTableName();
        checkTableExists(tableName, stmt);

        List<String> selectList = stmt.getSelectList();

        // SELECT * 展开
        if (selectList.size() == 1 && "*".equals(selectList.get(0))) {
            List<String> allColumns = catalog.getColumns(tableName);
            expandedSelectLists.put(stmt, allColumns);
        } else {
            checkSelectColumns(tableName, selectList, stmt);
            expandedSelectLists.put(stmt, selectList);
        }

        // 检查 WHERE 条件
        analyzeCondition(stmt.getWhereCond(), tableName);
    }

    private void checkSelectColumns(String tableName, List<String> columns, ASTNode node) {
        for (String col : columns) {
            if (!catalog.columnExists(tableName, col)) {
                throw error("表 " + tableName + " 中不存在列 " + col, node);
            }
        }
    }

    // ========== INSERT ==========

    private void analyzeInsertStmt(ASTNode.InsertStmt stmt) {
        String tableName = stmt.getTableName();
        checkTableExists(tableName, stmt);

        List<String> columns = stmt.getColumns();
        List<ASTNode.LiteralExpr> values = stmt.getValues();

        int expectedCount;
        if (columns.isEmpty()) {
            expectedCount = catalog.getColumns(tableName).size();
        } else {
            for (String col : columns) {
                if (!catalog.columnExists(tableName, col)) {
                    throw error("表 " + tableName + " 中不存在列 " + col, stmt);
                }
            }
            expectedCount = columns.size();
        }

        if (values.size() != expectedCount) {
            throw error("值的个数(" + values.size() + ")与列数(" + expectedCount + ")不一致", stmt);
        }

        // 类型检查：每个值的类型要和对应列的类型兼容
        List<String> targetColumns = columns.isEmpty()
                ? catalog.getColumns(tableName)
                : columns;
        for (int i = 0; i < values.size(); i++) {
            ASTNode.LiteralExpr value = values.get(i);
            CatalogImpl.DataType colType = catalog.getColumnType(tableName, targetColumns.get(i));
            CatalogImpl.DataType valType = literalType(value);
            if (!typeCompatible(colType, valType)) {
                throw error("第 " + (i + 1) + " 个值的类型与列 " + targetColumns.get(i)
                        + " 的类型不兼容", value);
            }
        }
    }

    // ========== UPDATE ==========

    private void analyzeUpdateStmt(ASTNode.UpdateStmt stmt) {
        String tableName = stmt.getTableName();
        checkTableExists(tableName, stmt);

        Map<String, ASTNode.LiteralExpr> assignments = stmt.getAssignments();
        for (Map.Entry<String, ASTNode.LiteralExpr> entry : assignments.entrySet()) {
            String colName = entry.getKey();
            ASTNode.LiteralExpr value = entry.getValue();
            if (!catalog.columnExists(tableName, colName)) {
                throw error("表 " + tableName + " 中不存在列 " + colName, stmt);
            }
            CatalogImpl.DataType colType = catalog.getColumnType(tableName, colName);
            CatalogImpl.DataType valType = literalType(value);
            if (!typeCompatible(colType, valType)) {
                throw error("列 " + colName + " 的类型与值的类型不兼容", value);
            }
        }

        analyzeCondition(stmt.getWhereCond(), tableName);
    }

    // ========== DELETE ==========

    private void analyzeDeleteStmt(ASTNode.DeleteStmt stmt) {
        String tableName = stmt.getTableName();
        checkTableExists(tableName, stmt);
        analyzeCondition(stmt.getWhereCond(), tableName);
    }

    // ========== CREATE TABLE ==========

    private void analyzeCreateTableStmt(ASTNode.CreateTableStmt stmt) {
        if (catalog.tableExists(stmt.getTableName())) {
            throw error("表 " + stmt.getTableName() + " 已存在", stmt);
        }
    }

    // ========== SHOW TABLES ==========

    private void analyzeShowTablesStmt(ASTNode.ShowTablesStmt stmt) {
        // SHOW TABLES 无需额外检查，直接通过
    }

    // ========== DROP TABLE ==========

    private void analyzeDropTableStmt(ASTNode.DropTableStmt stmt) {
        if (!catalog.tableExists(stmt.getTableName())) {
            throw error("表 " + stmt.getTableName() + " 不存在", stmt);
        }
    }

    // ========== 条件表达式分析（递归） ==========

    private void analyzeCondition(ASTNode cond, String tableName) {
        if (cond == null) {
            return;
        }
        CatalogImpl.DataType resultType = analyzeExpr(cond, tableName);
        if (resultType != CatalogImpl.DataType.VARCHAR) {
            // 简化：条件表达式结果类型我们暂不严格要求 BOOLEAN，
            // 因为我们类型系统比较简单，只要能比较就行
        }
    }

    /**
     * 分析表达式，返回其推断类型。
     * 递归访问表达式树的所有节点。
     */
    private CatalogImpl.DataType analyzeExpr(ASTNode expr, String tableName) {
        if (expr instanceof ASTNode.IdentifierExpr col) {
            return analyzeIdentifier(col, tableName);
        } else if (expr instanceof ASTNode.LiteralExpr lit) {
            return literalType(lit);
        } else if (expr instanceof ASTNode.UnaryExpr unary) {
            // NOT 操作数递归分析（校验其中列的存在性），结果视为布尔
            analyzeExpr(unary.getOperand(), tableName);
            return CatalogImpl.DataType.BOOLEAN;
        } else if (expr instanceof ASTNode.BinaryExpr bin) {
            return analyzeBinaryExpr(bin, tableName);
        } else {
            throw error("不支持的表达式类型: " + expr.getClass().getSimpleName(), expr);
        }
    }

    private CatalogImpl.DataType analyzeIdentifier(ASTNode.IdentifierExpr col, String tableName) {
        String colName = col.getName();
        if (!catalog.columnExists(tableName, colName)) {
            throw error("表 " + tableName + " 中不存在列 " + colName, col);
        }
        return catalog.getColumnType(tableName, colName);
    }

    private CatalogImpl.DataType analyzeBinaryExpr(ASTNode.BinaryExpr expr, String tableName) {
        String op = expr.getOp();

        // 逻辑运算符 AND/OR：两侧必须都是 BOOLEAN，结果也是 BOOLEAN
        if (isLogicalOp(op)) {
            CatalogImpl.DataType leftType = analyzeExpr(expr.getLeft(), tableName);
            CatalogImpl.DataType rightType = analyzeExpr(expr.getRight(), tableName);
            // 比较运算的结果是 BOOLEAN，可以直接用 AND/OR 连接
            // BOOLEAN 字面量（true/false）也可以
            if (leftType != CatalogImpl.DataType.BOOLEAN) {
                throw error("逻辑运算符 " + op + " 左侧必须是布尔表达式，实际为 " + leftType, expr);
            }
            if (rightType != CatalogImpl.DataType.BOOLEAN) {
                throw error("逻辑运算符 " + op + " 右侧必须是布尔表达式，实际为 " + rightType, expr);
            }
            return CatalogImpl.DataType.BOOLEAN;
        }

        CatalogImpl.DataType leftType = analyzeExpr(expr.getLeft(), tableName);
        CatalogImpl.DataType rightType = analyzeExpr(expr.getRight(), tableName);

        // 逻辑运算：操作数递归分析即可，结果视为布尔（类型系统暂不要求操作数为 BOOLEAN）
        if (isLogicalOp(op)) {
            return CatalogImpl.DataType.BOOLEAN;
        } else if (isComparisonOp(op)) {
            if (!typeCompatible(leftType, rightType)) {
                throw error("比较运算符 " + op + " 两侧类型不兼容: "
                        + leftType + " vs " + rightType, expr);
            }
            // 比较运算的结果是 BOOLEAN
            return CatalogImpl.DataType.BOOLEAN;
        } else if (isArithmeticOp(op)) {
            if (leftType != CatalogImpl.DataType.INT || rightType != CatalogImpl.DataType.INT) {
                throw error("算术运算符 " + op + " 两侧都必须是整数类型", expr);
            }
            return CatalogImpl.DataType.INT;
        } else {
            throw error("不支持的运算符: " + op, expr);
        }
    }

    // ========== 辅助方法 ==========

    private void checkTableExists(String tableName, ASTNode node) {
        if (!catalog.tableExists(tableName)) {
            throw error("表 " + tableName + " 不存在", node);
        }
    }

    private CatalogImpl.DataType literalType(ASTNode.LiteralExpr lit) {
        return switch (lit.getKind()) {
            case NUMBER -> CatalogImpl.DataType.INT;
            case STRING -> CatalogImpl.DataType.VARCHAR;
            case BOOLEAN -> CatalogImpl.DataType.BOOLEAN;
        };
    }

    private boolean typeCompatible(CatalogImpl.DataType a, CatalogImpl.DataType b) {
        return a == b;
    }

    private boolean isComparisonOp(String op) {
        return op.equals("=") || op.equals("==") || op.equals("!=") || op.equals(">")
                || op.equals("<") || op.equals(">=") || op.equals("<=");
    }

    /** 逻辑运算符（Parser 已把 && / || 归一化为大写 AND / OR） */
    private boolean isLogicalOp(String op) {
        return op.equals("AND") || op.equals("OR");
    }

    private boolean isArithmeticOp(String op) {
        return op.equals("+") || op.equals("-") || op.equals("*") || op.equals("/");
    }

    private SqxdlException error(String message, ASTNode node) {
        return new SqxdlException(node.getLine(), node.getCol(), message);
    }
}
