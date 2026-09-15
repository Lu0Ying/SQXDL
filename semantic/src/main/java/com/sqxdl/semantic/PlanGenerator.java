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

    /**
     * 条件优化开关：generate() 默认开启（生成即优化，保持 B 组既有行为）；
     * build() 会临时关闭以产出未经条件优化的原始计划树（供 DEBUG 对比）。
     */
    private boolean optimizeConditions = true;

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
     * 生成未经条件优化（常量折叠/逻辑化简）的原始计划树，供 DEBUG 展示"优化前"对比。
     * 结构类优化（谓词下推、列裁剪、JOIN 顺序）仍会进行；条件保持 AST 原样。
     *
     * @param ast 语法树根节点
     * @return 原始计划树根节点
     */
    public PlanNode build(ASTNode ast) {
        optimizeConditions = false;
        try {
            return generate(ast);
        } finally {
            optimizeConditions = true;
        }
    }

    /**
     * 对计划树整体做条件优化：常量折叠、逻辑化简，恒真 Filter 节点整节点移除，
     * Update/Delete 恒真条件退化为全表。结构类优化（下推/裁剪/JOIN 顺序）已由
     * 生成阶段完成，此处仅处理条件表达式。
     *
     * @param plan 原始计划树根节点（如 {@link #build(ASTNode)} 的结果）
     * @return 优化后的计划树根节点
     */
    public PlanNode optimize(PlanNode plan) {
        if (plan instanceof PlanNode.FilterPlan p) {
            ASTNode cond = optimizeCondition(p.getCondition());
            PlanNode child = optimize(p.getChild());
            // 恒真条件 → Filter 节点整个移除
            return cond == null ? child : new PlanNode.FilterPlan(cond, child);
        }
        if (plan instanceof PlanNode.ProjectPlan p) {
            return new PlanNode.ProjectPlan(p.getColumns(), optimize(p.getChild()));
        }
        if (plan instanceof PlanNode.GroupByPlan p) {
            return new PlanNode.GroupByPlan(p.getGroupByColumns(), p.getAggregates(),
                    optimize(p.getChild()));
        }
        if (plan instanceof PlanNode.OrderByPlan p) {
            return new PlanNode.OrderByPlan(p.getOrderByItems(), optimize(p.getChild()));
        }
        if (plan instanceof PlanNode.JoinPlan p) {
            List<PlanNode> children = new ArrayList<>();
            for (PlanNode child : p.getChildren()) {
                children.add(optimize(child));
            }
            List<ASTNode> onConds = new ArrayList<>();
            for (ASTNode on : p.getOnConditions()) {
                onConds.add(optimizeCondition(on));
            }
            return new PlanNode.JoinPlan(children, onConds, p.getAlgorithms());
        }
        if (plan instanceof PlanNode.UpdatePlan p) {
            // 恒真条件 → null（全表更新）
            return new PlanNode.UpdatePlan(p.getTableName(), p.getAssignments(),
                    optimizeCondition(p.getCondition()));
        }
        if (plan instanceof PlanNode.DeletePlan p) {
            // 恒真条件 → null（全表删除）
            return new PlanNode.DeletePlan(p.getTableName(), optimizeCondition(p.getCondition()));
        }
        // SeqScan/Insert/CreateTable/Show/Describe/Drop：无子树、无条件，原样返回
        return plan;
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
                    maybeOptimizeCondition(stmt.getWhereCond()));
        }
        if (ast instanceof ASTNode.DeleteStmt stmt) {
            return new PlanNode.DeletePlan(stmt.getTableName(),
                    maybeOptimizeCondition(stmt.getWhereCond()));
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

        // === 构建别名 → 实际表名映射 ===
        Map<String, String> aliasToTable = new HashMap<>();
        if (stmt.getTableAlias() != null) {
            aliasToTable.put(stmt.getTableAlias(), stmt.getTableName());
        }
        for (ASTNode.SelectStmt.JoinClause join : joins) {
            if (join.getAlias() != null) {
                aliasToTable.put(join.getAlias(), join.getTableName());
            }
        }

        // === 谓词下推：拆分 WHERE 为合取项（先解析别名）===
        List<ASTNode> conjuncts = splitConjuncts(
                maybeOptimizeCondition(resolveAliases(stmt.getWhereCond(), aliasToTable)));
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

        // === 列裁剪：计算每张表实际需要的列（含别名解析）===
        Map<String, Set<String>> neededColumns = computeNeededColumns(stmt, tableNames, aliasToTable);

        // === 构建数据源 ===
        PlanNode plan;
        if (joins.isEmpty()) {
            // 单表查询：SeqScan + 下推谓词
            String mainTable = stmt.getTableName();
            plan = buildScanWithPushdown(mainTable, neededColumns, perTableFilters);
        } else {
            // 多表 JOIN：RBO 顺序优化
            // 构建原始 ON 条件列表：[null, join1.onCond, join2.onCond, ...]（先解析别名）
            List<ASTNode> originalOnConds = new ArrayList<>();
            originalOnConds.add(null);  // 主表无 ON
            for (ASTNode.SelectStmt.JoinClause join : joins) {
                originalOnConds.add(maybeOptimizeCondition(
                        resolveAliases(join.getOnCond(), aliasToTable)));
            }
            // RBO 重排表顺序
            JoinOrderResult joinOrder = computeJoinOrder(
                    tableNames, originalOnConds, perTableFilters);
            // 按重排顺序构建子节点
            List<PlanNode> children = new ArrayList<>();
            for (String table : joinOrder.tables) {
                children.add(buildScanWithPushdown(table, neededColumns, perTableFilters));
            }
            // 为每个 JOIN 子节点选择连接算法
            List<PlanNode.JoinAlgorithm> algorithms = new ArrayList<>();
            algorithms.add(PlanNode.JoinAlgorithm.NESTED_LOOP);  // 左表
            for (int i = 1; i < joinOrder.tables.size(); i++) {
                algorithms.add(chooseJoinAlgorithm(joinOrder.onConditions.get(i)));
            }
            plan = new PlanNode.JoinPlan(children, joinOrder.onConditions, algorithms);

            // 跨表谓词留在 Join 之上
            if (!joinFilters.isEmpty()) {
                ASTNode remaining = combineWithAnd(joinFilters);
                plan = new PlanNode.FilterPlan(remaining, plan);
            }
        }

        // === 叠加 GROUP BY（可选，解析别名）===
        if (!stmt.getGroupBy().isEmpty() || hasAggregate(stmt)) {
            List<String> resolvedGroupBy = new ArrayList<>();
            for (String col : stmt.getGroupBy()) {
                resolvedGroupBy.add(resolveAliasInString(col, aliasToTable));
            }
            List<String> aggList = collectAggregates(stmt, aliasToTable);
            plan = new PlanNode.GroupByPlan(resolvedGroupBy, aggList, plan);
        }

        // === 叠加 ORDER BY（可选，解析别名）===
        if (!stmt.getOrderBy().isEmpty()) {
            List<PlanNode.OrderByPlan.OrderItem> items = new ArrayList<>();
            for (ASTNode.SelectStmt.OrderItem item : stmt.getOrderBy()) {
                items.add(new PlanNode.OrderByPlan.OrderItem(
                        resolveAliasInString(item.getColumn(), aliasToTable),
                        item.getDirection()));
            }
            plan = new PlanNode.OrderByPlan(items, plan);
        }

        // === 叠加投影（解析别名）===
        return new PlanNode.ProjectPlan(expandColumns(stmt, aliasToTable), plan);
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

    // ========== JOIN 算法选择 ==========

    /**
     * 根据 ON 条件形式选择连接算法。
     * 等值连接（=）→ HASH；非等值或无条件 → NESTED_LOOP。
     */
    private PlanNode.JoinAlgorithm chooseJoinAlgorithm(ASTNode onCond) {
        if (onCond == null) {
            return PlanNode.JoinAlgorithm.NESTED_LOOP;  // 笛卡尔积
        }
        if (isEquiJoin(onCond)) {
            return PlanNode.JoinAlgorithm.HASH;
        }
        return PlanNode.JoinAlgorithm.NESTED_LOOP;
    }

    /**
     * 判断是否为等值连接：ON 条件为 a.col = b.col 形式。
     * 支持合取分解：a=x AND b=y 全是等值 → 仍可 HashJoin。
     * 含 OR 或非等值比较 → false。
     */
    private boolean isEquiJoin(ASTNode onCond) {
        if (onCond instanceof ASTNode.BinaryExpr bin) {
            if ("=".equals(bin.getOp())
                    && bin.getLeft() instanceof ASTNode.IdentifierExpr
                    && bin.getRight() instanceof ASTNode.IdentifierExpr) {
                return true;
            }
            // AND 连接的多个等值条件也是 HashJoin
            if ("AND".equals(bin.getOp())) {
                return isEquiJoin(bin.getLeft()) && isEquiJoin(bin.getRight());
            }
        }
        return false;
    }

    // ========== RBO JOIN 顺序优化 ==========

    /**
     * RBO 顺序优化结果：重排后的表顺序与对应的 ON 条件。
     */
    private static class JoinOrderResult {
        final List<String> tables;
        final List<ASTNode> onConditions;

        JoinOrderResult(List<String> tables, List<ASTNode> onConditions) {
            this.tables = tables;
            this.onConditions = onConditions;
        }
    }

    /**
     * 基于 RBO（基于规则优化）的 JOIN 顺序优化。
     * <p>
     * 规则：
     *   1. 有单表 WHERE 谓词的表优先作为起始表（过滤后中间结果更小）
     *   2. 避免笛卡尔积：优先选择与已连接集合有 ON 条件的表
     *   3. 同等条件下，保持原始顺序（主表优先）保证稳定性
     * <p>
     * 不依赖统计数据（行数、基数等），仅依赖 AST 结构和表结构信息。
     *
     * @param originalTables  原始表顺序 [主表, JOIN表1, JOIN表2, ...]
     * @param originalOnConds 原始 ON 条件 [null, ON1, ON2, ...]（已优化）
     * @param perTableFilters 每张表的单表 WHERE 谓词（用于优先级判断）
     * @return 重排后的表顺序与 ON 条件
     */
    private JoinOrderResult computeJoinOrder(List<String> originalTables,
                                              List<ASTNode> originalOnConds,
                                              Map<String, List<ASTNode>> perTableFilters) {
        int n = originalTables.size();
        if (n <= 1) {
            return new JoinOrderResult(new ArrayList<>(originalTables),
                                        new ArrayList<>(originalOnConds));
        }

        // 收集所有非 null 的 ON 条件（用于构建连接图）
        List<ASTNode> allConds = new ArrayList<>();
        for (ASTNode cond : originalOnConds) {
            if (cond != null) allConds.add(cond);
        }

        // 贪心选择
        List<String> orderedTables = new ArrayList<>();
        List<ASTNode> orderedOnConds = new ArrayList<>();
        Set<String> joined = new HashSet<>();
        Set<ASTNode> usedConds = new HashSet<>();

        // 第一步：选择起始表
        // 优先有单表谓词的表；无谓词时选主表（originalTables[0]）
        String firstTable = originalTables.get(0);
        int maxPreds = getPredicateCount(firstTable, perTableFilters);
        for (int i = 1; i < n; i++) {
            String t = originalTables.get(i);
            int preds = getPredicateCount(t, perTableFilters);
            if (preds > maxPreds) {
                maxPreds = preds;
                firstTable = t;
            }
        }

        orderedTables.add(firstTable);
        orderedOnConds.add(null);  // 左表无 ON 条件
        joined.add(firstTable);

        // 贪心添加剩余表
        while (joined.size() < n) {
            // 找所有可连接的候选表：与已连接集合有可用 ON 条件
            List<String> connectedCandidates = new ArrayList<>();
            Map<String, List<ASTNode>> candidateConds = new HashMap<>();

            for (String t : originalTables) {
                if (joined.contains(t)) continue;

                List<ASTNode> applicableConds = findApplicableConditions(
                        t, allConds, usedConds, joined, originalTables);

                if (!applicableConds.isEmpty()) {
                    connectedCandidates.add(t);
                    candidateConds.put(t, applicableConds);
                }
            }

            String nextTable;
            ASTNode nextCond;

            if (connectedCandidates.isEmpty()) {
                // 无可连接表 → 笛卡尔积：选有最多谓词的剩余表
                nextTable = pickRemainingWithMostPredicates(
                        originalTables, joined, perTableFilters);
                nextCond = null;
            } else {
                // 在可连接候选中，选有最多单表谓词的（同谓词数时保持原始顺序）
                nextTable = pickBestCandidate(
                        originalTables, connectedCandidates, perTableFilters);
                List<ASTNode> conds = candidateConds.get(nextTable);
                nextCond = combineWithAnd(conds);
                usedConds.addAll(conds);
            }

            orderedTables.add(nextTable);
            orderedOnConds.add(nextCond);
            joined.add(nextTable);
        }

        return new JoinOrderResult(orderedTables, orderedOnConds);
    }

    /**
     * 查找适用于候选表 t 的 ON 条件：
     * 条件引用 t，且条件引用的所有其他表都已在 joined 中。
     */
    private List<ASTNode> findApplicableConditions(String t,
                                                    List<ASTNode> allConds,
                                                    Set<ASTNode> usedConds,
                                                    Set<String> joined,
                                                    List<String> allTables) {
        List<ASTNode> applicable = new ArrayList<>();
        for (ASTNode cond : allConds) {
            if (usedConds.contains(cond)) continue;
            Set<String> refs = extractTableRefs(cond, allTables);
            if (!refs.contains(t)) continue;
            Set<String> needed = new HashSet<>(refs);
            needed.remove(t);
            if (joined.containsAll(needed)) {
                applicable.add(cond);
            }
        }
        return applicable;
    }

    /** 获取表的单表 WHERE 谓词数量 */
    private int getPredicateCount(String table, Map<String, List<ASTNode>> perTableFilters) {
        List<ASTNode> filters = perTableFilters.get(table);
        return filters == null ? 0 : filters.size();
    }

    /** 在剩余表中选有最多单表谓词的（同谓词数时保持原始顺序） */
    private String pickRemainingWithMostPredicates(List<String> originalTables,
                                                    Set<String> joined,
                                                    Map<String, List<ASTNode>> perTableFilters) {
        String best = null;
        int bestPreds = -1;
        for (String t : originalTables) {
            if (joined.contains(t)) continue;
            int preds = getPredicateCount(t, perTableFilters);
            if (preds > bestPreds) {
                bestPreds = preds;
                best = t;
            }
        }
        return best;
    }

    /** 在可连接候选中选有最多单表谓词的（同谓词数时保持原始顺序） */
    private String pickBestCandidate(List<String> originalTables,
                                       List<String> candidates,
                                       Map<String, List<ASTNode>> perTableFilters) {
        String best = null;
        int bestPreds = -1;
        for (String t : originalTables) {  // 按原始顺序遍历保证稳定性
            if (!candidates.contains(t)) continue;
            int preds = getPredicateCount(t, perTableFilters);
            if (preds > bestPreds) {
                bestPreds = preds;
                best = t;
            }
        }
        return best;
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
                                                          List<String> tableNames,
                                                          Map<String, String> aliasToTable) {
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

        // SELECT 清单中的列（解析别名后加入需求集）
        for (String col : selectList) {
            if (AggregateFunction.isAggregate(col)) {
                AggregateFunction agg = AggregateFunction.parse(col);
                if (agg.isCountStar()) {
                    for (String t : tableNames) {
                        List<String> cols = catalog.getColumns(t);
                        if (cols != null) {
                            result.get(t).addAll(cols);
                        }
                    }
                } else {
                    String resolvedArg = resolveAliasInString(agg.getArgument(), aliasToTable);
                    addColumnRef(resolvedArg, tableNames, aliasToTable, result);
                }
            } else {
                String resolved = resolveAliasInString(col, aliasToTable);
                addColumnRef(resolved, tableNames, aliasToTable, result);
            }
        }

        // WHERE 条件中的列（WHERE 已在 generateSelect 中解析过别名，此处直接收集）
        collectColumnRefs(stmt.getWhereCond(), tableNames, aliasToTable, result);

        // JOIN ON 条件中的列（ON 条件已在 generateSelect 中解析过别名）
        for (ASTNode.SelectStmt.JoinClause join : stmt.getJoins()) {
            collectColumnRefs(join.getOnCond(), tableNames, aliasToTable, result);
        }

        // GROUP BY 列（解析别名）
        for (String col : stmt.getGroupBy()) {
            String resolved = resolveAliasInString(col, aliasToTable);
            addColumnRef(resolved, tableNames, aliasToTable, result);
        }

        // ORDER BY 列（解析别名）
        for (ASTNode.SelectStmt.OrderItem item : stmt.getOrderBy()) {
            String resolved = resolveAliasInString(item.getColumn(), aliasToTable);
            addColumnRef(resolved, tableNames, aliasToTable, result);
        }

        return result;
    }

    /** 将单个列名（可能点限定）加入对应表的需求集，解析别名前缀 */
    private void addColumnRef(String col, List<String> tableNames,
                              Map<String, String> aliasToTable,
                              Map<String, Set<String>> result) {
        int dot = col.indexOf('.');
        if (dot > 0) {
            String tablePart = col.substring(0, dot);
            String column = col.substring(dot + 1);
            String actualTable = aliasToTable.getOrDefault(tablePart, tablePart);
            if (result.containsKey(actualTable)) {
                result.get(actualTable).add(column);
            }
        } else {
            for (String t : tableNames) {
                if (catalog.columnExists(t, col)) {
                    result.get(t).add(col);
                }
            }
        }
    }

    /** 递归从表达式中收集所有列引用（解析别名前缀）*/
    private void collectColumnRefs(ASTNode expr, List<String> tableNames,
                                   Map<String, String> aliasToTable,
                                   Map<String, Set<String>> result) {
        if (expr == null) {
            return;
        }
        if (expr instanceof ASTNode.IdentifierExpr col) {
            addColumnRef(col.getName(), tableNames, aliasToTable, result);
        } else if (expr instanceof ASTNode.BinaryExpr bin) {
            collectColumnRefs(bin.getLeft(), tableNames, aliasToTable, result);
            collectColumnRefs(bin.getRight(), tableNames, aliasToTable, result);
        } else if (expr instanceof ASTNode.UnaryExpr un) {
            collectColumnRefs(un.getOperand(), tableNames, aliasToTable, result);
        }
    }

    /**
     * 获取 SELECT 列清单：优先使用语义分析阶段已展开的结果，
     * 否则自行展开 SELECT *。解析别名前缀为实际表名。
     */
    private List<String> expandColumns(ASTNode.SelectStmt stmt,
                                       Map<String, String> aliasToTable) {
        // 优先使用语义分析器展开后的列清单（解析别名前缀）
        if (analyzer != null) {
            List<String> expanded = analyzer.getExpandedColumns(stmt);
            if (expanded != null) {
                List<String> resolved = new ArrayList<>();
                for (String col : expanded) {
                    resolved.add(resolveAliasInString(col, aliasToTable));
                }
                return resolved;
            }
        }
        // 退化为自行展开
        List<String> selectList = stmt.getSelectList();
        if (selectList.size() != 1 || !"*".equals(selectList.get(0))) {
            // 解析别名前缀
            List<String> resolved = new ArrayList<>();
            for (String col : selectList) {
                resolved.add(resolveAliasInString(col, aliasToTable));
            }
            return resolved;
        }
        List<String> allColumns = catalog.getColumns(stmt.getTableName());
        return allColumns == null ? new ArrayList<>() : allColumns;
    }

    /** 投影清单是否含聚合项（如 COUNT(*)、SUM(age)）；SELECT * 展开结果不含聚合 */
    private boolean hasAggregate(ASTNode.SelectStmt stmt) {
        for (String column : stmt.getSelectList()) {
            if (isAggregate(column)) {
                return true;
            }
        }
        return false;
    }

    /** 判断投影项是否为聚合函数调用 */
    private boolean isAggregate(String column) {
        return AggregateFunction.isAggregate(column);
    }

    /**
     * 收集 SELECT 清单中出现的所有聚合函数（保持出现顺序，解析别名）。
     * 用于 GroupByPlan 的 aggregates 字段，执行器据此对每组计算聚合值。
     */
    private List<String> collectAggregates(ASTNode.SelectStmt stmt,
                                           Map<String, String> aliasToTable) {
        List<String> result = new ArrayList<>();
        for (String column : stmt.getSelectList()) {
            if (isAggregate(column)) {
                result.add(resolveAliasInString(column, aliasToTable));
            }
        }
        return result;
    }

    // ========== 别名解析 ==========

    /**
     * 递归将表达式树中的别名限定列引用替换为实际表名限定。
     * 如 s.id（s 是 student 的别名）→ student.id。
     * 仅替换 aliasToTable 中已注册的别名前缀，非别名的点限定名保持原样。
     */
    private ASTNode resolveAliases(ASTNode expr, Map<String, String> aliasToTable) {
        if (expr == null) {
            return null;
        }
        if (expr instanceof ASTNode.IdentifierExpr id) {
            String name = id.getName();
            int dot = name.indexOf('.');
            if (dot > 0) {
                String prefix = name.substring(0, dot);
                if (aliasToTable.containsKey(prefix)) {
                    String actualTable = aliasToTable.get(prefix);
                    String column = name.substring(dot + 1);
                    return new ASTNode.IdentifierExpr(id.getLine(), id.getCol(),
                            actualTable + "." + column);
                }
            }
            return id;
        }
        if (expr instanceof ASTNode.BinaryExpr bin) {
            return new ASTNode.BinaryExpr(bin.getLine(), bin.getCol(), bin.getOp(),
                    resolveAliases(bin.getLeft(), aliasToTable),
                    resolveAliases(bin.getRight(), aliasToTable));
        }
        if (expr instanceof ASTNode.UnaryExpr un) {
            return new ASTNode.UnaryExpr(un.getLine(), un.getCol(), un.getOp(),
                    resolveAliases(un.getOperand(), aliasToTable));
        }
        return expr;
    }

    /**
     * 将字符串形式的列引用中的别名前缀替换为实际表名。
     * 支持聚合函数参数：SUM(s.score) → SUM(student.score)。
     * COUNT(*) 不含列引用，原样返回。
     */
    private String resolveAliasInString(String name, Map<String, String> aliasToTable) {
        if (AggregateFunction.isAggregate(name)) {
            AggregateFunction agg = AggregateFunction.parse(name);
            if (agg.isCountStar()) {
                return name;
            }
            String resolvedArg = resolveAliasInString(agg.getArgument(), aliasToTable);
            return agg.getName() + "(" + resolvedArg + ")";
        }
        int dot = name.indexOf('.');
        if (dot > 0) {
            String prefix = name.substring(0, dot);
            if (aliasToTable.containsKey(prefix)) {
                return aliasToTable.get(prefix) + "." + name.substring(dot + 1);
            }
        }
        return name;
    }

    // ========== 条件优化入口 ==========

    /**
     * 按开关决定是否优化条件：generate()（含 build 内部复用）据此区分
     * "原始生成"与"生成即优化"两种模式。
     */
    private ASTNode maybeOptimizeCondition(ASTNode cond) {
        return optimizeConditions ? optimizeCondition(cond) : cond;
    }

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
            // JOIN：{"op":"join","children":[子节点...],"on":[条件...],"algorithms":[算法...]}
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
            if (p.getAlgorithms() != null) {
                sb.append(",\"algorithms\":[");
                for (int i = 0; i < p.getAlgorithms().size(); i++) {
                    if (i > 0) sb.append(",");
                    sb.append("\"").append(p.getAlgorithms().get(i).name()).append("\"");
                }
                sb.append("]");
            }
        } else if (plan instanceof PlanNode.GroupByPlan p) {
            sb.append("\"op\":\"groupBy\",\"columns\":");
            writeStringList(sb, p.getGroupByColumns());
            // 聚合函数列表（非空时输出，供执行器计算每组聚合值）
            if (!p.getAggregates().isEmpty()) {
                sb.append(",\"aggregates\":");
                writeStringList(sb, p.getAggregates());
            }
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
