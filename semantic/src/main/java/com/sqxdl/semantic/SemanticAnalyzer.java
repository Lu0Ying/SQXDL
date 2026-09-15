package com.sqxdl.semantic;

import com.sqxdl.parser.ASTNode;
import com.sqxdl.parser.SqxdlException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    /** 别名 → 实际表名 的映射（FROM student s / JOIN teacher t），无别名时为空 */
    private final Map<String, String> aliasToTable = new HashMap<>();

    public SemanticAnalyzer(CatalogImpl catalog) {
        this.catalog = catalog;
    }

    /**
     * 对 AST 做语义检查，发现错误时抛出带定位信息的 {@link SqxdlException}。
     *
     * @param ast 语法树根节点
     */
    public void analyze(ASTNode ast) {
        aliasToTable.clear();
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
        } else if (ast instanceof ASTNode.ShowStmt stmt) {
            analyzeShowStmt(stmt);
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
        // 收集本查询涉及的所有表：主表 + JOIN 表
        List<String> tableNames = new ArrayList<>();
        String mainTable = stmt.getTableName();
        checkTableExists(mainTable, stmt);
        tableNames.add(mainTable);

        // 注册主表别名（必须在 JOIN ON 条件解析之前注册）
        if (stmt.getTableAlias() != null) {
            aliasToTable.put(stmt.getTableAlias(), mainTable);
        }

        for (ASTNode.SelectStmt.JoinClause join : stmt.getJoins()) {
            String joinTable = join.getTableName();
            checkTableExists(joinTable, stmt);
            tableNames.add(joinTable);
            // 注册 JOIN 表别名
            if (join.getAlias() != null) {
                aliasToTable.put(join.getAlias(), joinTable);
            }
            // 检查 ON 条件（在多表上下文中解析列）
            analyzeCondition(join.getOnCond(), tableNames);
        }

        List<String> selectList = stmt.getSelectList();

        // SELECT * 展开：单表用裸名列；多表（JOIN）用 表名.列名 限定名——
        // 存储核心 join 输出的 schema 只对两侧重名列加来源前缀，重名列
        // （如 student.name / teacher.name）若按裸名投影会产生歧义，
        // 统一限定后无论是否重名都能唯一定位
        if (selectList.size() == 1 && "*".equals(selectList.get(0))) {
            List<String> allColumns = new ArrayList<>();
            boolean multiTable = tableNames.size() > 1;
            for (String t : tableNames) {
                List<String> cols = catalog.getColumns(t);
                if (cols == null) {
                    continue;
                }
                for (String c : cols) {
                    allColumns.add(multiTable ? t + "." + c : c);
                }
            }
            expandedSelectLists.put(stmt, allColumns);
        } else {
            checkSelectColumns(tableNames, selectList, stmt);
            expandedSelectLists.put(stmt, selectList);
        }

        // 检查 WHERE 条件（在多表上下文中解析列）
        analyzeCondition(stmt.getWhereCond(), tableNames);

        // 检查 GROUP BY 列存在性
        for (String col : stmt.getGroupBy()) {
            resolveColumn(col, tableNames, stmt);
        }

        // 检查 ORDER BY 列存在性
        for (ASTNode.SelectStmt.OrderItem item : stmt.getOrderBy()) {
            resolveColumn(item.getColumn(), tableNames, stmt);
        }

        // GROUP BY 语义规则检查
        checkGroupBySemantics(stmt, tableNames);
    }

    /**
     * GROUP BY 语义规则检查。
     * <p>
     * 规则：
     * <ul>
     *   <li>有 GROUP BY 时，SELECT 中的非聚合列必须出现在 GROUP BY 中</li>
     *   <li>无 GROUP BY 但 SELECT 含聚合函数时，SELECT 中不能有非聚合列</li>
     * </ul>
     */
    private void checkGroupBySemantics(ASTNode.SelectStmt stmt, List<String> tableNames) {
        List<String> selectList = expandedSelectLists.get(stmt);
        if (selectList == null) {
            return;
        }

        boolean hasAggregate = false;
        List<String> nonAggregateColumns = new ArrayList<>();
        for (String col : selectList) {
            if (AggregateFunction.isAggregate(col)) {
                hasAggregate = true;
            } else {
                nonAggregateColumns.add(col);
            }
        }

        if (stmt.getGroupBy().isEmpty()) {
            // 无 GROUP BY 但有聚合函数：SELECT 中不能有非聚合列
            if (hasAggregate && !nonAggregateColumns.isEmpty()) {
                throw error("含有聚合函数的查询中，SELECT 的非聚合列 "
                        + nonAggregateColumns + " 必须出现在 GROUP BY 中", stmt);
            }
        } else {
            // 有 GROUP BY：SELECT 中的非聚合列必须出现在 GROUP BY 中
            Set<String> groupByCols = new HashSet<>();
            for (String g : stmt.getGroupBy()) {
                // 点限定列名取纯列名
                groupByCols.add(g.contains(".") ? g.substring(g.indexOf('.') + 1) : g);
            }
            for (String col : nonAggregateColumns) {
                String bareCol = col.contains(".")
                        ? col.substring(col.indexOf('.') + 1) : col;
                if (!groupByCols.contains(bareCol)) {
                    throw error("SELECT 中的非聚合列 " + col
                            + " 必须出现在 GROUP BY 中", stmt);
                }
            }
        }
    }

    private void checkSelectColumns(String tableName, List<String> columns, ASTNode node) {
        for (String col : columns) {
            if (AggregateFunction.isAggregate(col)) {
                try {
                    AggregateFunction agg = AggregateFunction.parse(col);
                    checkAggregateArg(agg, List.of(tableName), node);
                } catch (IllegalArgumentException e) {
                    throw error(e.getMessage(), node);
                }
                continue;
            }
            if (!catalog.columnExists(tableName, col)) {
                throw error("表 " + tableName + " 中不存在列 " + col, node);
            }
        }
    }

    /**
     * 多表版本的列清单校验：列必须在至少一张表中存在；
     * 若列名在多张表中同时存在则报歧义错误。
     */
    private void checkSelectColumns(List<String> tableNames, List<String> columns, ASTNode node) {
        for (String col : columns) {
            if (AggregateFunction.isAggregate(col)) {
                try {
                    AggregateFunction agg = AggregateFunction.parse(col);
                    checkAggregateArg(agg, tableNames, node);
                } catch (IllegalArgumentException e) {
                    throw error(e.getMessage(), node);
                }
                continue;
            }
            resolveColumn(col, tableNames, node);
        }
    }

    /** 判断投影项是否为聚合函数调用 */
    private boolean isAggregate(String column) {
        return AggregateFunction.isAggregate(column);
    }

    /**
     * 校验聚合函数参数：参数列必须存在且类型合法。
     * <p>
     * 规则：
     * <ul>
     *   <li>COUNT(*)：无参数列，直接通过</li>
     *   <li>SUM/AVG：参数列必须为数值类型</li>
     *   <li>MIN/MAX：参数列可为任意类型</li>
     * </ul>
     */
    private void checkAggregateArg(AggregateFunction agg, List<String> tableNames, ASTNode node) {
        if (agg.isCountStar()) {
            return;  // COUNT(*) 不需要参数列校验
        }
        String argCol = agg.getArgument();
        String resolvedTable = resolveColumn(argCol, tableNames, node);
        // 拆分点限定列名取纯列名
        String columnName = argCol.contains(".")
                ? argCol.substring(argCol.indexOf('.') + 1) : argCol;
        CatalogImpl.DataType argType = catalog.getColumnType(resolvedTable, columnName);
        if (!agg.isArgTypeValid(argType)) {
            throw error("聚合函数 " + agg.getName() + " 的参数列 " + argCol
                    + " 类型 " + argType + " 不合法（需为数值类型）", node);
        }
    }

    /**
     * 在多表上下文中解析列名：返回列所在表名。
     * 列不存在时报错；列在多表中同时存在时报歧义错误。
     * 支持点限定标识符（table.column）：检测到 '.' 时按表名+列名直接校验，
     * 已指定表名故不参与歧义检查。
     */
    private String resolveColumn(String col, List<String> tableNames, ASTNode node) {
        // 点限定标识符：table.column 或 alias.column → 拆分后校验表与列
        int dot = col.indexOf('.');
        if (dot > 0) {
            String tablePart = col.substring(0, dot);
            String columnName = col.substring(dot + 1);
            // 先查别名映射，找不到则当作表名直接使用
            String actualTable = aliasToTable.getOrDefault(tablePart, tablePart);
            // 解析后的表名必须在当前查询涉及的表列表中
            if (!tableNames.contains(actualTable)) {
                throw error("表或别名 " + tablePart + " 不在当前查询涉及的表中", node);
            }
            if (!catalog.columnExists(actualTable, columnName)) {
                throw error("列 " + columnName + " 在表 " + actualTable + " 中不存在", node);
            }
            return actualTable;
        }

        // 普通列名：在所有表中查找，存在多张表命中时报歧义
        String found = null;
        int hit = 0;
        for (String t : tableNames) {
            if (catalog.columnExists(t, col)) {
                if (hit == 0) {
                    found = t;
                }
                hit++;
            }
        }
        if (hit == 0) {
            throw error("列 " + col + " 在查询涉及的表中均不存在", node);
        }
        if (hit > 1) {
            throw error("列 " + col + " 在多张表中存在，存在歧义，请使用表名限定", node);
        }
        return found;
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
        // 检查空表：至少需要一列
        List<ASTNode.CreateTableStmt.ColumnDef> columns = stmt.getColumns();
        if (columns == null || columns.isEmpty()) {
            throw error("建表语句至少需要定义一列", stmt);
        }
        // 检查列名重复
        Set<String> seen = new HashSet<>();
        for (ASTNode.CreateTableStmt.ColumnDef col : columns) {
            String colName = col.getName();
            if (!seen.add(colName)) {
                throw error("列名 " + colName + " 重复", stmt);
            }
        }
    }

    // ========== SHOW TABLES ==========

    private void analyzeShowStmt(ASTNode.ShowStmt stmt) {
        // SHOW TABLES 无需额外检查，直接通过
        // SHOW TABLE <表名> 需要检查表是否存在
        if ("TABLE".equals(stmt.getTarget())) {
            checkTableExists(stmt.getTableName(), stmt);
        }
    }

    // ========== DROP TABLE ==========

    private void analyzeDropTableStmt(ASTNode.DropTableStmt stmt) {
        if (!catalog.tableExists(stmt.getTableName())) {
            throw error("表 " + stmt.getTableName() + " 不存在", stmt);
        }
    }

    // ========== 条件表达式分析（递归） ==========

    /** 多表上下文版本：JOIN ON / 多表 WHERE 条件分析 */
    private void analyzeCondition(ASTNode cond, List<String> tableNames) {
        if (cond == null) {
            return;
        }
        analyzeExpr(cond, tableNames);
    }

    /** 多表上下文版本：递归分析表达式，列引用在多表中解析 */
    private CatalogImpl.DataType analyzeExpr(ASTNode expr, List<String> tableNames) {
        if (expr instanceof ASTNode.IdentifierExpr col) {
            String name = col.getName();
            String tableName = resolveColumn(name, tableNames, col);
            // 点限定标识符：拆分出纯列名再查类型
            String columnName = name.contains(".") ? name.substring(name.indexOf('.') + 1) : name;
            return catalog.getColumnType(tableName, columnName);
        } else if (expr instanceof ASTNode.LiteralExpr lit) {
            return literalType(lit);
        } else if (expr instanceof ASTNode.UnaryExpr unary) {
            analyzeExpr(unary.getOperand(), tableNames);
            return CatalogImpl.DataType.BOOLEAN;
        } else if (expr instanceof ASTNode.BinaryExpr bin) {
            return analyzeBinaryExpr(bin, tableNames);
        } else {
            throw error("不支持的表达式类型: " + expr.getClass().getSimpleName(), expr);
        }
    }

    /** 多表上下文版本：二元表达式分析 */
    private CatalogImpl.DataType analyzeBinaryExpr(ASTNode.BinaryExpr expr, List<String> tableNames) {
        String op = expr.getOp();

        if (isLogicalOp(op)) {
            CatalogImpl.DataType leftType = analyzeExpr(expr.getLeft(), tableNames);
            CatalogImpl.DataType rightType = analyzeExpr(expr.getRight(), tableNames);
            if (leftType != CatalogImpl.DataType.BOOLEAN) {
                throw error("逻辑运算符 " + op + " 左侧必须是布尔表达式，实际为 " + leftType, expr);
            }
            if (rightType != CatalogImpl.DataType.BOOLEAN) {
                throw error("逻辑运算符 " + op + " 右侧必须是布尔表达式，实际为 " + rightType, expr);
            }
            return CatalogImpl.DataType.BOOLEAN;
        }

        CatalogImpl.DataType leftType = analyzeExpr(expr.getLeft(), tableNames);
        CatalogImpl.DataType rightType = analyzeExpr(expr.getRight(), tableNames);

        if (isComparisonOp(op)) {
            if (!typeCompatible(leftType, rightType)) {
                throw error("比较运算符 " + op + " 两侧类型不兼容: "
                        + leftType + " vs " + rightType, expr);
            }
            return CatalogImpl.DataType.BOOLEAN;
        } else if (isArithmeticOp(op)) {
            if (!isNumeric(leftType) || !isNumeric(rightType)) {
                throw error("算术运算符 " + op + " 两侧都必须是数值类型（INT 或 DOUBLE）", expr);
            }
            return (leftType == CatalogImpl.DataType.DOUBLE || rightType == CatalogImpl.DataType.DOUBLE)
                    ? CatalogImpl.DataType.DOUBLE
                    : CatalogImpl.DataType.INT;
        } else {
            throw error("不支持的运算符: " + op, expr);
        }
    }

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
            if (!isNumeric(leftType) || !isNumeric(rightType)) {
                throw error("算术运算符 " + op + " 两侧都必须是数值类型（INT 或 DOUBLE）", expr);
            }
            // 任一操作数为 DOUBLE 时结果为 DOUBLE，否则为 INT
            return (leftType == CatalogImpl.DataType.DOUBLE || rightType == CatalogImpl.DataType.DOUBLE)
                    ? CatalogImpl.DataType.DOUBLE
                    : CatalogImpl.DataType.INT;
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
            case NUMBER -> lit.getValue().contains(".") || lit.getValue().contains("e") || lit.getValue().contains("E")
                    ? CatalogImpl.DataType.DOUBLE
                    : CatalogImpl.DataType.INT;
            case STRING -> CatalogImpl.DataType.VARCHAR;
            case BOOLEAN -> CatalogImpl.DataType.BOOLEAN;
        };
    }

    private boolean typeCompatible(CatalogImpl.DataType a, CatalogImpl.DataType b) {
        if (a == b) {
            return true;
        }
        // INT 与 DOUBLE 可以互相比较（数值类型兼容）
        return (a == CatalogImpl.DataType.INT || a == CatalogImpl.DataType.DOUBLE)
            && (b == CatalogImpl.DataType.INT || b == CatalogImpl.DataType.DOUBLE);
    }

    private boolean isComparisonOp(String op) {
        return op.equals("=") || op.equals("==") || op.equals("!=") || op.equals(">")
                || op.equals("<") || op.equals(">=") || op.equals("<=");
    }

    /** 逻辑运算符（Parser 已把 && / || 归一化为大写 AND / OR） */
    private boolean isLogicalOp(String op) {
        return op.equals("AND") || op.equals("OR");
    }

    /** 数值类型判断：INT 和 DOUBLE 都属于数值类型 */
    private boolean isNumeric(CatalogImpl.DataType type) {
        return type == CatalogImpl.DataType.INT || type == CatalogImpl.DataType.DOUBLE;
    }

    private boolean isArithmeticOp(String op) {
        return op.equals("+") || op.equals("-") || op.equals("*") || op.equals("/");
    }

    private SqxdlException error(String message, ASTNode node) {
        return new SqxdlException(node.getLine(), node.getCol(), message);
    }
}
