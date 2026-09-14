package com.sqxdl.semantic;

import com.sqxdl.parser.ASTNode;
import com.sqxdl.parser.ASTNode.LiteralExpr;
import com.sqxdl.parser.ASTNode.LiteralExpr.Kind;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 执行计划生成器（B 组）。
 * 职责：将通过语义检查的 AST 转换为逻辑执行计划树（{@link PlanNode}），
 * 与执行器/存储适配层对接。
 *
 * 本类在生成计划时对 WHERE 条件做两层优化：
 *   1. 常量折叠：两边都是字面量时直接计算，如 2+3→5、1=1→TRUE
 *   2. 表达式化简：利用恒等律/零元/单位元简化，如 cond AND TRUE→cond
 * 恒真条件 → 跳过 Filter；恒假条件 → 保留 Filter(FALSE) 让执行器返回空集。
 */
public class PlanGenerator {

    private final CatalogImpl catalog;
    private SemanticAnalyzer analyzer;

    public PlanGenerator(CatalogImpl catalog) {
        this.catalog = catalog;
    }

    /**
     * 设置语义分析器，用于获取 SELECT * 展开后的列清单。
     * 设置后 PlanGenerator 会优先使用语义分析阶段已展开的结果，
     * 避免重复展开。不设置时退化为自行展开。
     *
     * @param analyzer 语义分析器实例
     */
    public void setAnalyzer(SemanticAnalyzer analyzer) {
        this.analyzer = analyzer;
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
            return new PlanNode.UpdatePlan(stmt.getTableName(), stmt.getAssignments(),
                    optimizeCondition(stmt.getWhereCond()));
        }
        if (ast instanceof ASTNode.DeleteStmt stmt) {
            return new PlanNode.DeletePlan(stmt.getTableName(),
                    optimizeCondition(stmt.getWhereCond()));
        }
        if (ast instanceof ASTNode.CreateTableStmt stmt) {
            // 列名清单 + 带类型的列定义（存储核心建表协议已升级为对象数组）
            List<String> columnNames = new ArrayList<>();
            List<CatalogImpl.ColumnInfo> columnDefs = new ArrayList<>();
            for (ASTNode.CreateTableStmt.ColumnDef def : stmt.getColumns()) {
                columnNames.add(def.getName());
                columnDefs.add(new CatalogImpl.ColumnInfo(def.getName(), toDataType(def.getType())));
            }
            return new PlanNode.CreateTablePlan(stmt.getTableName(), columnNames, columnDefs);
        }
        if (ast instanceof ASTNode.ShowStmt stmt) {
            if ("TABLE".equals(stmt.getTarget())) {
                return new PlanNode.DescribeTablePlan(stmt.getTableName());
            }
            return new PlanNode.ShowTablesPlan();
        }
        if (ast instanceof ASTNode.DropTableStmt stmt) {
            return new PlanNode.DropTablePlan(stmt.getTableName());
        }
        throw new IllegalArgumentException("不支持的语句类型: " + ast.getClass().getSimpleName());
    }

    /** 建表类型字符串 -> 数据字典类型（INT/DOUBLE/BOOLEAN 之外的类型按 VARCHAR 处理） */
    private CatalogImpl.DataType toDataType(String typeName) {
        if ("INT".equalsIgnoreCase(typeName)) {
            return CatalogImpl.DataType.INT;
        }
        if ("DOUBLE".equalsIgnoreCase(typeName)) {
            return CatalogImpl.DataType.DOUBLE;
        }
        if ("BOOLEAN".equalsIgnoreCase(typeName)) {
            return CatalogImpl.DataType.BOOLEAN;
        }
        return CatalogImpl.DataType.VARCHAR;
    }

    // ========== SELECT 计划生成 ==========

    /**
     * 生成查询计划树：
     *   Project → OrderBy(可选) → GroupBy(可选) → Filter(可选) → Join/SeqScan
     *
     * 优化：
     *   1. 谓词下推：WHERE 条件拆分为合取项，单表谓词下推到 SeqScan 之上
     *   2. 列裁剪/投影下推：计算每张表实际需要的列，SeqScan 只读取必要列
     * WHERE 条件经过优化后，恒真则跳过 Filter。
     */
    private PlanNode generateSelect(ASTNode.SelectStmt stmt) {
        List<ASTNode.SelectStmt.JoinClause> joins = stmt.getJoins();

        // === 收集查询涉及的所有表名 ===
        List<String> tableNames = new ArrayList<>();
        tableNames.add(stmt.getTableName());
        for (ASTNode.SelectStmt.JoinClause join : joins) {
            tableNames.add(join.getTableName());
        }

        // === 谓词下推：拆分 WHERE 为合取项 ===
        List<ASTNode> conjuncts = splitConjuncts(optimizeCondition(stmt.getWhereCond()));
        // 单表谓词按表分组（可下推）；跨表谓词留在 Join 之上
        Map<String, List<ASTNode>> perTableFilters = new HashMap<>();
        List<ASTNode> joinFilters = new ArrayList<>();
        for (ASTNode conjunct : conjuncts) {
            Set<String> tables = extractTableRefs(conjunct, tableNames);
            if (tables.size() == 1) {
                perTableFilters.computeIfAbsent(tables.iterator().next(),
                        k -> new ArrayList<>()).add(conjunct);
            } else if (tables.isEmpty() && tableNames.size() == 1) {
                // 常量谓词（如 FALSE、1>2）：单表查询时下推到唯一表
                perTableFilters.computeIfAbsent(tableNames.get(0),
                        k -> new ArrayList<>()).add(conjunct);
            } else {
                joinFilters.add(conjunct);
            }
        }

        // === 列裁剪：计算每张表实际需要的列 ===
        Map<String, Set<String>> neededColumns = computeNeededColumns(stmt, tableNames);

        // === 构建数据源 ===
        PlanNode plan;
        if (joins.isEmpty()) {
            // 单表查询：SeqScan + 下推谓词
            String mainTable = stmt.getTableName();
            plan = buildScanWithPushdown(mainTable, neededColumns, perTableFilters);
        } else {
            // 多表 JOIN：每个子节点是带下推谓词的 SeqScan
            List<PlanNode> children = new ArrayList<>();
            List<ASTNode> onConditions = new ArrayList<>();
            for (int i = 0; i < tableNames.size(); i++) {
                String table = tableNames.get(i);
                children.add(buildScanWithPushdown(table, neededColumns, perTableFilters));
                // 主表 onCondition 约定为 null
                onConditions.add(i == 0 ? null : optimizeCondition(joins.get(i - 1).getOnCond()));
            }
            plan = new PlanNode.JoinPlan(children, onConditions);

            // 跨表谓词留在 Join 之上
            if (!joinFilters.isEmpty()) {
                ASTNode remaining = combineWithAnd(joinFilters);
                plan = new PlanNode.FilterPlan(remaining, plan);
            }
        }

        // === 叠加 GROUP BY（可选）===
        if (!stmt.getGroupBy().isEmpty() || hasAggregate(stmt)) {
            plan = new PlanNode.GroupByPlan(new ArrayList<>(stmt.getGroupBy()), plan);
        }

        // === 叠加 ORDER BY（可选）===
        if (!stmt.getOrderBy().isEmpty()) {
            List<PlanNode.OrderByPlan.OrderItem> items = new ArrayList<>();
            for (ASTNode.SelectStmt.OrderItem item : stmt.getOrderBy()) {
                items.add(new PlanNode.OrderByPlan.OrderItem(item.getColumn(), item.getDirection()));
            }
            plan = new PlanNode.OrderByPlan(items, plan);
        }

        // === 叠加投影 ===
        return new PlanNode.ProjectPlan(expandColumns(stmt), plan);
    }

    // ========== 谓词下推辅助方法 ==========

    /**
     * 拆分 AND 连接的合取项为列表。非 AND 表达式返回单元素列表。
     * 如 a AND (b AND c) → [a, b, c]
     */
    private List<ASTNode> splitConjuncts(ASTNode cond) {
        List<ASTNode> result = new ArrayList<>();
        if (cond == null) {
            return result;
        }
        if (cond instanceof ASTNode.BinaryExpr bin && "AND".equals(bin.getOp())) {
            result.addAll(splitConjuncts(bin.getLeft()));
            result.addAll(splitConjuncts(bin.getRight()));
        } else {
            result.add(cond);
        }
        return result;
    }

    /**
     * 递归提取表达式引用的表名集合。
     * 点限定标识符（table.column）直接取表名；普通列名查 catalog 找所属表。
     */
    private Set<String> extractTableRefs(ASTNode expr, List<String> allTables) {
        Set<String> tables = new HashSet<>();
        if (expr instanceof ASTNode.IdentifierExpr col) {
            String name = col.getName();
            int dot = name.indexOf('.');
            if (dot > 0) {
                tables.add(name.substring(0, dot));
            } else {
                for (String t : allTables) {
                    if (catalog.columnExists(t, name)) {
                        tables.add(t);
                    }
                }
            }
        } else if (expr instanceof ASTNode.BinaryExpr bin) {
            tables.addAll(extractTableRefs(bin.getLeft(), allTables));
            tables.addAll(extractTableRefs(bin.getRight(), allTables));
        } else if (expr instanceof ASTNode.UnaryExpr un) {
            tables.addAll(extractTableRefs(un.getOperand(), allTables));
        }
        return tables;
    }

    /**
     * 用 AND 合并多个表达式为单个表达式树。列表为空返回 null。
     */
    private ASTNode combineWithAnd(List<ASTNode> exprs) {
        if (exprs.isEmpty()) {
            return null;
        }
        ASTNode result = exprs.get(0);
        for (int i = 1; i < exprs.size(); i++) {
            result = new ASTNode.BinaryExpr(result.getLine(), result.getCol(),
                    "AND", result, exprs.get(i));
        }
        return result;
    }

    /**
     * 构建带谓词下推的 SeqScan：如果有该表的单表谓词，在 SeqScan 之上叠加 Filter。
     */
    private PlanNode buildScanWithPushdown(String table,
                                           Map<String, Set<String>> neededColumns,
                                           Map<String, List<ASTNode>> perTableFilters) {
        // 列裁剪：只扫描实际需要的列
        List<String> scanCols = null;
        Set<String> cols = neededColumns.get(table);
        if (cols != null && !cols.isEmpty()) {
            List<String> allCols = catalog.getColumns(table);
            if (allCols != null && !cols.containsAll(allCols)) {
                scanCols = new ArrayList<>(cols);
            }
        }
        PlanNode scan = new PlanNode.SeqScanPlan(table, scanCols);

        // 谓词下推：在该表上叠加单表谓词
        List<ASTNode> filters = perTableFilters.get(table);
        if (filters != null && !filters.isEmpty()) {
            ASTNode combined = combineWithAnd(filters);
            return new PlanNode.FilterPlan(combined, scan);
        }
        return scan;
    }

    // ========== 列裁剪辅助方法 ==========

    /**
     * 计算每张表实际需要的列集（自顶向下收集所有引用的列）。
     * 列来源：SELECT 清单、WHERE 条件、JOIN ON 条件、GROUP BY、ORDER BY。
     */
    private Map<String, Set<String>> computeNeededColumns(ASTNode.SelectStmt stmt,
                                                          List<String> tableNames) {
        Map<String, Set<String>> result = new HashMap<>();
        for (String t : tableNames) {
            result.put(t, new HashSet<>());
        }

        // SELECT * 需要所有表的所有列
        List<String> selectList = stmt.getSelectList();
        if (selectList.size() == 1 && "*".equals(selectList.get(0))) {
            for (String t : tableNames) {
                List<String> cols = catalog.getColumns(t);
                if (cols != null) {
                    result.get(t).addAll(cols);
                }
            }
            return result;
        }

        // SELECT 清单中的列
        for (String col : selectList) {
            if (isAggregate(col)) {
                // COUNT(*) 需要所有表的列（执行器要数行数）
                for (String t : tableNames) {
                    List<String> cols = catalog.getColumns(t);
                    if (cols != null) {
                        result.get(t).addAll(cols);
                    }
                }
            } else {
                addColumnRef(col, tableNames, result);
            }
        }

        // WHERE 条件中的列
        collectColumnRefs(stmt.getWhereCond(), tableNames, result);

        // JOIN ON 条件中的列
        for (ASTNode.SelectStmt.JoinClause join : stmt.getJoins()) {
            collectColumnRefs(join.getOnCond(), tableNames, result);
        }

        // GROUP BY 列
        for (String col : stmt.getGroupBy()) {
            addColumnRef(col, tableNames, result);
        }

        // ORDER BY 列
        for (ASTNode.SelectStmt.OrderItem item : stmt.getOrderBy()) {
            addColumnRef(item.getColumn(), tableNames, result);
        }

        return result;
    }

    /** 将单个列名（可能点限定）加入对应表的需求集 */
    private void addColumnRef(String col, List<String> tableNames,
                              Map<String, Set<String>> result) {
        int dot = col.indexOf('.');
        if (dot > 0) {
            String table = col.substring(0, dot);
            String column = col.substring(dot + 1);
            if (result.containsKey(table)) {
                result.get(table).add(column);
            }
        } else {
            for (String t : tableNames) {
                if (catalog.columnExists(t, col)) {
                    result.get(t).add(col);
                }
            }
        }
    }

    /** 递归从表达式中收集所有列引用 */
    private void collectColumnRefs(ASTNode expr, List<String> tableNames,
                                   Map<String, Set<String>> result) {
        if (expr == null) {
            return;
        }
        if (expr instanceof ASTNode.IdentifierExpr col) {
            addColumnRef(col.getName(), tableNames, result);
        } else if (expr instanceof ASTNode.BinaryExpr bin) {
            collectColumnRefs(bin.getLeft(), tableNames, result);
            collectColumnRefs(bin.getRight(), tableNames, result);
        } else if (expr instanceof ASTNode.UnaryExpr un) {
            collectColumnRefs(un.getOperand(), tableNames, result);
        }
    }

    /**
     * 获取 SELECT 列清单：优先使用语义分析阶段已展开的结果，
     * 否则自行展开 SELECT *。
     */
    private List<String> expandColumns(ASTNode.SelectStmt stmt) {
        // 优先使用语义分析器展开后的列清单
        if (analyzer != null) {
            List<String> expanded = analyzer.getExpandedColumns(stmt);
            if (expanded != null) {
                return expanded;
            }
        }
        // 退化为自行展开
        List<String> selectList = stmt.getSelectList();
        if (selectList.size() != 1 || !"*".equals(selectList.get(0))) {
            return selectList;
        }
        List<String> allColumns = catalog.getColumns(stmt.getTableName());
        return allColumns == null ? new ArrayList<>() : allColumns;
    }

    /** 投影清单是否含聚合项（如 COUNT(*)）；SELECT * 展开结果不含聚合 */
    private boolean hasAggregate(ASTNode.SelectStmt stmt) {
        for (String column : stmt.getSelectList()) {
            if (isAggregate(column)) {
                return true;
            }
        }
        return false;
    }

    /** 判断投影项是否为聚合函数调用（Parser 归一化为 "COUNT(*)" 形式） */
    private boolean isAggregate(String column) {
        return "COUNT(*)".equalsIgnoreCase(column);
    }

    // ========== 条件优化入口 ==========

    /**
     * 优化 WHERE 条件表达式。
     * - 输入 null（无 WHERE）→ 返回 null
     * - 优化后为恒真 → 返回 null（表示无需 Filter）
     * - 优化后为其他 → 返回优化后的表达式
     */
    private ASTNode optimizeCondition(ASTNode cond) {
        if (cond == null) {
            return null;
        }
        ASTNode optimized = optimizeExpr(cond);
        if (isTrueLiteral(optimized)) {
            return null;
        }
        return optimized;
    }

    // ========== 表达式优化（递归） ==========

    /**
     * 递归优化表达式：先处理子节点，再做常量折叠和表达式化简。
     */
    private ASTNode optimizeExpr(ASTNode expr) {
        if (expr instanceof LiteralExpr || expr instanceof ASTNode.IdentifierExpr) {
            return expr;
        }
        if (expr instanceof ASTNode.UnaryExpr un) {
            // 1. 递归优化操作数（先折叠内部的常量运算，如 NOT (1+2>3) 内的 1+2）
            ASTNode operand = optimizeExpr(un.getOperand());
            String op = un.getOp();

            // 2. 常量折叠：NOT TRUE → FALSE, NOT FALSE → TRUE
            if (op.equals("NOT") && operand instanceof LiteralExpr lit
                    && lit.getKind() == Kind.BOOLEAN) {
                boolean val = Boolean.parseBoolean(lit.getValue());
                return new LiteralExpr(un.getLine(), un.getCol(),
                        String.valueOf(!val), Kind.BOOLEAN);
            }

            // 3. 双重否定消除：NOT NOT x → x
            if (op.equals("NOT") && operand instanceof ASTNode.UnaryExpr inner
                    && inner.getOp().equals("NOT")) {
                return inner.getOperand();
            }

            // 4. 无变化 → 返回原节点，避免创建多余对象
            if (operand == un.getOperand()) {
                return un;
            }
            return new ASTNode.UnaryExpr(un.getLine(), un.getCol(), op, operand);
        }
        if (!(expr instanceof ASTNode.BinaryExpr bin)) {
            return expr;
        }

        // 先递归优化子表达式
        ASTNode left = optimizeExpr(bin.getLeft());
        ASTNode right = optimizeExpr(bin.getRight());
        String op = bin.getOp();

        // 1. 常量折叠：两边都是字面量 → 直接计算
        if (left instanceof LiteralExpr && right instanceof LiteralExpr) {
            LiteralExpr folded = foldConstants(op, (LiteralExpr) left, (LiteralExpr) right, bin);
            if (folded != null) {
                return folded;
            }
        }

        // 2. 逻辑表达式化简（AND/OR 与常量的短路）
        ASTNode simplified = simplifyLogical(op, left, right, bin);
        if (simplified != null) {
            return simplified;
        }

        // 3. 算术恒等式化简（x+0→x, x*1→x 等）
        simplified = simplifyArithmetic(op, left, right, bin);
        if (simplified != null) {
            return simplified;
        }

        // 无变化 → 返回原节点，避免创建多余对象
        if (left == bin.getLeft() && right == bin.getRight()) {
            return bin;
        }
        return new ASTNode.BinaryExpr(bin.getLine(), bin.getCol(), op, left, right);
    }

    // ========== 常量折叠 ==========

    /**
     * 对两个字面量做常量折叠，返回结果字面量；无法折叠时返回 null。
     */
    private LiteralExpr foldConstants(String op, LiteralExpr left, LiteralExpr right, ASTNode node) {
        // 算术运算：两边必须都是数字
        if (isArithmeticOp(op)) {
            if (left.getKind() != Kind.NUMBER || right.getKind() != Kind.NUMBER) {
                return null;
            }
            // 任一操作数为小数则按 DOUBLE 运算，结果保留小数格式；双整数保持整数运算
            boolean decimal = left.getValue().contains(".") || right.getValue().contains(".");
            if (decimal) {
                double l = Double.parseDouble(left.getValue());
                double r = Double.parseDouble(right.getValue());
                double result;
                switch (op) {
                    case "+": result = l + r; break;
                    case "-": result = l - r; break;
                    case "*": result = l * r; break;
                    case "/":
                        if (r == 0) return null; // 除以零不折叠，留给运行时处理
                        result = l / r;
                        break;
                    default: return null;
                }
                return new LiteralExpr(node.getLine(), node.getCol(), String.valueOf(result), Kind.NUMBER);
            }
            int l = Integer.parseInt(left.getValue());
            int r = Integer.parseInt(right.getValue());
            int result;
            switch (op) {
                case "+": result = l + r; break;
                case "-": result = l - r; break;
                case "*": result = l * r; break;
                case "/":
                    if (r == 0) return null; // 除以零不折叠，留给运行时处理
                    result = l / r;
                    break;
                default: return null;
            }
            return new LiteralExpr(node.getLine(), node.getCol(), String.valueOf(result), Kind.NUMBER);
        }

        // 比较运算：两边必须同类型；数值用 Double 比较，同时兼容整数与小数
        if (isComparisonOp(op)) {
            if (left.getKind() != right.getKind()) {
                return null;
            }
            int cmp;
            if (left.getKind() == Kind.NUMBER) {
                cmp = Double.compare(Double.parseDouble(left.getValue()),
                        Double.parseDouble(right.getValue()));
            } else {
                cmp = left.getValue().compareTo(right.getValue());
            }
            boolean result;
            switch (op) {
                case "=":  result = (cmp == 0); break;
                case "!=": result = (cmp != 0); break;
                case ">":  result = (cmp > 0);  break;
                case "<":  result = (cmp < 0);  break;
                case ">=": result = (cmp >= 0); break;
                case "<=": result = (cmp <= 0); break;
                default: return null;
            }
            return new LiteralExpr(node.getLine(), node.getCol(), String.valueOf(result), Kind.BOOLEAN);
        }

        // 逻辑运算：两边必须都是布尔
        if (op.equals("AND") || op.equals("OR")) {
            if (left.getKind() != Kind.BOOLEAN || right.getKind() != Kind.BOOLEAN) {
                return null;
            }
            boolean l = Boolean.parseBoolean(left.getValue());
            boolean r = Boolean.parseBoolean(right.getValue());
            boolean result = op.equals("AND") ? (l && r) : (l || r);
            return new LiteralExpr(node.getLine(), node.getCol(), String.valueOf(result), Kind.BOOLEAN);
        }

        return null;
    }

    // ========== 逻辑表达式化简 ==========

    /**
     * 利用逻辑恒等律化简 AND/OR 表达式，无法化简时返回 null。
     */
    private ASTNode simplifyLogical(String op, ASTNode left, ASTNode right, ASTNode node) {
        if (op.equals("AND")) {
            // cond AND TRUE → cond
            if (isTrueLiteral(right)) return left;
            // TRUE AND cond → cond
            if (isTrueLiteral(left)) return right;
            // cond AND FALSE → FALSE, FALSE AND cond → FALSE
            if (isFalseLiteral(left) || isFalseLiteral(right)) {
                return new LiteralExpr(node.getLine(), node.getCol(), "false", Kind.BOOLEAN);
            }
        }
        if (op.equals("OR")) {
            // cond OR FALSE → cond
            if (isFalseLiteral(right)) return left;
            // FALSE OR cond → cond
            if (isFalseLiteral(left)) return right;
            // cond OR TRUE → TRUE, TRUE OR cond → TRUE
            if (isTrueLiteral(left) || isTrueLiteral(right)) {
                return new LiteralExpr(node.getLine(), node.getCol(), "true", Kind.BOOLEAN);
            }
        }
        return null;
    }

    // ========== 算术恒等式化简 ==========

    /**
     * 利用算术单位元化简，如 x+0→x、x*1→x、x-0→x、x/1→x。无法化简时返回 null。
     */
    private ASTNode simplifyArithmetic(String op, ASTNode left, ASTNode right, ASTNode node) {
        // x + 0 → x, 0 + x → x
        if (op.equals("+")) {
            if (isZeroLiteral(right)) return left;
            if (isZeroLiteral(left)) return right;
        }
        // x - 0 → x
        if (op.equals("-")) {
            if (isZeroLiteral(right)) return left;
        }
        // x * 1 → x, 1 * x → x
        if (op.equals("*")) {
            if (isOneLiteral(right)) return left;
            if (isOneLiteral(left)) return right;
        }
        // x / 1 → x
        if (op.equals("/")) {
            if (isOneLiteral(right)) return left;
        }
        return null;
    }

    // ========== 辅助判断方法 ==========

    private boolean isTrueLiteral(ASTNode node) {
        return node instanceof LiteralExpr lit
                && lit.getKind() == Kind.BOOLEAN
                && Boolean.parseBoolean(lit.getValue());
    }

    private boolean isFalseLiteral(ASTNode node) {
        return node instanceof LiteralExpr lit
                && lit.getKind() == Kind.BOOLEAN
                && !Boolean.parseBoolean(lit.getValue());
    }

    private boolean isZeroLiteral(ASTNode node) {
        return node instanceof LiteralExpr lit
                && lit.getKind() == Kind.NUMBER
                && Integer.parseInt(lit.getValue()) == 0;
    }

    private boolean isOneLiteral(ASTNode node) {
        return node instanceof LiteralExpr lit
                && lit.getKind() == Kind.NUMBER
                && Integer.parseInt(lit.getValue()) == 1;
    }

    private boolean isComparisonOp(String op) {
        return op.equals("=") || op.equals("==") || op.equals("!=") || op.equals(">")
                || op.equals("<") || op.equals(">=") || op.equals("<=");
    }

    private boolean isArithmeticOp(String op) {
        return op.equals("+") || op.equals("-") || op.equals("*") || op.equals("/");
    }

    // ========== 物理计划 JSON 序列化 ==========

    /**
     * 将逻辑计划树序列化为物理计划 JSON 字符串（符合 storage_core 规范）。
     * 输出为单行 JSON（无换行），可直接写入 storage_core.exe 的 stdin。
     *
     * @param plan 计划树根节点
     * @return JSON 字符串
     */
    public String toJson(PlanNode plan) {
        StringBuilder sb = new StringBuilder();
        writePlanNode(sb, plan);
        return sb.toString();
    }

    /**
     * 递归写入计划节点。
     * 查询类（project/filter/scan）以树形组织，通过 child 引用子节点；
     * 写操作类（insert/update/delete/createTable）为单个对象。
     */
    private void writePlanNode(StringBuilder sb, PlanNode plan) {
        sb.append("{");

        if (plan instanceof PlanNode.SeqScanPlan p) {
            sb.append("\"op\":\"scan\",\"table\":");
            writeJsonString(sb, p.getTableName());
            if (p.getColumns() != null) {
                sb.append(",\"columns\":");
                writeStringList(sb, p.getColumns());
            }
        } else if (plan instanceof PlanNode.FilterPlan p) {
            sb.append("\"op\":\"filter\",\"condition\":");
            writeExpr(sb, p.getCondition());
            sb.append(",\"child\":");
            writePlanNode(sb, p.getChild());
        } else if (plan instanceof PlanNode.ProjectPlan p) {
            sb.append("\"op\":\"project\",\"columns\":");
            writeStringList(sb, p.getColumns());
            sb.append(",\"child\":");
            writePlanNode(sb, p.getChild());
        } else if (plan instanceof PlanNode.InsertPlan p) {
            sb.append("\"op\":\"insert\",\"table\":");
            writeJsonString(sb, p.getTableName());
            if (!p.getColumns().isEmpty()) {
                sb.append(",\"columns\":");
                writeStringList(sb, p.getColumns());
            }
            sb.append(",\"values\":[");
            List<LiteralExpr> values = p.getValues();
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) sb.append(",");
                writeLiteralValue(sb, values.get(i));
            }
            sb.append("]");
        } else if (plan instanceof PlanNode.UpdatePlan p) {
            sb.append("\"op\":\"update\",\"table\":");
            writeJsonString(sb, p.getTableName());
            sb.append(",\"set\":{");
            int i = 0;
            for (Map.Entry<String, LiteralExpr> entry : p.getAssignments().entrySet()) {
                if (i > 0) sb.append(",");
                writeJsonString(sb, entry.getKey());
                sb.append(":");
                writeLiteralValue(sb, entry.getValue());
                i++;
            }
            sb.append("}");
            if (p.getCondition() != null) {
                sb.append(",\"condition\":");
                writeExpr(sb, p.getCondition());
            }
        } else if (plan instanceof PlanNode.DeletePlan p) {
            sb.append("\"op\":\"delete\",\"table\":");
            writeJsonString(sb, p.getTableName());
            if (p.getCondition() != null) {
                sb.append(",\"condition\":");
                writeExpr(sb, p.getCondition());
            }
        } else if (plan instanceof PlanNode.CreateTablePlan p) {
            sb.append("\"op\":\"createTable\",\"table\":");
            writeJsonString(sb, p.getTableName());
            sb.append(",\"columns\":");
            writeStringList(sb, p.getColumns());
        } else if (plan instanceof PlanNode.ShowTablesPlan) {
            sb.append("\"op\":\"showTables\"");
        } else if (plan instanceof PlanNode.DescribeTablePlan p) {
            sb.append("\"op\":\"describeTable\",\"table\":");
            writeJsonString(sb, p.getTableName());
        } else if (plan instanceof PlanNode.DropTablePlan p) {
            sb.append("\"op\":\"dropTable\",\"table\":");
            writeJsonString(sb, p.getTableName());
        } else if (plan instanceof PlanNode.JoinPlan p) {
            // JOIN：{"op":"join","children":[子节点...],"on":[条件...]}
            // on[i] 为 null 表示该子节点为左表，无 ON 条件
            sb.append("\"op\":\"join\",\"children\":[");
            for (int i = 0; i < p.getChildren().size(); i++) {
                if (i > 0) sb.append(",");
                writePlanNode(sb, p.getChildren().get(i));
            }
            sb.append("],\"on\":[");
            for (int i = 0; i < p.getOnConditions().size(); i++) {
                if (i > 0) sb.append(",");
                ASTNode on = p.getOnConditions().get(i);
                if (on == null) {
                    sb.append("null");
                } else {
                    writeExpr(sb, on);
                }
            }
            sb.append("]");
        } else if (plan instanceof PlanNode.GroupByPlan p) {
            sb.append("\"op\":\"groupBy\",\"columns\":");
            writeStringList(sb, p.getGroupByColumns());
            sb.append(",\"child\":");
            writePlanNode(sb, p.getChild());
        } else if (plan instanceof PlanNode.OrderByPlan p) {
            // ORDER BY：{"op":"orderBy","items":[{"column":"age","direction":"ASC"},...],"child":{...}}
            sb.append("\"op\":\"orderBy\",\"items\":[");
            for (int i = 0; i < p.getOrderByItems().size(); i++) {
                PlanNode.OrderByPlan.OrderItem item = p.getOrderByItems().get(i);
                if (i > 0) sb.append(",");
                sb.append("{\"column\":");
                writeJsonString(sb, item.getColumn());
                sb.append(",\"direction\":");
                writeJsonString(sb, item.getDirection());
                sb.append("}");
            }
            sb.append("],\"child\":");
            writePlanNode(sb, p.getChild());
        } else {
            throw new IllegalArgumentException("无法序列化的计划节点类型: " + plan.getClass().getSimpleName());
        }

        sb.append("}");
    }

    /**
     * 递归写入条件表达式（AST → JSON condition）。
     * - IdentifierExpr → {"type":"column","name":"id"}
     * - LiteralExpr → {"type":"literal","value":1}（value 用 JSON 原生类型）
     * - BinaryExpr → {"type":"binary","op":">","left":{...},"right":{...}}
     */
    private void writeExpr(StringBuilder sb, ASTNode expr) {
        sb.append("{");
        if (expr instanceof ASTNode.IdentifierExpr col) {
            sb.append("\"type\":\"column\",\"name\":");
            writeJsonString(sb, col.getName());
        } else if (expr instanceof LiteralExpr lit) {
            sb.append("\"type\":\"literal\",\"value\":");
            writeLiteralValue(sb, lit);
        } else if (expr instanceof ASTNode.BinaryExpr bin) {
            sb.append("\"type\":\"binary\",\"op\":");
            writeJsonString(sb, bin.getOp());
            sb.append(",\"left\":");
            writeExpr(sb, bin.getLeft());
            sb.append(",\"right\":");
            writeExpr(sb, bin.getRight());
        } else if (expr instanceof ASTNode.UnaryExpr un) {
            sb.append("\"type\":\"unary\",\"op\":");
            writeJsonString(sb, un.getOp());
            sb.append(",\"operand\":");
            writeExpr(sb, un.getOperand());
        } else {
            throw new IllegalArgumentException("无法序列化的表达式类型: " + expr.getClass().getSimpleName());
        }
        sb.append("}");
    }

    /**
     * 写入字面量的值，根据 Kind 使用对应的 JSON 原生类型。
     * - NUMBER → JSON 数字（不带引号）
     * - STRING → JSON 字符串（带引号，处理转义）
     * - BOOLEAN → JSON 布尔（true/false，不带引号）
     */
    private void writeLiteralValue(StringBuilder sb, LiteralExpr lit) {
        if (lit.getKind() == Kind.NUMBER) {
            sb.append(lit.getValue());
        } else if (lit.getKind() == Kind.BOOLEAN) {
            sb.append(Boolean.parseBoolean(lit.getValue()) ? "true" : "false");
        } else {
            writeJsonString(sb, lit.getValue());
        }
    }

    /**
     * 写入字符串列表，如 ["id","name"]。
     */
    private void writeStringList(StringBuilder sb, List<String> list) {
        sb.append("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            writeJsonString(sb, list.get(i));
        }
        sb.append("]");
    }

    /**
     * 写入 JSON 字符串（带引号，处理转义）。
     * 转义规则：\" \\ \n \t \r \b \f
     */
    private void writeJsonString(StringBuilder sb, String str) {
        sb.append("\"");
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                case '\r' -> sb.append("\\r");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> sb.append(c);
            }
        }
        sb.append("\"");
    }
}
