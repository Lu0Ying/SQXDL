package com.sqxdl.semantic;

import com.sqxdl.parser.ASTNode;
import com.sqxdl.parser.ASTNode.LiteralExpr;
import com.sqxdl.parser.ASTNode.LiteralExpr.Kind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlanGeneratorTest {

    private CatalogImpl catalog;
    private PlanGenerator generator;

    @BeforeEach
    void setUp() {
        catalog = new CatalogImpl();
        List<CatalogImpl.ColumnInfo> studentCols = Arrays.asList(
                new CatalogImpl.ColumnInfo("id", CatalogImpl.DataType.INT),
                new CatalogImpl.ColumnInfo("name", CatalogImpl.DataType.VARCHAR),
                new CatalogImpl.ColumnInfo("age", CatalogImpl.DataType.INT)
        );
        catalog.createTableWithTypes("student", studentCols);
        generator = new PlanGenerator(catalog);
    }

    // ========== 基本计划生成（无优化） ==========

    @Test
    void select_noWhere_success() {
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("id", "name"), null, List.of(), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        assertTrue(plan instanceof PlanNode.ProjectPlan);
        ProjectPlanCheck(plan, Arrays.asList("id", "name"));
        assertTrue(getChild(plan) instanceof PlanNode.SeqScanPlan);
        assertNull(getChild(getChild(plan))); // SeqScan 无子节点
    }

    @Test
    void select_withWhere_success() {
        ASTNode.IdentifierExpr col = new ASTNode.IdentifierExpr(1, 10, "age");
        ASTNode.LiteralExpr lit = lit(1, 15, "18", Kind.NUMBER);
        ASTNode.BinaryExpr cond = new ASTNode.BinaryExpr(1, 12, ">", col, lit);

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("id"), cond, List.of(), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        assertTrue(plan instanceof PlanNode.ProjectPlan);
        assertTrue(getChild(plan) instanceof PlanNode.FilterPlan);
        assertTrue(getChild(getChild(plan)) instanceof PlanNode.SeqScanPlan);
    }

    @Test
    void selectStar_expandsToAllColumns() {
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("*"), null, List.of(), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        ProjectPlanCheck(plan, Arrays.asList("id", "name", "age"));
    }

    // ========== 常量折叠 ==========

    @Test
    void constantFolding_trueComparison_skipsFilter() {
        // WHERE 1 = 1 → TRUE → 无 Filter
        ASTNode.BinaryExpr cond = new ASTNode.BinaryExpr(
                1, 10, "=", lit(1, 9, "1", Kind.NUMBER), lit(1, 13, "1", Kind.NUMBER));

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("id"), cond, List.of(), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        // Project → SeqScan（跳过了 Filter）
        assertTrue(plan instanceof PlanNode.ProjectPlan);
        assertTrue(getChild(plan) instanceof PlanNode.SeqScanPlan);
    }

    @Test
    void constantFolding_falseComparison_keepsFilter() {
        // WHERE 1 = 0 → FALSE → Filter(FALSE)
        ASTNode.BinaryExpr cond = new ASTNode.BinaryExpr(
                1, 10, "=", lit(1, 9, "1", Kind.NUMBER), lit(1, 13, "0", Kind.NUMBER));

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("id"), cond, List.of(), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        // Project → Filter(FALSE) → SeqScan
        assertTrue(plan instanceof PlanNode.ProjectPlan);
        assertTrue(getChild(plan) instanceof PlanNode.FilterPlan);
    }

    @Test
    void constantFolding_arithmeticThenComparison() {
        // WHERE 2 + 3 > 4 → WHERE 5 > 4 → WHERE TRUE → 无 Filter
        ASTNode.BinaryExpr add = new ASTNode.BinaryExpr(
                1, 9, "+", lit(1, 8, "2", Kind.NUMBER), lit(1, 12, "3", Kind.NUMBER));
        ASTNode.BinaryExpr cond = new ASTNode.BinaryExpr(
                1, 14, ">", add, lit(1, 16, "4", Kind.NUMBER));

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("id"), cond, List.of(), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        // 折叠后为 TRUE → 跳过 Filter
        assertTrue(plan instanceof PlanNode.ProjectPlan);
        assertTrue(getChild(plan) instanceof PlanNode.SeqScanPlan);
    }

    @Test
    void constantFolding_arithmeticProducesNumber() {
        // WHERE 2 + 3 > age → WHERE 5 > age（折叠算术，保留列引用）
        ASTNode.BinaryExpr add = new ASTNode.BinaryExpr(
                1, 9, "+", lit(1, 8, "2", Kind.NUMBER), lit(1, 12, "3", Kind.NUMBER));
        ASTNode.IdentifierExpr col = new ASTNode.IdentifierExpr(1, 16, "age");
        ASTNode.BinaryExpr cond = new ASTNode.BinaryExpr(1, 14, ">", add, col);

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("id"), cond, List.of(), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        // 折叠后为 5 > age，仍需 Filter
        assertTrue(getChild(plan) instanceof PlanNode.FilterPlan);
        // 验证折叠后的条件左操作数是 5
        ASTNode.BinaryExpr filterCond = (ASTNode.BinaryExpr) ((PlanNode.FilterPlan) getChild(plan)).getCondition();
        assertTrue(filterCond.getLeft() instanceof ASTNode.LiteralExpr);
        assertEquals("5", ((ASTNode.LiteralExpr) filterCond.getLeft()).getValue());
    }

    @Test
    void constantFolding_stringComparison() {
        // WHERE 'abc' = 'abc' → TRUE → 无 Filter
        ASTNode.BinaryExpr cond = new ASTNode.BinaryExpr(
                1, 10, "=", lit(1, 9, "abc", Kind.STRING), lit(1, 15, "abc", Kind.STRING));

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("id"), cond, List.of(), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        assertTrue(getChild(plan) instanceof PlanNode.SeqScanPlan);
    }

    @Test
    void constantFolding_stringNotEqual_false() {
        // WHERE 'abc' != 'abc' → FALSE → Filter(FALSE)
        ASTNode.BinaryExpr cond = new ASTNode.BinaryExpr(
                1, 10, "!=", lit(1, 9, "abc", Kind.STRING), lit(1, 15, "abc", Kind.STRING));

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("id"), cond, List.of(), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        assertTrue(getChild(plan) instanceof PlanNode.FilterPlan);
    }

    @Test
    void constantFolding_divByZero_notFolded() {
        // WHERE 1 / 0 > 0 → 除以零不折叠，保留原始表达式
        ASTNode.BinaryExpr div = new ASTNode.BinaryExpr(
                1, 9, "/", lit(1, 8, "1", Kind.NUMBER), lit(1, 12, "0", Kind.NUMBER));
        ASTNode.BinaryExpr cond = new ASTNode.BinaryExpr(
                1, 14, ">", div, lit(1, 16, "0", Kind.NUMBER));

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("id"), cond, List.of(), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        // 除以零不折叠，保留 Filter
        assertTrue(getChild(plan) instanceof PlanNode.FilterPlan);
    }

    // ========== 逻辑表达式化简 ==========

    @Test
    void simplify_andTrue_removesTrue() {
        // WHERE age > 18 AND TRUE → WHERE age > 18
        ASTNode.IdentifierExpr col = new ASTNode.IdentifierExpr(1, 10, "age");
        ASTNode.BinaryExpr left = new ASTNode.BinaryExpr(1, 12, ">", col, lit(1, 16, "18", Kind.NUMBER));
        ASTNode.BinaryExpr cond = new ASTNode.BinaryExpr(1, 20, "AND", left, lit(1, 24, "true", Kind.BOOLEAN));

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("id"), cond, List.of(), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        // 化简后仍需 Filter（age > 18 不是常量）
        assertTrue(getChild(plan) instanceof PlanNode.FilterPlan);
        // Filter 条件应该是 age > 18（化简掉了 AND TRUE）
        ASTNode filterCond = ((PlanNode.FilterPlan) getChild(plan)).getCondition();
        assertTrue(filterCond instanceof ASTNode.BinaryExpr);
        assertEquals(">", ((ASTNode.BinaryExpr) filterCond).getOp());
    }

    @Test
    void simplify_andFalse_becomesFalse() {
        // WHERE age > 18 AND FALSE → WHERE FALSE
        ASTNode.IdentifierExpr col = new ASTNode.IdentifierExpr(1, 10, "age");
        ASTNode.BinaryExpr left = new ASTNode.BinaryExpr(1, 12, ">", col, lit(1, 16, "18", Kind.NUMBER));
        ASTNode.BinaryExpr cond = new ASTNode.BinaryExpr(1, 20, "AND", left, lit(1, 24, "false", Kind.BOOLEAN));

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("id"), cond, List.of(), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        // 化简为 FALSE → Filter(FALSE)
        assertTrue(getChild(plan) instanceof PlanNode.FilterPlan);
        ASTNode filterCond = ((PlanNode.FilterPlan) getChild(plan)).getCondition();
        assertTrue(filterCond instanceof ASTNode.LiteralExpr);
        assertEquals("false", ((ASTNode.LiteralExpr) filterCond).getValue());
    }

    @Test
    void simplify_orTrue_becomesTrue_skipsFilter() {
        // WHERE age > 18 OR TRUE → WHERE TRUE → 无 Filter
        ASTNode.IdentifierExpr col = new ASTNode.IdentifierExpr(1, 10, "age");
        ASTNode.BinaryExpr left = new ASTNode.BinaryExpr(1, 12, ">", col, lit(1, 16, "18", Kind.NUMBER));
        ASTNode.BinaryExpr cond = new ASTNode.BinaryExpr(1, 20, "OR", left, lit(1, 24, "true", Kind.BOOLEAN));

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("id"), cond, List.of(), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        // 化简为 TRUE → 跳过 Filter
        assertTrue(getChild(plan) instanceof PlanNode.SeqScanPlan);
    }

    @Test
    void simplify_orFalse_removesFalse() {
        // WHERE age > 18 OR FALSE → WHERE age > 18
        ASTNode.IdentifierExpr col = new ASTNode.IdentifierExpr(1, 10, "age");
        ASTNode.BinaryExpr left = new ASTNode.BinaryExpr(1, 12, ">", col, lit(1, 16, "18", Kind.NUMBER));
        ASTNode.BinaryExpr cond = new ASTNode.BinaryExpr(1, 20, "OR", left, lit(1, 24, "false", Kind.BOOLEAN));

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("id"), cond, List.of(), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        // 化简后仍需 Filter
        assertTrue(getChild(plan) instanceof PlanNode.FilterPlan);
        ASTNode filterCond = ((PlanNode.FilterPlan) getChild(plan)).getCondition();
        assertTrue(filterCond instanceof ASTNode.BinaryExpr);
        assertEquals(">", ((ASTNode.BinaryExpr) filterCond).getOp());
    }

    // ========== 算术恒等式化简 ==========

    @Test
    void simplify_addZero_removesZero() {
        // WHERE age + 0 > 18 → WHERE age > 18
        ASTNode.IdentifierExpr col = new ASTNode.IdentifierExpr(1, 10, "age");
        ASTNode.BinaryExpr add = new ASTNode.BinaryExpr(1, 12, "+", col, lit(1, 18, "0", Kind.NUMBER));
        ASTNode.BinaryExpr cond = new ASTNode.BinaryExpr(1, 20, ">", add, lit(1, 24, "18", Kind.NUMBER));

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("id"), cond, List.of(), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        assertTrue(getChild(plan) instanceof PlanNode.FilterPlan);
        ASTNode.BinaryExpr filterCond = (ASTNode.BinaryExpr) ((PlanNode.FilterPlan) getChild(plan)).getCondition();
        // 左操作数应该直接是 IdentifierExpr（age+0 被化简为 age）
        assertTrue(filterCond.getLeft() instanceof ASTNode.IdentifierExpr);
        assertEquals("age", ((ASTNode.IdentifierExpr) filterCond.getLeft()).getName());
    }

    @Test
    void simplify_mulOne_removesOne() {
        // WHERE age * 1 > 18 → WHERE age > 18
        ASTNode.IdentifierExpr col = new ASTNode.IdentifierExpr(1, 10, "age");
        ASTNode.BinaryExpr mul = new ASTNode.BinaryExpr(1, 12, "*", col, lit(1, 18, "1", Kind.NUMBER));
        ASTNode.BinaryExpr cond = new ASTNode.BinaryExpr(1, 20, ">", mul, lit(1, 24, "18", Kind.NUMBER));

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("id"), cond, List.of(), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        assertTrue(getChild(plan) instanceof PlanNode.FilterPlan);
        ASTNode.BinaryExpr filterCond = (ASTNode.BinaryExpr) ((PlanNode.FilterPlan) getChild(plan)).getCondition();
        assertTrue(filterCond.getLeft() instanceof ASTNode.IdentifierExpr);
    }

    @Test
    void simplify_subZero_removesZero() {
        // WHERE age - 0 > 18 → WHERE age > 18
        ASTNode.IdentifierExpr col = new ASTNode.IdentifierExpr(1, 10, "age");
        ASTNode.BinaryExpr sub = new ASTNode.BinaryExpr(1, 12, "-", col, lit(1, 18, "0", Kind.NUMBER));
        ASTNode.BinaryExpr cond = new ASTNode.BinaryExpr(1, 20, ">", sub, lit(1, 24, "18", Kind.NUMBER));

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("id"), cond, List.of(), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        ASTNode.BinaryExpr filterCond = (ASTNode.BinaryExpr) ((PlanNode.FilterPlan) getChild(plan)).getCondition();
        assertTrue(filterCond.getLeft() instanceof ASTNode.IdentifierExpr);
    }

    @Test
    void simplify_divOne_removesOne() {
        // WHERE age / 1 > 18 → WHERE age > 18
        ASTNode.IdentifierExpr col = new ASTNode.IdentifierExpr(1, 10, "age");
        ASTNode.BinaryExpr div = new ASTNode.BinaryExpr(1, 12, "/", col, lit(1, 18, "1", Kind.NUMBER));
        ASTNode.BinaryExpr cond = new ASTNode.BinaryExpr(1, 20, ">", div, lit(1, 24, "18", Kind.NUMBER));

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("id"), cond, List.of(), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        ASTNode.BinaryExpr filterCond = (ASTNode.BinaryExpr) ((PlanNode.FilterPlan) getChild(plan)).getCondition();
        assertTrue(filterCond.getLeft() instanceof ASTNode.IdentifierExpr);
    }

    // ========== 嵌套优化 ==========

    @Test
    void nestedOptimization_bothSideFolded() {
        // WHERE (1+2) > (3-1) → WHERE 3 > 2 → WHERE TRUE → 无 Filter
        ASTNode.BinaryExpr left = new ASTNode.BinaryExpr(1, 9, "+",
                lit(1, 8, "1", Kind.NUMBER), lit(1, 12, "2", Kind.NUMBER));
        ASTNode.BinaryExpr right = new ASTNode.BinaryExpr(1, 17, "-",
                lit(1, 16, "3", Kind.NUMBER), lit(1, 20, "1", Kind.NUMBER));
        ASTNode.BinaryExpr cond = new ASTNode.BinaryExpr(1, 14, ">", left, right);

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("id"), cond, List.of(), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        assertTrue(getChild(plan) instanceof PlanNode.SeqScanPlan);
    }

    @Test
    void nestedOptimization_partialFold() {
        // WHERE (1+2) > age → WHERE 3 > age（左折叠，右保留）
        ASTNode.BinaryExpr left = new ASTNode.BinaryExpr(1, 9, "+",
                lit(1, 8, "1", Kind.NUMBER), lit(1, 12, "2", Kind.NUMBER));
        ASTNode.IdentifierExpr right = new ASTNode.IdentifierExpr(1, 16, "age");
        ASTNode.BinaryExpr cond = new ASTNode.BinaryExpr(1, 14, ">", left, right);

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("id"), cond, List.of(), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        assertTrue(getChild(plan) instanceof PlanNode.FilterPlan);
        ASTNode.BinaryExpr filterCond = (ASTNode.BinaryExpr) ((PlanNode.FilterPlan) getChild(plan)).getCondition();
        assertTrue(filterCond.getLeft() instanceof ASTNode.LiteralExpr);
        assertEquals("3", ((ASTNode.LiteralExpr) filterCond.getLeft()).getValue());
        assertTrue(filterCond.getRight() instanceof ASTNode.IdentifierExpr);
    }

    // ========== UPDATE/DELETE 条件优化 ==========

    @Test
    void update_whereTrue_conditionIsNull() {
        // UPDATE student SET name='Bob' WHERE 1=1 → condition 被优化为 null
        Map<String, ASTNode.LiteralExpr> assignments = new LinkedHashMap<>();
        assignments.put("name", lit(1, 20, "Bob", Kind.STRING));

        ASTNode.BinaryExpr cond = new ASTNode.BinaryExpr(
                1, 30, "=", lit(1, 29, "1", Kind.NUMBER), lit(1, 33, "1", Kind.NUMBER));
        ASTNode.UpdateStmt stmt = new ASTNode.UpdateStmt(1, 1, "student", assignments, cond);

        PlanNode plan = generator.generate(stmt);
        assertTrue(plan instanceof PlanNode.UpdatePlan);
        assertNull(((PlanNode.UpdatePlan) plan).getCondition());
    }

    @Test
    void delete_whereFalse_conditionIsFalse() {
        ASTNode.BinaryExpr cond = new ASTNode.BinaryExpr(
                1, 30, "=", lit(1, 29, "1", Kind.NUMBER), lit(1, 33, "0", Kind.NUMBER));
        ASTNode.DeleteStmt stmt = new ASTNode.DeleteStmt(1, 1, "student", cond);

        PlanNode plan = generator.generate(stmt);
        assertTrue(plan instanceof PlanNode.DeletePlan);
        ASTNode condResult = ((PlanNode.DeletePlan) plan).getCondition();
        assertNotNull(condResult);
        assertTrue(condResult instanceof ASTNode.LiteralExpr);
        assertEquals("false", ((ASTNode.LiteralExpr) condResult).getValue());
    }

    @Test
    void update_noWhere_conditionIsNull() {
        Map<String, ASTNode.LiteralExpr> assignments = new LinkedHashMap<>();
        assignments.put("name", lit(1, 20, "Bob", Kind.STRING));

        ASTNode.UpdateStmt stmt = new ASTNode.UpdateStmt(1, 1, "student", assignments, null);

        PlanNode plan = generator.generate(stmt);
        assertTrue(plan instanceof PlanNode.UpdatePlan);
        assertNull(((PlanNode.UpdatePlan) plan).getCondition());
    }

    // ========== 其他语句 ==========

    @Test
    void insert_success() {
        List<ASTNode.LiteralExpr> values = Arrays.asList(
                lit(1, 20, "1", Kind.NUMBER), lit(1, 25, "Alice", Kind.STRING));
        ASTNode.InsertStmt stmt = new ASTNode.InsertStmt(1, 1, "student", Arrays.asList("id", "name"), values);

        PlanNode plan = generator.generate(stmt);
        assertTrue(plan instanceof PlanNode.InsertPlan);
    }

    @Test
    void createTable_success() {
        ASTNode.CreateTableStmt stmt = new ASTNode.CreateTableStmt(1, 1, "course", Arrays.asList(
                new ASTNode.CreateTableStmt.ColumnDef("cid", "INT"),
                new ASTNode.CreateTableStmt.ColumnDef("cname", "VARCHAR")));

        PlanNode plan = generator.generate(stmt);
        assertTrue(plan instanceof PlanNode.CreateTablePlan);
    }

    // ========== 与 SemanticAnalyzer 协作 ==========

    @Test
    void selectStar_withAnalyzer_usesExpandedColumns() {
        SemanticAnalyzer analyzer = new SemanticAnalyzer(catalog);
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("*"), null, List.of(), List.of(), List.of());

        // 先做语义分析（展开 *）
        analyzer.analyze(stmt);

        // PlanGenerator 使用 analyzer 展开后的列清单
        generator.setAnalyzer(analyzer);
        PlanNode plan = generator.generate(stmt);

        ProjectPlanCheck(plan, Arrays.asList("id", "name", "age"));
    }

    @Test
    void selectStar_withoutAnalyzer_selfExpands() {
        // 不设置 analyzer，PlanGenerator 自行展开
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("*"), null, List.of(), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        ProjectPlanCheck(plan, Arrays.asList("id", "name", "age"));
    }

    @Test
    void selectStar_withAnalyzer_skipsRedundantExpansion() {
        // 即使没有调用 analyzer.analyze()，setAnalyzer 后应该退化为自行展开
        SemanticAnalyzer analyzer = new SemanticAnalyzer(catalog);
        generator.setAnalyzer(analyzer);

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("id"), null, List.of(), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        ProjectPlanCheck(plan, Arrays.asList("id"));
    }

    @Test
    void select_withAnalyzerAndWhere_success() {
        SemanticAnalyzer analyzer = new SemanticAnalyzer(catalog);
        ASTNode.IdentifierExpr col = new ASTNode.IdentifierExpr(1, 10, "age");
        ASTNode.LiteralExpr lit = lit(1, 15, "18", Kind.NUMBER);
        ASTNode.BinaryExpr cond = new ASTNode.BinaryExpr(1, 12, ">", col, lit);

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("id", "name"), cond, List.of(), List.of(), List.of());

        analyzer.analyze(stmt);
        generator.setAnalyzer(analyzer);
        PlanNode plan = generator.generate(stmt);

        assertTrue(plan instanceof PlanNode.ProjectPlan);
        assertTrue(getChild(plan) instanceof PlanNode.FilterPlan);
        assertTrue(getChild(getChild(plan)) instanceof PlanNode.SeqScanPlan);
    }

    // ========== 计划树可视化 ==========

    @Test
    void formatPlan_selectWithWhere() {
        ASTNode.IdentifierExpr col = new ASTNode.IdentifierExpr(1, 10, "age");
        ASTNode.LiteralExpr lit = lit(1, 15, "18", Kind.NUMBER);
        ASTNode.BinaryExpr cond = new ASTNode.BinaryExpr(1, 12, ">", col, lit);

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("id", "name"), cond, List.of(), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        String output = PlanNode.formatPlan(plan);

        // 验证包含各层节点
        assertTrue(output.contains("ProjectPlan"));
        assertTrue(output.contains("columns: [id, name]"));
        assertTrue(output.contains("FilterPlan"));
        assertTrue(output.contains("condition:"));
        assertTrue(output.contains("SeqScanPlan"));
        assertTrue(output.contains("table: student"));
    }

    @Test
    void formatPlan_selectNoWhere() {
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("id"), null, List.of(), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        String output = PlanNode.formatPlan(plan);

        assertTrue(output.contains("ProjectPlan"));
        assertTrue(output.contains("SeqScanPlan"));
        assertFalse(output.contains("FilterPlan"));
    }

    @Test
    void formatPlan_selectStar() {
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("*"), null, List.of(), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        String output = PlanNode.formatPlan(plan);

        assertTrue(output.contains("columns: [id, name, age]"));
    }

    @Test
    void formatPlan_insert() {
        List<ASTNode.LiteralExpr> values = Arrays.asList(
                lit(1, 20, "1", Kind.NUMBER), lit(1, 25, "Alice", Kind.STRING));
        ASTNode.InsertStmt stmt = new ASTNode.InsertStmt(1, 1, "student", Arrays.asList("id", "name"), values);
        PlanNode plan = generator.generate(stmt);

        String output = PlanNode.formatPlan(plan);

        assertTrue(output.contains("InsertPlan"));
        assertTrue(output.contains("table: student"));
        assertTrue(output.contains("columns: [id, name]"));
    }

    @Test
    void formatPlan_updateWithCondition() {
        Map<String, ASTNode.LiteralExpr> assignments = new LinkedHashMap<>();
        assignments.put("name", lit(1, 20, "Bob", Kind.STRING));

        ASTNode.IdentifierExpr col = new ASTNode.IdentifierExpr(1, 30, "age");
        ASTNode.BinaryExpr cond = new ASTNode.BinaryExpr(1, 32, ">", col, lit(1, 36, "18", Kind.NUMBER));
        ASTNode.UpdateStmt stmt = new ASTNode.UpdateStmt(1, 1, "student", assignments, cond);

        PlanNode plan = generator.generate(stmt);

        String output = PlanNode.formatPlan(plan);

        assertTrue(output.contains("UpdatePlan"));
        assertTrue(output.contains("table: student"));
        assertTrue(output.contains("condition:"));
    }

    @Test
    void formatPlan_updateNoCondition() {
        Map<String, ASTNode.LiteralExpr> assignments = new LinkedHashMap<>();
        assignments.put("name", lit(1, 20, "Bob", Kind.STRING));
        ASTNode.UpdateStmt stmt = new ASTNode.UpdateStmt(1, 1, "student", assignments, null);

        PlanNode plan = generator.generate(stmt);

        String output = PlanNode.formatPlan(plan);

        assertTrue(output.contains("UpdatePlan"));
        assertTrue(output.contains("(全表)"));
    }

    @Test
    void formatPlan_delete() {
        ASTNode.DeleteStmt stmt = new ASTNode.DeleteStmt(1, 1, "student", null);
        PlanNode plan = generator.generate(stmt);

        String output = PlanNode.formatPlan(plan);

        assertTrue(output.contains("DeletePlan"));
        assertTrue(output.contains("table: student"));
        assertTrue(output.contains("(全表)"));
    }

    @Test
    void formatPlan_createTable() {
        ASTNode.CreateTableStmt stmt = new ASTNode.CreateTableStmt(1, 1, "course", Arrays.asList(
                new ASTNode.CreateTableStmt.ColumnDef("cid", "INT"),
                new ASTNode.CreateTableStmt.ColumnDef("cname", "VARCHAR")));
        PlanNode plan = generator.generate(stmt);

        String output = PlanNode.formatPlan(plan);

        assertTrue(output.contains("CreateTablePlan"));
        assertTrue(output.contains("table: course"));
        assertTrue(output.contains("columns: [cid, cname]"));
    }

    @Test
    void formatPlan_nullPlan() {
        String output = PlanNode.formatPlan(null);
        assertEquals("(null)\n", output);
    }

    // ========== 物理计划 JSON 序列化 ==========

    @Test
    void toJson_selectWithWhere() {
        ASTNode.IdentifierExpr col = new ASTNode.IdentifierExpr(1, 10, "id");
        ASTNode.LiteralExpr val = lit(1, 15, "1", Kind.NUMBER);
        ASTNode.BinaryExpr cond = new ASTNode.BinaryExpr(1, 12, ">", col, val);

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("id", "name"), cond, List.of(), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        String json = generator.toJson(plan);

        // 验证 JSON 结构正确
        assertTrue(json.contains("\"op\":\"project\""));
        assertTrue(json.contains("\"columns\":[\"id\",\"name\"]"));
        assertTrue(json.contains("\"op\":\"filter\""));
        assertTrue(json.contains("\"type\":\"binary\""));
        assertTrue(json.contains("\"op\":\">\""));
        assertTrue(json.contains("\"type\":\"column\""));
        assertTrue(json.contains("\"name\":\"id\""));
        assertTrue(json.contains("\"type\":\"literal\""));
        assertTrue(json.contains("\"value\":1"));
        assertTrue(json.contains("\"op\":\"scan\""));
        assertTrue(json.contains("\"table\":\"student\""));
        // 确保是单行
        assertFalse(json.contains("\n"));
    }

    @Test
    void toJson_selectNoWhere() {
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("id", "name"), null, List.of(), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        String json = generator.toJson(plan);

        assertTrue(json.contains("\"op\":\"project\""));
        assertFalse(json.contains("\"op\":\"filter\""));
        assertTrue(json.contains("\"op\":\"scan\""));
    }

    @Test
    void toJson_selectStar() {
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("*"), null, List.of(), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        String json = generator.toJson(plan);

        // * 应该被展开为完整列名
        assertTrue(json.contains("\"columns\":[\"id\",\"name\",\"age\"]"));
        assertFalse(json.contains("\"*\""));
    }

    @Test
    void toJson_insertWithColumns() {
        List<ASTNode.LiteralExpr> values = Arrays.asList(
                lit(1, 20, "1", Kind.NUMBER), lit(1, 25, "Alice", Kind.STRING));
        ASTNode.InsertStmt stmt = new ASTNode.InsertStmt(1, 1, "student", Arrays.asList("id", "name"), values);
        PlanNode plan = generator.generate(stmt);

        String json = generator.toJson(plan);

        assertTrue(json.contains("\"op\":\"insert\""));
        assertTrue(json.contains("\"table\":\"student\""));
        assertTrue(json.contains("\"columns\":[\"id\",\"name\"]"));
        assertTrue(json.contains("\"values\":[1,\"Alice\"]"));
        // NUMBER 值不带引号，STRING 值带引号
        assertTrue(json.contains("[1,"));
        assertTrue(json.contains("\"Alice\""));
    }

    @Test
    void toJson_insertWithoutColumns() {
        List<ASTNode.LiteralExpr> values = Arrays.asList(
                lit(1, 20, "1", Kind.NUMBER), lit(1, 25, "Alice", Kind.STRING));
        ASTNode.InsertStmt stmt = new ASTNode.InsertStmt(1, 1, "student", Arrays.asList(), values);
        PlanNode plan = generator.generate(stmt);

        String json = generator.toJson(plan);

        assertTrue(json.contains("\"op\":\"insert\""));
        assertFalse(json.contains("\"columns\""));
        assertTrue(json.contains("\"values\":[1,\"Alice\"]"));
    }

    @Test
    void toJson_updateWithCondition() {
        Map<String, ASTNode.LiteralExpr> assignments = new LinkedHashMap<>();
        assignments.put("name", lit(1, 20, "Bob", Kind.STRING));

        ASTNode.IdentifierExpr col = new ASTNode.IdentifierExpr(1, 30, "id");
        ASTNode.BinaryExpr cond = new ASTNode.BinaryExpr(1, 32, "=", col, lit(1, 36, "1", Kind.NUMBER));
        ASTNode.UpdateStmt stmt = new ASTNode.UpdateStmt(1, 1, "student", assignments, cond);

        PlanNode plan = generator.generate(stmt);

        String json = generator.toJson(plan);

        assertTrue(json.contains("\"op\":\"update\""));
        assertTrue(json.contains("\"set\":{\"name\":\"Bob\"}"));
        assertTrue(json.contains("\"condition\""));
        assertTrue(json.contains("\"type\":\"binary\""));
        assertTrue(json.contains("\"op\":\"=\""));
    }

    @Test
    void toJson_updateNoCondition() {
        Map<String, ASTNode.LiteralExpr> assignments = new LinkedHashMap<>();
        assignments.put("name", lit(1, 20, "Bob", Kind.STRING));
        ASTNode.UpdateStmt stmt = new ASTNode.UpdateStmt(1, 1, "student", assignments, null);

        PlanNode plan = generator.generate(stmt);

        String json = generator.toJson(plan);

        assertTrue(json.contains("\"op\":\"update\""));
        assertTrue(json.contains("\"set\":{\"name\":\"Bob\"}"));
        assertFalse(json.contains("\"condition\""));
    }

    @Test
    void toJson_deleteWithCondition() {
        ASTNode.IdentifierExpr col = new ASTNode.IdentifierExpr(1, 10, "id");
        ASTNode.BinaryExpr cond = new ASTNode.BinaryExpr(1, 12, "=", col, lit(1, 15, "1", Kind.NUMBER));
        ASTNode.DeleteStmt stmt = new ASTNode.DeleteStmt(1, 1, "student", cond);

        PlanNode plan = generator.generate(stmt);

        String json = generator.toJson(plan);

        assertTrue(json.contains("\"op\":\"delete\""));
        assertTrue(json.contains("\"table\":\"student\""));
        assertTrue(json.contains("\"condition\""));
    }

    @Test
    void toJson_deleteNoCondition() {
        ASTNode.DeleteStmt stmt = new ASTNode.DeleteStmt(1, 1, "student", null);
        PlanNode plan = generator.generate(stmt);

        String json = generator.toJson(plan);

        assertTrue(json.contains("\"op\":\"delete\""));
        assertFalse(json.contains("\"condition\""));
    }

    @Test
    void toJson_createTable() {
        ASTNode.CreateTableStmt stmt = new ASTNode.CreateTableStmt(1, 1, "course", Arrays.asList(
                new ASTNode.CreateTableStmt.ColumnDef("cid", "INT"),
                new ASTNode.CreateTableStmt.ColumnDef("cname", "VARCHAR")));
        PlanNode plan = generator.generate(stmt);

        String json = generator.toJson(plan);

        assertTrue(json.contains("\"op\":\"createTable\""));
        assertTrue(json.contains("\"table\":\"course\""));
        assertTrue(json.contains("\"columns\":[\"cid\",\"cname\"]"));
    }

    @Test
    void toJson_booleanLiteralValue() {
        ASTNode.IdentifierExpr col = new ASTNode.IdentifierExpr(1, 10, "id");
        ASTNode.BinaryExpr cond = new ASTNode.BinaryExpr(1, 12, "=", col, lit(1, 15, "true", Kind.BOOLEAN));

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("id"), cond, List.of(), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        String json = generator.toJson(plan);

        // BOOLEAN 值应该是 JSON 原生 true/false（不带引号）
        assertTrue(json.contains("\"value\":true"));
        assertFalse(json.contains("\"true\""));
    }

    @Test
    void toJson_stringLiteralValue() {
        ASTNode.IdentifierExpr col = new ASTNode.IdentifierExpr(1, 10, "name");
        ASTNode.BinaryExpr cond = new ASTNode.BinaryExpr(1, 12, "=", col, lit(1, 15, "Alice", Kind.STRING));

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("id"), cond, List.of(), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        String json = generator.toJson(plan);

        // STRING 值应该带引号
        assertTrue(json.contains("\"value\":\"Alice\""));
    }

    @Test
    void toJson_nestedBinaryExpr() {
        // WHERE (id > 1) AND (age < 20)
        ASTNode.BinaryExpr left = new ASTNode.BinaryExpr(1, 9, ">",
                new ASTNode.IdentifierExpr(1, 8, "id"), lit(1, 12, "1", Kind.NUMBER));
        ASTNode.BinaryExpr right = new ASTNode.BinaryExpr(1, 19, "<",
                new ASTNode.IdentifierExpr(1, 18, "age"), lit(1, 22, "20", Kind.NUMBER));
        ASTNode.BinaryExpr cond = new ASTNode.BinaryExpr(1, 14, "AND", left, right);

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("id"), cond, List.of(), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        String json = generator.toJson(plan);

        // 验证嵌套二元表达式正确序列化
        assertTrue(json.contains("\"op\":\"AND\""));
        assertTrue(json.contains("\"op\":\">\""));
        assertTrue(json.contains("\"op\":\"<\""));
    }

    @Test
    void toJson_singleLineNoNewline() {
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("id"), null, List.of(), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        String json = generator.toJson(plan);

        assertFalse(json.contains("\n"));
        assertFalse(json.contains("\r"));
    }

    @Test
    void toJson_stringEscaping() {
        // 值含特殊字符（双引号、反斜杠）
        Map<String, ASTNode.LiteralExpr> assignments = new LinkedHashMap<>();
        assignments.put("name", lit(1, 20, "hello\"world\\test", Kind.STRING));
        ASTNode.UpdateStmt stmt = new ASTNode.UpdateStmt(1, 1, "student", assignments, null);

        PlanNode plan = generator.generate(stmt);

        String json = generator.toJson(plan);

        // 转义后的双引号和反斜杠
        assertTrue(json.contains("hello\\\"world\\\\test"));
    }

    @Test
    void toJson_matchesStorageSpec_selectExample() {
        // 验证与 storage/readme.md 中 SELECT 示例的结构一致
        // SELECT id, name FROM student WHERE id > 1
        ASTNode.BinaryExpr cond = new ASTNode.BinaryExpr(
                1, 10, ">",
                new ASTNode.IdentifierExpr(1, 9, "id"),
                lit(1, 13, "1", Kind.NUMBER));
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("id", "name"), cond, List.of(), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        String json = generator.toJson(plan);

        // 和 readme 示例的关键字段对比
        assertTrue(json.startsWith("{\"op\":\"project\",\"columns\":[\"id\",\"name\"],\"child\":"));
        assertTrue(json.contains("\"op\":\"filter\",\"condition\":{\"type\":\"binary\",\"op\":\">\""));
        assertTrue(json.contains("\"left\":{\"type\":\"column\",\"name\":\"id\"}"));
        assertTrue(json.contains("\"right\":{\"type\":\"literal\",\"value\":1}"));
        assertTrue(json.contains("\"op\":\"scan\",\"table\":\"student\""));
        // 列裁剪：SeqScan 应包含 columns 字段（只读 id, name）
        assertTrue(json.contains("\"columns\":[\"id\",\"name\"]"));
    }

    // ========== SHOW TABLES ==========

    @Test
    void generate_showTables() {
        ASTNode.ShowStmt stmt = new ASTNode.ShowStmt(1, 1, "TABLES", null);
        PlanNode plan = generator.generate(stmt);
        assertInstanceOf(PlanNode.ShowTablesPlan.class, plan);
    }

    @Test
    void toJson_showTables() {
        ASTNode.ShowStmt stmt = new ASTNode.ShowStmt(1, 1, "TABLES", null);
        PlanNode plan = generator.generate(stmt);
        String json = generator.toJson(plan);
        assertTrue(json.contains("\"op\":\"showTables\""));
    }

    @Test
    void formatPlan_showTables() {
        ASTNode.ShowStmt stmt = new ASTNode.ShowStmt(1, 1, "TABLES", null);
        PlanNode plan = generator.generate(stmt);
        String output = PlanNode.formatPlan(plan);
        assertTrue(output.contains("ShowTablesPlan"));
    }

    @Test
    void generate_showTable_describe() {
        ASTNode.ShowStmt stmt = new ASTNode.ShowStmt(1, 1, "TABLE", "student");
        PlanNode plan = generator.generate(stmt);
        assertInstanceOf(PlanNode.DescribeTablePlan.class, plan);
        assertTrue(generator.toJson(plan).contains("\"op\":\"describeTable\""));
    }

    // ========== DROP TABLE ==========

    @Test
    void generate_dropTable() {
        ASTNode.DropTableStmt stmt = new ASTNode.DropTableStmt(1, 1, "student");
        PlanNode plan = generator.generate(stmt);
        assertInstanceOf(PlanNode.DropTablePlan.class, plan);
        assertEquals("student", ((PlanNode.DropTablePlan) plan).getTableName());
    }

    @Test
    void toJson_dropTable() {
        ASTNode.DropTableStmt stmt = new ASTNode.DropTableStmt(1, 1, "course");
        PlanNode plan = generator.generate(stmt);
        String json = generator.toJson(plan);
        assertTrue(json.contains("\"op\":\"dropTable\""));
        assertTrue(json.contains("\"table\":\"course\""));
    }

    @Test
    void formatPlan_dropTable() {
        ASTNode.DropTableStmt stmt = new ASTNode.DropTableStmt(1, 1, "student");
        PlanNode plan = generator.generate(stmt);
        String output = PlanNode.formatPlan(plan);
        assertTrue(output.contains("DropTablePlan"));
        assertTrue(output.contains("student"));
    }

    // ========== AND/OR 优化 ==========

    @Test
    void optimize_andWithTrue_simplifiesToLeft() {
        // WHERE (id > 1) AND TRUE → (id > 1)
        ASTNode.BinaryExpr left = new ASTNode.BinaryExpr(1, 9, ">",
                new ASTNode.IdentifierExpr(1, 8, "id"), lit(1, 12, "1", Kind.NUMBER));
        ASTNode.BinaryExpr cond = new ASTNode.BinaryExpr(1, 14, "AND",
                left, lit(1, 20, "true", Kind.BOOLEAN));

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(1, 1, "student", Arrays.asList("id"), cond, List.of(), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        // 根节点是 ProjectPlan，child 是 FilterPlan
        assertInstanceOf(PlanNode.ProjectPlan.class, plan);
        PlanNode.FilterPlan filter = (PlanNode.FilterPlan) ((PlanNode.ProjectPlan) plan).getChild();
        ASTNode optimized = filter.getCondition();
        // 应该化简为左侧的 id > 1
        assertInstanceOf(ASTNode.BinaryExpr.class, optimized);
        assertEquals(">", ((ASTNode.BinaryExpr) optimized).getOp());
    }

    @Test
    void optimize_orWithFalse_simplifiesToLeft() {
        // WHERE (id > 1) OR FALSE → (id > 1)
        ASTNode.BinaryExpr left = new ASTNode.BinaryExpr(1, 9, ">",
                new ASTNode.IdentifierExpr(1, 8, "id"), lit(1, 12, "1", Kind.NUMBER));
        ASTNode.BinaryExpr cond = new ASTNode.BinaryExpr(1, 14, "OR",
                left, lit(1, 20, "false", Kind.BOOLEAN));

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(1, 1, "student", Arrays.asList("id"), cond, List.of(), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        assertInstanceOf(PlanNode.ProjectPlan.class, plan);
        PlanNode.FilterPlan filter = (PlanNode.FilterPlan) ((PlanNode.ProjectPlan) plan).getChild();
        ASTNode optimized = filter.getCondition();
        assertInstanceOf(ASTNode.BinaryExpr.class, optimized);
        assertEquals(">", ((ASTNode.BinaryExpr) optimized).getOp());
    }

    @Test
    void optimize_andWithFalse_simplifiesToFalse() {
        // WHERE (id > 1) AND FALSE → FALSE (恒假)
        ASTNode.BinaryExpr left = new ASTNode.BinaryExpr(1, 9, ">",
                new ASTNode.IdentifierExpr(1, 8, "id"), lit(1, 12, "1", Kind.NUMBER));
        ASTNode.BinaryExpr cond = new ASTNode.BinaryExpr(1, 14, "AND",
                left, lit(1, 20, "false", Kind.BOOLEAN));

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(1, 1, "student", Arrays.asList("id"), cond, List.of(), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        assertInstanceOf(PlanNode.ProjectPlan.class, plan);
        PlanNode.FilterPlan filter = (PlanNode.FilterPlan) ((PlanNode.ProjectPlan) plan).getChild();
        ASTNode optimized = filter.getCondition();
        assertInstanceOf(LiteralExpr.class, optimized);
        assertEquals("false", ((LiteralExpr) optimized).getValue());
    }

    @Test
    void optimize_orWithTrue_simplifiesToTrue() {
        // WHERE (id > 1) OR TRUE → TRUE (恒真，跳过 Filter)
        ASTNode.BinaryExpr left = new ASTNode.BinaryExpr(1, 9, ">",
                new ASTNode.IdentifierExpr(1, 8, "id"), lit(1, 12, "1", Kind.NUMBER));
        ASTNode.BinaryExpr cond = new ASTNode.BinaryExpr(1, 14, "OR",
                left, lit(1, 20, "true", Kind.BOOLEAN));

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(1, 1, "student", Arrays.asList("id"), cond, List.of(), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        // 恒真跳过 Filter，应该是 Project → SeqScan
        assertInstanceOf(PlanNode.ProjectPlan.class, plan);
        assertInstanceOf(PlanNode.SeqScanPlan.class, ((PlanNode.ProjectPlan) plan).getChild());
    }

    @Test
    void toJson_andOrCondition() {
        // WHERE (id > 1) AND (age < 20)
        ASTNode.BinaryExpr left = new ASTNode.BinaryExpr(1, 9, ">",
                new ASTNode.IdentifierExpr(1, 8, "id"), lit(1, 12, "1", Kind.NUMBER));
        ASTNode.BinaryExpr right = new ASTNode.BinaryExpr(1, 19, "<",
                new ASTNode.IdentifierExpr(1, 18, "age"), lit(1, 22, "20", Kind.NUMBER));
        ASTNode.BinaryExpr cond = new ASTNode.BinaryExpr(1, 14, "AND", left, right);

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(1, 1, "student", Arrays.asList("id"), cond, List.of(), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        String json = generator.toJson(plan);
        assertTrue(json.contains("\"op\":\"AND\""));
        assertTrue(json.contains("\"op\":\">\""));
        assertTrue(json.contains("\"op\":\"<\""));
    }

    // ========== 辅助方法 ==========

    private ASTNode.LiteralExpr lit(int line, int col, String value, Kind kind) {
        return new ASTNode.LiteralExpr(line, col, value, kind);
    }

    private PlanNode getChild(PlanNode plan) {
        if (plan instanceof PlanNode.ProjectPlan p) return p.getChild();
        if (plan instanceof PlanNode.FilterPlan f) return f.getChild();
        return null;
    }

    private void ProjectPlanCheck(PlanNode plan, List<String> expectedColumns) {
        assertTrue(plan instanceof PlanNode.ProjectPlan);
        assertEquals(expectedColumns, ((PlanNode.ProjectPlan) plan).getColumns());
    }

    /** 从 SeqScan 或 Filter→SeqScan 中提取表名 */
    private String getScanTableName(PlanNode node) {
        if (node instanceof PlanNode.SeqScanPlan s) return s.getTableName();
        if (node instanceof PlanNode.FilterPlan f) return getScanTableName(f.getChild());
        return null;
    }

    // ========== DOUBLE 常量折叠 ==========

    @Test
    void constantFolding_doubleArithmetic() {
        // WHERE 1.5 + 2.5 > age → WHERE 4.0 > age
        ASTNode.BinaryExpr add = new ASTNode.BinaryExpr(
                1, 9, "+", lit(1, 8, "1.5", Kind.NUMBER), lit(1, 14, "2.5", Kind.NUMBER));
        ASTNode.IdentifierExpr col = new ASTNode.IdentifierExpr(1, 18, "age");
        ASTNode.BinaryExpr cond = new ASTNode.BinaryExpr(1, 16, ">", add, col);

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("id"), cond, List.of(), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        assertTrue(getChild(plan) instanceof PlanNode.FilterPlan);
        ASTNode.BinaryExpr filterCond = (ASTNode.BinaryExpr) ((PlanNode.FilterPlan) getChild(plan)).getCondition();
        assertTrue(filterCond.getLeft() instanceof ASTNode.LiteralExpr);
        String folded = ((ASTNode.LiteralExpr) filterCond.getLeft()).getValue();
        // 结果应为 4.0
        assertEquals(4.0, Double.parseDouble(folded), 0.0001);
    }

    @Test
    void constantFolding_mixedIntAndDouble() {
        // WHERE 2 + 0.5 > age → WHERE 2.5 > age（整数 + 小数 → 小数结果）
        ASTNode.BinaryExpr add = new ASTNode.BinaryExpr(
                1, 9, "+", lit(1, 8, "2", Kind.NUMBER), lit(1, 12, "0.5", Kind.NUMBER));
        ASTNode.IdentifierExpr col = new ASTNode.IdentifierExpr(1, 17, "age");
        ASTNode.BinaryExpr cond = new ASTNode.BinaryExpr(1, 15, ">", add, col);

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("id"), cond, List.of(), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        assertTrue(getChild(plan) instanceof PlanNode.FilterPlan);
        ASTNode.BinaryExpr filterCond = (ASTNode.BinaryExpr) ((PlanNode.FilterPlan) getChild(plan)).getCondition();
        assertTrue(filterCond.getLeft() instanceof ASTNode.LiteralExpr);
        String folded = ((ASTNode.LiteralExpr) filterCond.getLeft()).getValue();
        assertEquals(2.5, Double.parseDouble(folded), 0.0001);
        // 混合运算结果应为小数格式（含小数点）
        assertTrue(folded.contains("."));
    }

    @Test
    void constantFolding_intResult_staysInt() {
        // WHERE 2 + 3 > age → WHERE 5 > age（两个整数，结果为整数）
        ASTNode.BinaryExpr add = new ASTNode.BinaryExpr(
                1, 9, "+", lit(1, 8, "2", Kind.NUMBER), lit(1, 12, "3", Kind.NUMBER));
        ASTNode.IdentifierExpr col = new ASTNode.IdentifierExpr(1, 16, "age");
        ASTNode.BinaryExpr cond = new ASTNode.BinaryExpr(1, 14, ">", add, col);

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("id"), cond, List.of(), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        assertTrue(getChild(plan) instanceof PlanNode.FilterPlan);
        ASTNode.BinaryExpr filterCond = (ASTNode.BinaryExpr) ((PlanNode.FilterPlan) getChild(plan)).getCondition();
        assertTrue(filterCond.getLeft() instanceof ASTNode.LiteralExpr);
        assertEquals("5", ((ASTNode.LiteralExpr) filterCond.getLeft()).getValue());
    }

    @Test
    void constantFolding_doubleComparison_true() {
        // WHERE 3.14 > 2.5 → TRUE → 无 Filter
        ASTNode.BinaryExpr cond = new ASTNode.BinaryExpr(
                1, 10, ">", lit(1, 9, "3.14", Kind.NUMBER), lit(1, 16, "2.5", Kind.NUMBER));

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("id"), cond, List.of(), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        assertTrue(getChild(plan) instanceof PlanNode.SeqScanPlan);
    }

    @Test
    void constantFolding_doubleComparison_false() {
        // WHERE 1.5 > 2.5 → FALSE → Filter(FALSE)
        ASTNode.BinaryExpr cond = new ASTNode.BinaryExpr(
                1, 10, ">", lit(1, 9, "1.5", Kind.NUMBER), lit(1, 15, "2.5", Kind.NUMBER));

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("id"), cond, List.of(), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        assertTrue(getChild(plan) instanceof PlanNode.FilterPlan);
    }

    @Test
    void toJson_doubleLiteralInInsert() {
        // INSERT INTO student VALUES ... 含小数值
        catalog.createTableWithTypes("products", Arrays.asList(
                new CatalogImpl.ColumnInfo("id", CatalogImpl.DataType.INT),
                new CatalogImpl.ColumnInfo("price", CatalogImpl.DataType.DOUBLE)
        ));
        ASTNode.InsertStmt stmt = new ASTNode.InsertStmt(1, 1, "products",
                Arrays.asList("id", "price"),
                Arrays.asList(
                        lit(1, 25, "1", Kind.NUMBER),
                        lit(1, 30, "9.99", Kind.NUMBER)
                ));
        PlanNode plan = generator.generate(stmt);
        String json = generator.toJson(plan);

        // 9.99 应在 JSON 中作为原生数字输出（无引号）
        assertTrue(json.contains("9.99"));
        assertFalse(json.contains("\"9.99\"")); // 不应被引号包裹
    }

    @Test
    void toJson_doubleLiteralInCondition() {
        // WHERE score > 3.14 → JSON 中 3.14 为原生数字
        catalog.createTableWithTypes("scores", Arrays.asList(
                new CatalogImpl.ColumnInfo("score", CatalogImpl.DataType.DOUBLE)
        ));
        ASTNode.IdentifierExpr col = new ASTNode.IdentifierExpr(1, 10, "score");
        ASTNode.BinaryExpr cond = new ASTNode.BinaryExpr(1, 15, ">",
                col, lit(1, 18, "3.14", Kind.NUMBER));
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "scores", Arrays.asList("score"), cond, List.of(), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);
        String json = generator.toJson(plan);

        assertTrue(json.contains("\"value\":3.14"));
    }

    // ========== 多表 JOIN 计划生成 ==========

    @Test
    void join_generatesJoinPlan() {
        catalog.createTableWithTypes("course", Arrays.asList(
                new CatalogImpl.ColumnInfo("cid", CatalogImpl.DataType.INT),
                new CatalogImpl.ColumnInfo("cname", CatalogImpl.DataType.VARCHAR)
        ));
        // ON student.id = course.cid
        ASTNode.BinaryExpr onCond = new ASTNode.BinaryExpr(1, 30, "=",
                new ASTNode.IdentifierExpr(1, 28, "id"),
                new ASTNode.IdentifierExpr(1, 35, "cid"));
        ASTNode.SelectStmt.JoinClause join = new ASTNode.SelectStmt.JoinClause("course", onCond);

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("name", "cname"), null,
                List.of(join), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        // Project → Join → [SeqScan(student), SeqScan(course)]
        assertInstanceOf(PlanNode.ProjectPlan.class, plan);
        PlanNode joinNode = ((PlanNode.ProjectPlan) plan).getChild();
        assertInstanceOf(PlanNode.JoinPlan.class, joinNode);
        PlanNode.JoinPlan jp = (PlanNode.JoinPlan) joinNode;
        assertEquals(2, jp.getChildren().size());
        assertInstanceOf(PlanNode.SeqScanPlan.class, jp.getChildren().get(0));
        assertInstanceOf(PlanNode.SeqScanPlan.class, jp.getChildren().get(1));
        assertEquals("student", ((PlanNode.SeqScanPlan) jp.getChildren().get(0)).getTableName());
        assertEquals("course", ((PlanNode.SeqScanPlan) jp.getChildren().get(1)).getTableName());
        // 第一个 ON 条件为 null（左表），第二个为 onCond
        assertNull(jp.getOnConditions().get(0));
        assertNotNull(jp.getOnConditions().get(1));
    }

    @Test
    void join_withWhere_generatesFilterOnJoin() {
        catalog.createTableWithTypes("course", Arrays.asList(
                new CatalogImpl.ColumnInfo("cid", CatalogImpl.DataType.INT),
                new CatalogImpl.ColumnInfo("cname", CatalogImpl.DataType.VARCHAR)
        ));
        ASTNode.BinaryExpr onCond = new ASTNode.BinaryExpr(1, 30, "=",
                new ASTNode.IdentifierExpr(1, 28, "id"),
                new ASTNode.IdentifierExpr(1, 35, "cid"));
        ASTNode.SelectStmt.JoinClause join = new ASTNode.SelectStmt.JoinClause("course", onCond);

        // WHERE age > 18
        ASTNode.BinaryExpr whereCond = new ASTNode.BinaryExpr(1, 50, ">",
                new ASTNode.IdentifierExpr(1, 48, "age"),
                lit(1, 55, "18", Kind.NUMBER));

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("name"), whereCond,
                List.of(join), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        // 谓词下推：age > 18 只涉及 student → 下推到 SeqScan[student] 之上
        // Project → Join → [Filter[age>18] → SeqScan[student], SeqScan[course]]
        assertInstanceOf(PlanNode.ProjectPlan.class, plan);
        PlanNode joinNode = ((PlanNode.ProjectPlan) plan).getChild();
        assertInstanceOf(PlanNode.JoinPlan.class, joinNode);
        PlanNode.JoinPlan jp = (PlanNode.JoinPlan) joinNode;
        // student 子节点应有 Filter
        assertInstanceOf(PlanNode.FilterPlan.class, jp.getChildren().get(0));
        // course 子节点无 Filter
        assertInstanceOf(PlanNode.SeqScanPlan.class, jp.getChildren().get(1));
    }

    @Test
    void toJson_join() {
        catalog.createTableWithTypes("course", Arrays.asList(
                new CatalogImpl.ColumnInfo("cid", CatalogImpl.DataType.INT),
                new CatalogImpl.ColumnInfo("cname", CatalogImpl.DataType.VARCHAR)
        ));
        ASTNode.BinaryExpr onCond = new ASTNode.BinaryExpr(1, 30, "=",
                new ASTNode.IdentifierExpr(1, 28, "id"),
                new ASTNode.IdentifierExpr(1, 35, "cid"));
        ASTNode.SelectStmt.JoinClause join = new ASTNode.SelectStmt.JoinClause("course", onCond);

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("name"), null,
                List.of(join), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);
        String json = generator.toJson(plan);

        assertTrue(json.contains("\"op\":\"join\""));
        assertTrue(json.contains("\"children\":["));
        assertTrue(json.contains("\"table\":\"student\""));
        assertTrue(json.contains("\"table\":\"course\""));
        assertTrue(json.contains("\"on\":["));
        assertTrue(json.contains("null")); // 左表 ON 条件为 null
    }

    @Test
    void formatPlan_join() {
        catalog.createTableWithTypes("course", Arrays.asList(
                new CatalogImpl.ColumnInfo("cid", CatalogImpl.DataType.INT),
                new CatalogImpl.ColumnInfo("cname", CatalogImpl.DataType.VARCHAR)
        ));
        ASTNode.BinaryExpr onCond = new ASTNode.BinaryExpr(1, 30, "=",
                new ASTNode.IdentifierExpr(1, 28, "id"),
                new ASTNode.IdentifierExpr(1, 35, "cid"));
        ASTNode.SelectStmt.JoinClause join = new ASTNode.SelectStmt.JoinClause("course", onCond);

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("name"), null,
                List.of(join), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        String output = PlanNode.formatPlan(plan);
        assertTrue(output.contains("JoinPlan"));
        assertTrue(output.contains("SeqScanPlan"));
        assertTrue(output.contains("student"));
        assertTrue(output.contains("course"));
        assertTrue(output.contains("(左表)")); // 左表 ON 条件显示
    }

    // ========== GROUP BY 计划生成 ==========

    @Test
    void groupBy_generatesGroupByPlan() {
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("age"), null,
                List.of(), List.of("age"), List.of());
        PlanNode plan = generator.generate(stmt);

        // Project → GroupBy → SeqScan
        assertInstanceOf(PlanNode.ProjectPlan.class, plan);
        PlanNode groupBy = ((PlanNode.ProjectPlan) plan).getChild();
        assertInstanceOf(PlanNode.GroupByPlan.class, groupBy);
        assertEquals(Arrays.asList("age"), ((PlanNode.GroupByPlan) groupBy).getGroupByColumns());
        assertInstanceOf(PlanNode.SeqScanPlan.class, ((PlanNode.GroupByPlan) groupBy).getChild());
    }

    @Test
    void groupBy_withWhere_generatesFilterBeforeGroupBy() {
        ASTNode.BinaryExpr whereCond = new ASTNode.BinaryExpr(1, 20, ">",
                new ASTNode.IdentifierExpr(1, 18, "age"),
                lit(1, 25, "18", Kind.NUMBER));
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("age"), whereCond,
                List.of(), List.of("age"), List.of());
        PlanNode plan = generator.generate(stmt);

        // Project → GroupBy → Filter → SeqScan
        assertInstanceOf(PlanNode.ProjectPlan.class, plan);
        PlanNode groupBy = ((PlanNode.ProjectPlan) plan).getChild();
        assertInstanceOf(PlanNode.GroupByPlan.class, groupBy);
        PlanNode filter = ((PlanNode.GroupByPlan) groupBy).getChild();
        assertInstanceOf(PlanNode.FilterPlan.class, filter);
    }

    @Test
    void toJson_groupBy() {
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("age"), null,
                List.of(), List.of("age"), List.of());
        PlanNode plan = generator.generate(stmt);
        String json = generator.toJson(plan);

        assertTrue(json.contains("\"op\":\"groupBy\""));
        assertTrue(json.contains("\"columns\":[\"age\"]"));
    }

    @Test
    void formatPlan_groupBy() {
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("age"), null,
                List.of(), List.of("age"), List.of());
        PlanNode plan = generator.generate(stmt);

        String output = PlanNode.formatPlan(plan);
        assertTrue(output.contains("GroupByPlan"));
        assertTrue(output.contains("age"));
    }

    // ========== ORDER BY 计划生成 ==========

    @Test
    void orderBy_generatesOrderByPlan() {
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("id"), null,
                List.of(), List.of(),
                List.of(new ASTNode.SelectStmt.OrderItem("age", "ASC")));
        PlanNode plan = generator.generate(stmt);

        // Project → OrderBy → SeqScan
        assertInstanceOf(PlanNode.ProjectPlan.class, plan);
        PlanNode orderBy = ((PlanNode.ProjectPlan) plan).getChild();
        assertInstanceOf(PlanNode.OrderByPlan.class, orderBy);
        List<PlanNode.OrderByPlan.OrderItem> items = ((PlanNode.OrderByPlan) orderBy).getOrderByItems();
        assertEquals(1, items.size());
        assertEquals("age", items.get(0).getColumn());
        assertEquals("ASC", items.get(0).getDirection());
    }

    @Test
    void orderBy_descDirection_preserved() {
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("id"), null,
                List.of(), List.of(),
                List.of(new ASTNode.SelectStmt.OrderItem("age", "DESC")));
        PlanNode plan = generator.generate(stmt);

        PlanNode.OrderByPlan orderBy = (PlanNode.OrderByPlan) ((PlanNode.ProjectPlan) plan).getChild();
        assertEquals("DESC", orderBy.getOrderByItems().get(0).getDirection());
    }

    @Test
    void toJson_orderBy() {
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("id"), null,
                List.of(), List.of(),
                List.of(new ASTNode.SelectStmt.OrderItem("age", "DESC")));
        PlanNode plan = generator.generate(stmt);
        String json = generator.toJson(plan);

        assertTrue(json.contains("\"op\":\"orderBy\""));
        assertTrue(json.contains("\"column\":\"age\""));
        assertTrue(json.contains("\"direction\":\"DESC\""));
    }

    @Test
    void formatPlan_orderBy() {
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("id"), null,
                List.of(), List.of(),
                List.of(new ASTNode.SelectStmt.OrderItem("age", "ASC")));
        PlanNode plan = generator.generate(stmt);

        String output = PlanNode.formatPlan(plan);
        assertTrue(output.contains("OrderByPlan"));
        assertTrue(output.contains("age"));
        assertTrue(output.contains("ASC"));
    }

    // ========== 组合：JOIN + WHERE + GROUP BY + ORDER BY ==========

    @Test
    void join_where_groupBy_orderBy_fullPlanTree() {
        catalog.createTableWithTypes("course", Arrays.asList(
                new CatalogImpl.ColumnInfo("cid", CatalogImpl.DataType.INT),
                new CatalogImpl.ColumnInfo("cname", CatalogImpl.DataType.VARCHAR)
        ));
        // ON student.id = course.cid
        ASTNode.BinaryExpr onCond = new ASTNode.BinaryExpr(1, 30, "=",
                new ASTNode.IdentifierExpr(1, 28, "id"),
                new ASTNode.IdentifierExpr(1, 35, "cid"));
        ASTNode.SelectStmt.JoinClause join = new ASTNode.SelectStmt.JoinClause("course", onCond);

        // WHERE age > 18
        ASTNode.BinaryExpr whereCond = new ASTNode.BinaryExpr(1, 50, ">",
                new ASTNode.IdentifierExpr(1, 48, "age"),
                lit(1, 55, "18", Kind.NUMBER));

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("name", "cname"), whereCond,
                List.of(join), List.of("age"),
                List.of(new ASTNode.SelectStmt.OrderItem("cname", "ASC")));
        PlanNode plan = generator.generate(stmt);

        // 谓词下推：age > 18 只涉及 student → 下推到 SeqScan[student] 之上
        // Project → OrderBy → GroupBy → Join → [Filter[age>18]→SeqScan[student], SeqScan[course]]
        assertInstanceOf(PlanNode.ProjectPlan.class, plan);
        PlanNode orderBy = ((PlanNode.ProjectPlan) plan).getChild();
        assertInstanceOf(PlanNode.OrderByPlan.class, orderBy);

        PlanNode groupBy = ((PlanNode.OrderByPlan) orderBy).getChild();
        assertInstanceOf(PlanNode.GroupByPlan.class, groupBy);

        PlanNode joinNode = ((PlanNode.GroupByPlan) groupBy).getChild();
        assertInstanceOf(PlanNode.JoinPlan.class, joinNode);
        PlanNode.JoinPlan jp = (PlanNode.JoinPlan) joinNode;
        assertEquals(2, jp.getChildren().size());
        // student 子节点应有下推的 Filter
        assertInstanceOf(PlanNode.FilterPlan.class, jp.getChildren().get(0));
    }

    @Test
    void toJson_joinWhereGroupByOrderBy() {
        catalog.createTableWithTypes("course", Arrays.asList(
                new CatalogImpl.ColumnInfo("cid", CatalogImpl.DataType.INT),
                new CatalogImpl.ColumnInfo("cname", CatalogImpl.DataType.VARCHAR)
        ));
        ASTNode.BinaryExpr onCond = new ASTNode.BinaryExpr(1, 30, "=",
                new ASTNode.IdentifierExpr(1, 28, "id"),
                new ASTNode.IdentifierExpr(1, 35, "cid"));
        ASTNode.SelectStmt.JoinClause join = new ASTNode.SelectStmt.JoinClause("course", onCond);

        ASTNode.BinaryExpr whereCond = new ASTNode.BinaryExpr(1, 50, ">",
                new ASTNode.IdentifierExpr(1, 48, "age"),
                lit(1, 55, "18", Kind.NUMBER));

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("name"), whereCond,
                List.of(join), List.of("age"),
                List.of(new ASTNode.SelectStmt.OrderItem("cname", "ASC")));
        PlanNode plan = generator.generate(stmt);
        String json = generator.toJson(plan);

        // 所有节点类型都应该出现在 JSON 中
        assertTrue(json.contains("\"op\":\"project\""));
        assertTrue(json.contains("\"op\":\"orderBy\""));
        assertTrue(json.contains("\"op\":\"groupBy\""));
        assertTrue(json.contains("\"op\":\"filter\""));
        assertTrue(json.contains("\"op\":\"join\""));
        assertTrue(json.contains("\"op\":\"scan\""));
        assertFalse(json.contains("\n"));
    }

    // ========== NOT 一元表达式的 JSON 序列化 ==========

    @Test
    void toJson_notExpr_serializedAsUnary() {
        // WHERE NOT (age > 18)
        ASTNode.BinaryExpr inner = new ASTNode.BinaryExpr(1, 20, ">",
                new ASTNode.IdentifierExpr(1, 18, "age"),
                lit(1, 25, "18", Kind.NUMBER));
        ASTNode.UnaryExpr notExpr = new ASTNode.UnaryExpr(1, 10, "NOT", inner);

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("id"), notExpr,
                List.of(), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);
        String json = generator.toJson(plan);

        // 应包含 unary 节点，op 为 NOT，operand 为内层 binary
        assertTrue(json.contains("\"type\":\"unary\""));
        assertTrue(json.contains("\"op\":\"NOT\""));
        assertTrue(json.contains("\"operand\":"));
        assertTrue(json.contains("\"type\":\"binary\""));
        assertTrue(json.contains("\"op\":\">\""));
        assertTrue(json.contains("\"name\":\"age\""));
        assertTrue(json.contains("\"value\":18"));
    }

    @Test
    void toJson_nestedNotNotExpr_serializedCorrectly() {
        // WHERE NOT NOT (age > 18) — 双重否定未化简时（操作数非常量）
        ASTNode.BinaryExpr inner = new ASTNode.BinaryExpr(1, 20, ">",
                new ASTNode.IdentifierExpr(1, 18, "age"),
                lit(1, 25, "18", Kind.NUMBER));
        ASTNode.UnaryExpr notInner = new ASTNode.UnaryExpr(1, 10, "NOT", inner);
        ASTNode.UnaryExpr notNot = new ASTNode.UnaryExpr(1, 5, "NOT", notInner);

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("id"), notNot,
                List.of(), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);
        String json = generator.toJson(plan);

        // 双重否定会被 optimizeExpr 消除为内层 age > 18，所以 JSON 中应只有 binary
        assertTrue(json.contains("\"type\":\"binary\""));
        assertTrue(json.contains("\"op\":\">\""));
        // 不应出现 unary（已被消除）
        assertFalse(json.contains("\"type\":\"unary\""));
    }

    // ========== NOT 一元表达式的常量折叠/化简 ==========

    @Test
    void optimize_notTrue_foldsToFalse() {
        // WHERE NOT TRUE → FALSE → 保留 Filter(FALSE) 返回空集
        ASTNode.LiteralExpr trueLit = new ASTNode.LiteralExpr(1, 10, "true", Kind.BOOLEAN);
        ASTNode.UnaryExpr notTrue = new ASTNode.UnaryExpr(1, 5, "NOT", trueLit);

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("id"), notTrue,
                List.of(), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        // Project → Filter(FALSE) → SeqScan
        assertInstanceOf(PlanNode.ProjectPlan.class, plan);
        PlanNode filter = ((PlanNode.ProjectPlan) plan).getChild();
        assertInstanceOf(PlanNode.FilterPlan.class, filter);
        // Filter 条件应为 FALSE 字面量
        ASTNode cond = ((PlanNode.FilterPlan) filter).getCondition();
        assertInstanceOf(ASTNode.LiteralExpr.class, cond);
        assertEquals("false", ((ASTNode.LiteralExpr) cond).getValue());
        assertEquals(Kind.BOOLEAN, ((ASTNode.LiteralExpr) cond).getKind());
    }

    @Test
    void optimize_notFalse_foldsToTrue_skipsFilter() {
        // WHERE NOT FALSE → TRUE → 跳过 Filter
        ASTNode.LiteralExpr falseLit = new ASTNode.LiteralExpr(1, 10, "false", Kind.BOOLEAN);
        ASTNode.UnaryExpr notFalse = new ASTNode.UnaryExpr(1, 5, "NOT", falseLit);

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("id"), notFalse,
                List.of(), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        // 恒真 → 跳过 Filter → Project 直接接 SeqScan
        assertInstanceOf(PlanNode.ProjectPlan.class, plan);
        PlanNode child = ((PlanNode.ProjectPlan) plan).getChild();
        assertInstanceOf(PlanNode.SeqScanPlan.class, child);
    }

    @Test
    void optimize_notOfConstantComparison_foldsInnerFirst() {
        // WHERE NOT (1 = 1) → NOT TRUE → FALSE
        ASTNode.BinaryExpr inner = new ASTNode.BinaryExpr(1, 20, "=",
                lit(1, 15, "1", Kind.NUMBER),
                lit(1, 19, "1", Kind.NUMBER));
        ASTNode.UnaryExpr notExpr = new ASTNode.UnaryExpr(1, 5, "NOT", inner);

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("id"), notExpr,
                List.of(), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        // 应折叠为 FALSE → Filter(FALSE) → SeqScan
        PlanNode filter = ((PlanNode.ProjectPlan) plan).getChild();
        assertInstanceOf(PlanNode.FilterPlan.class, filter);
        ASTNode cond = ((PlanNode.FilterPlan) filter).getCondition();
        assertInstanceOf(ASTNode.LiteralExpr.class, cond);
        assertEquals("false", ((ASTNode.LiteralExpr) cond).getValue());
    }

    @Test
    void optimize_notOfArithmeticComparison_foldsInnerArithmetic() {
        // WHERE NOT (1+2 > 3) → NOT (3 > 3) → NOT FALSE → TRUE → 跳过 Filter
        ASTNode.BinaryExpr add = new ASTNode.BinaryExpr(1, 15, "+",
                lit(1, 10, "1", Kind.NUMBER),
                lit(1, 12, "2", Kind.NUMBER));
        ASTNode.BinaryExpr cmp = new ASTNode.BinaryExpr(1, 20, ">",
                add, lit(1, 18, "3", Kind.NUMBER));
        ASTNode.UnaryExpr notExpr = new ASTNode.UnaryExpr(1, 5, "NOT", cmp);

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("id"), notExpr,
                List.of(), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        // 1+2=3, 3>3 为 FALSE, NOT FALSE 为 TRUE → 跳过 Filter
        PlanNode child = ((PlanNode.ProjectPlan) plan).getChild();
        assertInstanceOf(PlanNode.SeqScanPlan.class, child);
    }

    @Test
    void optimize_notNotColumnExpr_eliminatedToInner() {
        // WHERE NOT NOT (age > 18) → age > 18（消除双重否定）
        ASTNode.BinaryExpr inner = new ASTNode.BinaryExpr(1, 20, ">",
                new ASTNode.IdentifierExpr(1, 18, "age"),
                lit(1, 25, "18", Kind.NUMBER));
        ASTNode.UnaryExpr notInner = new ASTNode.UnaryExpr(1, 10, "NOT", inner);
        ASTNode.UnaryExpr notNot = new ASTNode.UnaryExpr(1, 5, "NOT", notInner);

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("id"), notNot,
                List.of(), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        // 双重否定消除 → 留下 age > 18 的 Filter
        PlanNode filter = ((PlanNode.ProjectPlan) plan).getChild();
        assertInstanceOf(PlanNode.FilterPlan.class, filter);
        ASTNode cond = ((PlanNode.FilterPlan) filter).getCondition();
        // 应是 BinaryExpr（age > 18），不是 UnaryExpr
        assertInstanceOf(ASTNode.BinaryExpr.class, cond);
        assertEquals(">", ((ASTNode.BinaryExpr) cond).getOp());
    }

    @Test
    void optimize_notOfColumnExpr_keptAsUnary() {
        // WHERE NOT (age > 18) — 操作数非常量，保留 UnaryExpr
        ASTNode.BinaryExpr inner = new ASTNode.BinaryExpr(1, 20, ">",
                new ASTNode.IdentifierExpr(1, 18, "age"),
                lit(1, 25, "18", Kind.NUMBER));
        ASTNode.UnaryExpr notExpr = new ASTNode.UnaryExpr(1, 5, "NOT", inner);

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("id"), notExpr,
                List.of(), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        // 保留为 NOT (age > 18) 的 Filter
        PlanNode filter = ((PlanNode.ProjectPlan) plan).getChild();
        assertInstanceOf(PlanNode.FilterPlan.class, filter);
        ASTNode cond = ((PlanNode.FilterPlan) filter).getCondition();
        assertInstanceOf(ASTNode.UnaryExpr.class, cond);
        assertEquals("NOT", ((ASTNode.UnaryExpr) cond).getOp());
    }

    // ========== 谓词下推 ==========

    @Test
    void predicatePushdown_singleTableWhere_filterOnSeqScan() {
        // WHERE age > 18 → Filter 直接在 SeqScan 之上（而非 Project 之下）
        ASTNode.BinaryExpr cond = new ASTNode.BinaryExpr(1, 20, ">",
                new ASTNode.IdentifierExpr(1, 18, "age"),
                lit(1, 25, "18", Kind.NUMBER));
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("id"), cond,
                List.of(), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        // Project → Filter → SeqScan
        assertInstanceOf(PlanNode.ProjectPlan.class, plan);
        PlanNode filter = ((PlanNode.ProjectPlan) plan).getChild();
        assertInstanceOf(PlanNode.FilterPlan.class, filter);
        assertInstanceOf(PlanNode.SeqScanPlan.class, ((PlanNode.FilterPlan) filter).getChild());
    }

    @Test
    void predicatePushdown_joinWhere_singleTablePredicate_pushedToScan() {
        // WHERE age > 18 (student) → 下推到 SeqScan[student]
        catalog.createTableWithTypes("course", Arrays.asList(
                new CatalogImpl.ColumnInfo("cid", CatalogImpl.DataType.INT),
                new CatalogImpl.ColumnInfo("cname", CatalogImpl.DataType.VARCHAR)
        ));
        ASTNode.BinaryExpr onCond = new ASTNode.BinaryExpr(1, 30, "=",
                new ASTNode.IdentifierExpr(1, 28, "id"),
                new ASTNode.IdentifierExpr(1, 35, "cid"));
        ASTNode.SelectStmt.JoinClause join = new ASTNode.SelectStmt.JoinClause("course", onCond);

        ASTNode.BinaryExpr whereCond = new ASTNode.BinaryExpr(1, 50, ">",
                new ASTNode.IdentifierExpr(1, 48, "age"),
                lit(1, 55, "18", Kind.NUMBER));

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("name"), whereCond,
                List.of(join), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        // Project → Join → [Filter[age>18]→SeqScan[student], SeqScan[course]]
        PlanNode joinNode = ((PlanNode.ProjectPlan) plan).getChild();
        assertInstanceOf(PlanNode.JoinPlan.class, joinNode);
        PlanNode.JoinPlan jp = (PlanNode.JoinPlan) joinNode;
        // student 有下推的 Filter
        assertInstanceOf(PlanNode.FilterPlan.class, jp.getChildren().get(0));
        // course 无 Filter
        assertInstanceOf(PlanNode.SeqScanPlan.class, jp.getChildren().get(1));
    }

    @Test
    void predicatePushdown_joinWhere_crossTablePredicate_staysOnJoin() {
        // WHERE student.id = course.cid → 跨表条件，留在 Join 之上
        catalog.createTableWithTypes("course", Arrays.asList(
                new CatalogImpl.ColumnInfo("cid", CatalogImpl.DataType.INT),
                new CatalogImpl.ColumnInfo("cname", CatalogImpl.DataType.VARCHAR)
        ));
        ASTNode.BinaryExpr onCond = new ASTNode.BinaryExpr(1, 30, "=",
                new ASTNode.IdentifierExpr(1, 28, "student.id"),
                new ASTNode.IdentifierExpr(1, 40, "course.cid"));
        ASTNode.SelectStmt.JoinClause join = new ASTNode.SelectStmt.JoinClause("course", onCond);

        // WHERE student.age > course.credit — 跨表条件
        ASTNode.BinaryExpr whereCond = new ASTNode.BinaryExpr(1, 50, ">",
                new ASTNode.IdentifierExpr(1, 48, "student.age"),
                new ASTNode.IdentifierExpr(1, 60, "course.cname"));

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("student.name"), whereCond,
                List.of(join), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        // Project → Filter → Join → [SeqScan, SeqScan]
        // 跨表谓词留在 Join 之上
        PlanNode filter = ((PlanNode.ProjectPlan) plan).getChild();
        assertInstanceOf(PlanNode.FilterPlan.class, filter);
        PlanNode joinNode = ((PlanNode.FilterPlan) filter).getChild();
        assertInstanceOf(PlanNode.JoinPlan.class, joinNode);
    }

    @Test
    void predicatePushdown_joinWhereMixed_singlePushedCrossKept() {
        // WHERE age > 18 AND student.id = course.cid
        // age > 18 下推到 student；student.id = course.cid 留在 Join
        catalog.createTableWithTypes("course", Arrays.asList(
                new CatalogImpl.ColumnInfo("cid", CatalogImpl.DataType.INT),
                new CatalogImpl.ColumnInfo("cname", CatalogImpl.DataType.VARCHAR)
        ));
        ASTNode.BinaryExpr onCond = new ASTNode.BinaryExpr(1, 30, "=",
                new ASTNode.IdentifierExpr(1, 28, "student.id"),
                new ASTNode.IdentifierExpr(1, 40, "course.cid"));
        ASTNode.SelectStmt.JoinClause join = new ASTNode.SelectStmt.JoinClause("course", onCond);

        ASTNode.BinaryExpr singleTable = new ASTNode.BinaryExpr(1, 50, ">",
                new ASTNode.IdentifierExpr(1, 48, "age"),
                lit(1, 55, "18", Kind.NUMBER));
        ASTNode.BinaryExpr crossTable = new ASTNode.BinaryExpr(1, 65, "=",
                new ASTNode.IdentifierExpr(1, 63, "student.id"),
                new ASTNode.IdentifierExpr(1, 75, "course.cid"));
        ASTNode.BinaryExpr whereCond = new ASTNode.BinaryExpr(1, 60, "AND", singleTable, crossTable);

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("student.name"), whereCond,
                List.of(join), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        // Project → Filter[student.id=course.cid] → Join → [Filter[age>18]→SeqScan[student], SeqScan[course]]
        PlanNode filter = ((PlanNode.ProjectPlan) plan).getChild();
        assertInstanceOf(PlanNode.FilterPlan.class, filter);  // 跨表 Filter
        PlanNode joinNode = ((PlanNode.FilterPlan) filter).getChild();
        assertInstanceOf(PlanNode.JoinPlan.class, joinNode);
        PlanNode.JoinPlan jp = (PlanNode.JoinPlan) joinNode;
        // student 有下推的 Filter
        assertInstanceOf(PlanNode.FilterPlan.class, jp.getChildren().get(0));
        // course 无 Filter
        assertInstanceOf(PlanNode.SeqScanPlan.class, jp.getChildren().get(1));
    }

    // ========== 列裁剪 / 投影下推 ==========

    @Test
    void columnPruning_singleTable_onlyNeededColumns() {
        // SELECT name FROM student → SeqScan 只读 name
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("name"), null,
                List.of(), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        // Project → SeqScan[columns: [name]]
        assertInstanceOf(PlanNode.ProjectPlan.class, plan);
        PlanNode scan = ((PlanNode.ProjectPlan) plan).getChild();
        assertInstanceOf(PlanNode.SeqScanPlan.class, scan);
        List<String> cols = ((PlanNode.SeqScanPlan) scan).getColumns();
        assertNotNull(cols);
        assertEquals(1, cols.size());
        assertTrue(cols.contains("name"));
    }

    @Test
    void columnPruning_whereAddsNeededColumns() {
        // SELECT name FROM student WHERE age > 18 → SeqScan 读 name + age
        ASTNode.BinaryExpr cond = new ASTNode.BinaryExpr(1, 20, ">",
                new ASTNode.IdentifierExpr(1, 18, "age"),
                lit(1, 25, "18", Kind.NUMBER));
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("name"), cond,
                List.of(), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        // Project → Filter → SeqScan[columns: name, age]
        PlanNode filter = ((PlanNode.ProjectPlan) plan).getChild();
        PlanNode scan = ((PlanNode.FilterPlan) filter).getChild();
        assertInstanceOf(PlanNode.SeqScanPlan.class, scan);
        List<String> cols = ((PlanNode.SeqScanPlan) scan).getColumns();
        assertNotNull(cols);
        assertEquals(2, cols.size());
        assertTrue(cols.contains("name"));
        assertTrue(cols.contains("age"));
    }

    @Test
    void columnPruning_orderByAddsNeededColumns() {
        // SELECT name FROM student ORDER BY age → SeqScan 读 name + age
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("name"), null,
                List.of(), List.of(),
                List.of(new ASTNode.SelectStmt.OrderItem("age", "ASC")));
        PlanNode plan = generator.generate(stmt);

        // Project → OrderBy → SeqScan[columns: name, age]
        PlanNode orderBy = ((PlanNode.ProjectPlan) plan).getChild();
        PlanNode scan = ((PlanNode.OrderByPlan) orderBy).getChild();
        assertInstanceOf(PlanNode.SeqScanPlan.class, scan);
        List<String> cols = ((PlanNode.SeqScanPlan) scan).getColumns();
        assertNotNull(cols);
        assertTrue(cols.contains("name"));
        assertTrue(cols.contains("age"));
    }

    @Test
    void columnPruning_groupByAddsNeededColumns() {
        // SELECT age FROM student GROUP BY age → SeqScan 读 age
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("age"), null,
                List.of(), List.of("age"), List.of());
        PlanNode plan = generator.generate(stmt);

        // Project → GroupBy → SeqScan[columns: age]
        PlanNode groupBy = ((PlanNode.ProjectPlan) plan).getChild();
        PlanNode scan = ((PlanNode.GroupByPlan) groupBy).getChild();
        assertInstanceOf(PlanNode.SeqScanPlan.class, scan);
        List<String> cols = ((PlanNode.SeqScanPlan) scan).getColumns();
        assertNotNull(cols);
        assertEquals(1, cols.size());
        assertTrue(cols.contains("age"));
    }

    @Test
    void columnPruning_selectStar_allColumns() {
        // SELECT * FROM student → SeqScan 无列限制（columns 为 null）
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("*"), null,
                List.of(), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        PlanNode scan = ((PlanNode.ProjectPlan) plan).getChild();
        assertInstanceOf(PlanNode.SeqScanPlan.class, scan);
        assertNull(((PlanNode.SeqScanPlan) scan).getColumns());
    }

    @Test
    void columnPruning_joinEachTableSeparately() {
        // SELECT student.name, course.cname FROM student JOIN course ON id=cid
        // student 只读 name + id；course 只读 cname + cid（= 全部列）
        catalog.createTableWithTypes("course", Arrays.asList(
                new CatalogImpl.ColumnInfo("cid", CatalogImpl.DataType.INT),
                new CatalogImpl.ColumnInfo("cname", CatalogImpl.DataType.VARCHAR),
                new CatalogImpl.ColumnInfo("credit", CatalogImpl.DataType.INT)
        ));
        ASTNode.BinaryExpr onCond = new ASTNode.BinaryExpr(1, 30, "=",
                new ASTNode.IdentifierExpr(1, 28, "id"),
                new ASTNode.IdentifierExpr(1, 35, "cid"));
        ASTNode.SelectStmt.JoinClause join = new ASTNode.SelectStmt.JoinClause("course", onCond);

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("name", "cname"), null,
                List.of(join), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        PlanNode joinNode = ((PlanNode.ProjectPlan) plan).getChild();
        assertInstanceOf(PlanNode.JoinPlan.class, joinNode);
        PlanNode.JoinPlan jp = (PlanNode.JoinPlan) joinNode;

        // student: 只读 name + id（不读 age）
        PlanNode studentScan = jp.getChildren().get(0);
        assertInstanceOf(PlanNode.SeqScanPlan.class, studentScan);
        List<String> studentCols = ((PlanNode.SeqScanPlan) studentScan).getColumns();
        assertNotNull(studentCols);
        assertTrue(studentCols.contains("name"));
        assertTrue(studentCols.contains("id"));
        assertFalse(studentCols.contains("age"));

        // course: 只读 cname + cid（不读 credit）
        PlanNode courseScan = jp.getChildren().get(1);
        assertInstanceOf(PlanNode.SeqScanPlan.class, courseScan);
        List<String> courseCols = ((PlanNode.SeqScanPlan) courseScan).getColumns();
        assertNotNull(courseCols);
        assertTrue(courseCols.contains("cname"));
        assertTrue(courseCols.contains("cid"));
        assertFalse(courseCols.contains("credit"));
    }

    @Test
    void columnPruning_allColumnsRead_noColumnList() {
        // SELECT id, name, age FROM student → 全部列 → columns 为 null
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("id", "name", "age"), null,
                List.of(), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        PlanNode scan = ((PlanNode.ProjectPlan) plan).getChild();
        assertInstanceOf(PlanNode.SeqScanPlan.class, scan);
        // 全部列 → 不裁剪
        assertNull(((PlanNode.SeqScanPlan) scan).getColumns());
    }

    @Test
    void toJson_scanWithColumns() {
        // SELECT name FROM student → JSON 中 SeqScan 有 columns 字段
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("name"), null,
                List.of(), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);
        String json = generator.toJson(plan);

        assertTrue(json.contains("\"op\":\"scan\""));
        assertTrue(json.contains("\"table\":\"student\""));
        assertTrue(json.contains("\"columns\":[\"name\"]"));
    }

    @Test
    void formatPlan_scanWithColumns() {
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("name"), null,
                List.of(), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        String output = PlanNode.formatPlan(plan);
        assertTrue(output.contains("SeqScanPlan"));
        assertTrue(output.contains("student"));
        assertTrue(output.contains("name"));  // 列名出现在属性中
    }

    // ========== JOIN 算法选择 ==========

    @Test
    void joinAlgorithm_equiJoin_usesHash() {
        // ON id = cid → 等值连接 → HASH
        catalog.createTableWithTypes("course", Arrays.asList(
                new CatalogImpl.ColumnInfo("cid", CatalogImpl.DataType.INT),
                new CatalogImpl.ColumnInfo("cname", CatalogImpl.DataType.VARCHAR)
        ));
        ASTNode.BinaryExpr onCond = new ASTNode.BinaryExpr(1, 30, "=",
                new ASTNode.IdentifierExpr(1, 28, "id"),
                new ASTNode.IdentifierExpr(1, 35, "cid"));
        ASTNode.SelectStmt.JoinClause join = new ASTNode.SelectStmt.JoinClause("course", onCond);

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("name"), null,
                List.of(join), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        PlanNode.JoinPlan jp = (PlanNode.JoinPlan) ((PlanNode.ProjectPlan) plan).getChild();
        List<PlanNode.JoinAlgorithm> algos = jp.getAlgorithms();
        assertNotNull(algos);
        assertEquals(2, algos.size());
        // 左表 NESTED_LOOP，右表 HASH
        assertEquals(PlanNode.JoinAlgorithm.NESTED_LOOP, algos.get(0));
        assertEquals(PlanNode.JoinAlgorithm.HASH, algos.get(1));
    }

    @Test
    void joinAlgorithm_nonEquiJoin_usesNestedLoop() {
        // ON student.age > course.credit → 非等值 → NESTED_LOOP
        catalog.createTableWithTypes("course", Arrays.asList(
                new CatalogImpl.ColumnInfo("cid", CatalogImpl.DataType.INT),
                new CatalogImpl.ColumnInfo("cname", CatalogImpl.DataType.VARCHAR)
        ));
        ASTNode.BinaryExpr onCond = new ASTNode.BinaryExpr(1, 30, ">",
                new ASTNode.IdentifierExpr(1, 28, "age"),
                new ASTNode.IdentifierExpr(1, 35, "cname"));
        ASTNode.SelectStmt.JoinClause join = new ASTNode.SelectStmt.JoinClause("course", onCond);

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("name"), null,
                List.of(join), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        PlanNode.JoinPlan jp = (PlanNode.JoinPlan) ((PlanNode.ProjectPlan) plan).getChild();
        List<PlanNode.JoinAlgorithm> algos = jp.getAlgorithms();
        assertNotNull(algos);
        assertEquals(PlanNode.JoinAlgorithm.NESTED_LOOP, algos.get(0));
        assertEquals(PlanNode.JoinAlgorithm.NESTED_LOOP, algos.get(1));
    }

    @Test
    void joinAlgorithm_multipleEquiJoinConjuncts_usesHash() {
        // ON id = cid AND name = cname → 全等值合取 → HASH
        catalog.createTableWithTypes("course", Arrays.asList(
                new CatalogImpl.ColumnInfo("cid", CatalogImpl.DataType.INT),
                new CatalogImpl.ColumnInfo("cname", CatalogImpl.DataType.VARCHAR)
        ));
        ASTNode.BinaryExpr eq1 = new ASTNode.BinaryExpr(1, 30, "=",
                new ASTNode.IdentifierExpr(1, 28, "id"),
                new ASTNode.IdentifierExpr(1, 35, "cid"));
        ASTNode.BinaryExpr eq2 = new ASTNode.BinaryExpr(1, 50, "=",
                new ASTNode.IdentifierExpr(1, 48, "name"),
                new ASTNode.IdentifierExpr(1, 55, "cname"));
        ASTNode.BinaryExpr onCond = new ASTNode.BinaryExpr(1, 45, "AND", eq1, eq2);
        ASTNode.SelectStmt.JoinClause join = new ASTNode.SelectStmt.JoinClause("course", onCond);

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("name"), null,
                List.of(join), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        PlanNode.JoinPlan jp = (PlanNode.JoinPlan) ((PlanNode.ProjectPlan) plan).getChild();
        assertEquals(PlanNode.JoinAlgorithm.HASH, jp.getAlgorithms().get(1));
    }

    @Test
    void joinAlgorithm_mixedEquiAndNonEqui_usesNestedLoop() {
        // ON id = cid OR age > cname → 含非等值 → NESTED_LOOP
        catalog.createTableWithTypes("course", Arrays.asList(
                new CatalogImpl.ColumnInfo("cid", CatalogImpl.DataType.INT),
                new CatalogImpl.ColumnInfo("cname", CatalogImpl.DataType.VARCHAR)
        ));
        ASTNode.BinaryExpr eq = new ASTNode.BinaryExpr(1, 30, "=",
                new ASTNode.IdentifierExpr(1, 28, "id"),
                new ASTNode.IdentifierExpr(1, 35, "cid"));
        ASTNode.BinaryExpr ne = new ASTNode.BinaryExpr(1, 50, ">",
                new ASTNode.IdentifierExpr(1, 48, "age"),
                new ASTNode.IdentifierExpr(1, 55, "cname"));
        ASTNode.BinaryExpr onCond = new ASTNode.BinaryExpr(1, 45, "OR", eq, ne);
        ASTNode.SelectStmt.JoinClause join = new ASTNode.SelectStmt.JoinClause("course", onCond);

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("name"), null,
                List.of(join), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        PlanNode.JoinPlan jp = (PlanNode.JoinPlan) ((PlanNode.ProjectPlan) plan).getChild();
        assertEquals(PlanNode.JoinAlgorithm.NESTED_LOOP, jp.getAlgorithms().get(1));
    }

    @Test
    void joinAlgorithm_threeTableJoin_mixedAlgorithms() {
        // student JOIN course ON id=cid (HASH) JOIN enrollment ON id=eid (HASH)
        catalog.createTableWithTypes("course", Arrays.asList(
                new CatalogImpl.ColumnInfo("cid", CatalogImpl.DataType.INT),
                new CatalogImpl.ColumnInfo("cname", CatalogImpl.DataType.VARCHAR)
        ));
        catalog.createTableWithTypes("enrollment", Arrays.asList(
                new CatalogImpl.ColumnInfo("eid", CatalogImpl.DataType.INT),
                new CatalogImpl.ColumnInfo("grade", CatalogImpl.DataType.VARCHAR)
        ));
        ASTNode.BinaryExpr on1 = new ASTNode.BinaryExpr(1, 30, "=",
                new ASTNode.IdentifierExpr(1, 28, "id"),
                new ASTNode.IdentifierExpr(1, 35, "cid"));
        ASTNode.BinaryExpr on2 = new ASTNode.BinaryExpr(1, 60, "=",
                new ASTNode.IdentifierExpr(1, 58, "id"),
                new ASTNode.IdentifierExpr(1, 65, "eid"));
        ASTNode.SelectStmt.JoinClause join1 = new ASTNode.SelectStmt.JoinClause("course", on1);
        ASTNode.SelectStmt.JoinClause join2 = new ASTNode.SelectStmt.JoinClause("enrollment", on2);

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("name"), null,
                List.of(join1, join2), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        PlanNode.JoinPlan jp = (PlanNode.JoinPlan) ((PlanNode.ProjectPlan) plan).getChild();
        List<PlanNode.JoinAlgorithm> algos = jp.getAlgorithms();
        assertNotNull(algos);
        assertEquals(3, algos.size());
        assertEquals(PlanNode.JoinAlgorithm.NESTED_LOOP, algos.get(0));  // 左表
        assertEquals(PlanNode.JoinAlgorithm.HASH, algos.get(1));          // course
        assertEquals(PlanNode.JoinAlgorithm.HASH, algos.get(2));          // enrollment
    }

    @Test
    void joinAlgorithm_threeTableJoin_differentAlgorithms() {
        // student JOIN course ON id=cid (HASH) JOIN enrollment ON age > grade (NESTED_LOOP)
        catalog.createTableWithTypes("course", Arrays.asList(
                new CatalogImpl.ColumnInfo("cid", CatalogImpl.DataType.INT),
                new CatalogImpl.ColumnInfo("cname", CatalogImpl.DataType.VARCHAR)
        ));
        catalog.createTableWithTypes("enrollment", Arrays.asList(
                new CatalogImpl.ColumnInfo("eid", CatalogImpl.DataType.INT),
                new CatalogImpl.ColumnInfo("grade", CatalogImpl.DataType.VARCHAR)
        ));
        ASTNode.BinaryExpr on1 = new ASTNode.BinaryExpr(1, 30, "=",
                new ASTNode.IdentifierExpr(1, 28, "id"),
                new ASTNode.IdentifierExpr(1, 35, "cid"));
        ASTNode.BinaryExpr on2 = new ASTNode.BinaryExpr(1, 60, ">",
                new ASTNode.IdentifierExpr(1, 58, "age"),
                new ASTNode.IdentifierExpr(1, 65, "grade"));
        ASTNode.SelectStmt.JoinClause join1 = new ASTNode.SelectStmt.JoinClause("course", on1);
        ASTNode.SelectStmt.JoinClause join2 = new ASTNode.SelectStmt.JoinClause("enrollment", on2);

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("name"), null,
                List.of(join1, join2), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        PlanNode.JoinPlan jp = (PlanNode.JoinPlan) ((PlanNode.ProjectPlan) plan).getChild();
        List<PlanNode.JoinAlgorithm> algos = jp.getAlgorithms();
        assertEquals(PlanNode.JoinAlgorithm.NESTED_LOOP, algos.get(0));
        assertEquals(PlanNode.JoinAlgorithm.HASH, algos.get(1));
        assertEquals(PlanNode.JoinAlgorithm.NESTED_LOOP, algos.get(2));
    }

    @Test
    void toJson_joinIncludesAlgorithms() {
        catalog.createTableWithTypes("course", Arrays.asList(
                new CatalogImpl.ColumnInfo("cid", CatalogImpl.DataType.INT),
                new CatalogImpl.ColumnInfo("cname", CatalogImpl.DataType.VARCHAR)
        ));
        ASTNode.BinaryExpr onCond = new ASTNode.BinaryExpr(1, 30, "=",
                new ASTNode.IdentifierExpr(1, 28, "id"),
                new ASTNode.IdentifierExpr(1, 35, "cid"));
        ASTNode.SelectStmt.JoinClause join = new ASTNode.SelectStmt.JoinClause("course", onCond);

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("name"), null,
                List.of(join), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);
        String json = generator.toJson(plan);

        assertTrue(json.contains("\"op\":\"join\""));
        assertTrue(json.contains("\"algorithms\""));
        assertTrue(json.contains("\"NESTED_LOOP\""));
        assertTrue(json.contains("\"HASH\""));
    }

    @Test
    void formatPlan_joinShowsAlgorithms() {
        catalog.createTableWithTypes("course", Arrays.asList(
                new CatalogImpl.ColumnInfo("cid", CatalogImpl.DataType.INT),
                new CatalogImpl.ColumnInfo("cname", CatalogImpl.DataType.VARCHAR)
        ));
        ASTNode.BinaryExpr onCond = new ASTNode.BinaryExpr(1, 30, "=",
                new ASTNode.IdentifierExpr(1, 28, "id"),
                new ASTNode.IdentifierExpr(1, 35, "cid"));
        ASTNode.SelectStmt.JoinClause join = new ASTNode.SelectStmt.JoinClause("course", onCond);

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("name"), null,
                List.of(join), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        String output = PlanNode.formatPlan(plan);
        assertTrue(output.contains("JoinPlan"));
        assertTrue(output.contains("algorithms"));
        assertTrue(output.contains("HASH"));
    }

    // ========== RBO JOIN 顺序优化 ==========

    @Test
    void rboJoinOrder_noPredicate_keepsOriginalOrder() {
        // SELECT name FROM student JOIN course ON id=cid (无 WHERE)
        // RBO: 无谓词 → 主表 student 在第一位
        catalog.createTableWithTypes("course", Arrays.asList(
                new CatalogImpl.ColumnInfo("cid", CatalogImpl.DataType.INT),
                new CatalogImpl.ColumnInfo("cname", CatalogImpl.DataType.VARCHAR)
        ));
        ASTNode.BinaryExpr onCond = new ASTNode.BinaryExpr(1, 30, "=",
                new ASTNode.IdentifierExpr(1, 28, "id"),
                new ASTNode.IdentifierExpr(1, 35, "cid"));
        ASTNode.SelectStmt.JoinClause join = new ASTNode.SelectStmt.JoinClause("course", onCond);

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("name"), null,
                List.of(join), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        PlanNode.JoinPlan jp = (PlanNode.JoinPlan) ((PlanNode.ProjectPlan) plan).getChild();
        // 无谓词 → 保持原始顺序
        assertEquals("student", getScanTableName(jp.getChildren().get(0)));
        assertEquals("course", getScanTableName(jp.getChildren().get(1)));
        // 左表 ON 为 null，第二表 ON 为 id=cid
        assertNull(jp.getOnConditions().get(0));
        assertNotNull(jp.getOnConditions().get(1));
    }

    @Test
    void rboJoinOrder_predicateOnJoinTable_becomesFirst() {
        // SELECT name FROM student JOIN course ON id=cid WHERE cid > 100
        // cid 属于 course → course 有单表谓词 → RBO 将 course 放在第一位
        catalog.createTableWithTypes("course", Arrays.asList(
                new CatalogImpl.ColumnInfo("cid", CatalogImpl.DataType.INT),
                new CatalogImpl.ColumnInfo("cname", CatalogImpl.DataType.VARCHAR)
        ));
        ASTNode.BinaryExpr onCond = new ASTNode.BinaryExpr(1, 30, "=",
                new ASTNode.IdentifierExpr(1, 28, "id"),
                new ASTNode.IdentifierExpr(1, 35, "cid"));
        ASTNode.SelectStmt.JoinClause join = new ASTNode.SelectStmt.JoinClause("course", onCond);

        // WHERE cid > 100 (单表谓词，在 course 上)
        ASTNode.BinaryExpr whereCond = new ASTNode.BinaryExpr(1, 50, ">",
                new ASTNode.IdentifierExpr(1, 48, "cid"),
                lit(1, 55, "100", Kind.NUMBER));

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("name"), whereCond,
                List.of(join), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        PlanNode.JoinPlan jp = (PlanNode.JoinPlan) ((PlanNode.ProjectPlan) plan).getChild();
        // RBO: course（有谓词）在第一位，student 在第二位
        assertEquals("course", getScanTableName(jp.getChildren().get(0)));
        assertEquals("student", getScanTableName(jp.getChildren().get(1)));
        // course 有 Filter（cid > 100 下推）
        assertInstanceOf(PlanNode.FilterPlan.class, jp.getChildren().get(0));
        // student 无 Filter
        assertInstanceOf(PlanNode.SeqScanPlan.class, jp.getChildren().get(1));
        // ON 条件重映射：左表 null，第二表为 id=cid
        assertNull(jp.getOnConditions().get(0));
        assertNotNull(jp.getOnConditions().get(1));
        // 算法：左表 NESTED_LOOP，第二表 HASH（等值连接）
        assertEquals(PlanNode.JoinAlgorithm.NESTED_LOOP, jp.getAlgorithms().get(0));
        assertEquals(PlanNode.JoinAlgorithm.HASH, jp.getAlgorithms().get(1));
    }

    @Test
    void rboJoinOrder_threeTable_predicateOnLastCausesReorder() {
        // SELECT name FROM student JOIN course ON id=cid JOIN enrollment ON id=eid WHERE eid > 100
        // eid 属于 enrollment → enrollment 有单表谓词 → RBO: enrollment, student, course
        catalog.createTableWithTypes("course", Arrays.asList(
                new CatalogImpl.ColumnInfo("cid", CatalogImpl.DataType.INT),
                new CatalogImpl.ColumnInfo("cname", CatalogImpl.DataType.VARCHAR)
        ));
        catalog.createTableWithTypes("enrollment", Arrays.asList(
                new CatalogImpl.ColumnInfo("eid", CatalogImpl.DataType.INT),
                new CatalogImpl.ColumnInfo("grade", CatalogImpl.DataType.VARCHAR)
        ));
        ASTNode.BinaryExpr on1 = new ASTNode.BinaryExpr(1, 30, "=",
                new ASTNode.IdentifierExpr(1, 28, "id"),
                new ASTNode.IdentifierExpr(1, 35, "cid"));
        ASTNode.BinaryExpr on2 = new ASTNode.BinaryExpr(1, 60, "=",
                new ASTNode.IdentifierExpr(1, 58, "id"),
                new ASTNode.IdentifierExpr(1, 65, "eid"));
        ASTNode.SelectStmt.JoinClause join1 = new ASTNode.SelectStmt.JoinClause("course", on1);
        ASTNode.SelectStmt.JoinClause join2 = new ASTNode.SelectStmt.JoinClause("enrollment", on2);

        // WHERE eid > 100 (在 enrollment 上)
        ASTNode.BinaryExpr whereCond = new ASTNode.BinaryExpr(1, 80, ">",
                new ASTNode.IdentifierExpr(1, 78, "eid"),
                lit(1, 85, "100", Kind.NUMBER));

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("name"), whereCond,
                List.of(join1, join2), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        PlanNode.JoinPlan jp = (PlanNode.JoinPlan) ((PlanNode.ProjectPlan) plan).getChild();
        assertEquals(3, jp.getChildren().size());

        // RBO 顺序: enrollment (有谓词) → student (连接 via id=eid) → course (连接 via id=cid)
        assertEquals("enrollment", getScanTableName(jp.getChildren().get(0)));
        assertEquals("student", getScanTableName(jp.getChildren().get(1)));
        assertEquals("course", getScanTableName(jp.getChildren().get(2)));

        // enrollment 有 Filter（eid > 100 下推）
        assertInstanceOf(PlanNode.FilterPlan.class, jp.getChildren().get(0));

        // ON 条件: [null, id=eid, id=cid]
        assertNull(jp.getOnConditions().get(0));
        assertNotNull(jp.getOnConditions().get(1));
        assertNotNull(jp.getOnConditions().get(2));

        // 算法: [NESTED_LOOP, HASH, HASH]
        assertEquals(PlanNode.JoinAlgorithm.NESTED_LOOP, jp.getAlgorithms().get(0));
        assertEquals(PlanNode.JoinAlgorithm.HASH, jp.getAlgorithms().get(1));
        assertEquals(PlanNode.JoinAlgorithm.HASH, jp.getAlgorithms().get(2));
    }

    @Test
    void rboJoinOrder_crossTableWhere_doesNotReorder() {
        // SELECT name FROM student JOIN course ON id=cid WHERE student.id = course.cid
        // 跨表谓词不参与 RBO 排序 → 保持原始顺序
        catalog.createTableWithTypes("course", Arrays.asList(
                new CatalogImpl.ColumnInfo("cid", CatalogImpl.DataType.INT),
                new CatalogImpl.ColumnInfo("cname", CatalogImpl.DataType.VARCHAR)
        ));
        ASTNode.BinaryExpr onCond = new ASTNode.BinaryExpr(1, 30, "=",
                new ASTNode.IdentifierExpr(1, 28, "id"),
                new ASTNode.IdentifierExpr(1, 35, "cid"));
        ASTNode.SelectStmt.JoinClause join = new ASTNode.SelectStmt.JoinClause("course", onCond);

        // WHERE student.id = course.cid (跨表)
        ASTNode.BinaryExpr whereCond = new ASTNode.BinaryExpr(1, 50, "=",
                new ASTNode.IdentifierExpr(1, 48, "student.id"),
                new ASTNode.IdentifierExpr(1, 55, "course.cid"));

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("student.name"), whereCond,
                List.of(join), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        // 跨表谓词留在 Join 之上
        assertInstanceOf(PlanNode.FilterPlan.class, ((PlanNode.ProjectPlan) plan).getChild());
        PlanNode.FilterPlan filter = (PlanNode.FilterPlan) ((PlanNode.ProjectPlan) plan).getChild();
        PlanNode.JoinPlan jp = (PlanNode.JoinPlan) filter.getChild();

        // 无单表谓词 → 保持原始顺序
        assertEquals("student", getScanTableName(jp.getChildren().get(0)));
        assertEquals("course", getScanTableName(jp.getChildren().get(1)));
    }

    @Test
    void rboJoinOrder_equalPredicates_keepsOriginalOrder() {
        // SELECT name FROM student JOIN course ON id=cid WHERE age > 18 AND cid > 100
        // 两表都有 1 个谓词，同谓词数 → 保持原始顺序（主表优先）
        catalog.createTableWithTypes("course", Arrays.asList(
                new CatalogImpl.ColumnInfo("cid", CatalogImpl.DataType.INT),
                new CatalogImpl.ColumnInfo("cname", CatalogImpl.DataType.VARCHAR)
        ));
        ASTNode.BinaryExpr onCond = new ASTNode.BinaryExpr(1, 30, "=",
                new ASTNode.IdentifierExpr(1, 28, "id"),
                new ASTNode.IdentifierExpr(1, 35, "cid"));
        ASTNode.SelectStmt.JoinClause join = new ASTNode.SelectStmt.JoinClause("course", onCond);

        // WHERE age > 18 AND cid > 100
        ASTNode.BinaryExpr pred1 = new ASTNode.BinaryExpr(1, 50, ">",
                new ASTNode.IdentifierExpr(1, 48, "age"),
                lit(1, 55, "18", Kind.NUMBER));
        ASTNode.BinaryExpr pred2 = new ASTNode.BinaryExpr(1, 65, ">",
                new ASTNode.IdentifierExpr(1, 63, "cid"),
                lit(1, 70, "100", Kind.NUMBER));
        ASTNode.BinaryExpr whereCond = new ASTNode.BinaryExpr(1, 60, "AND", pred1, pred2);

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("name"), whereCond,
                List.of(join), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        PlanNode.JoinPlan jp = (PlanNode.JoinPlan) ((PlanNode.ProjectPlan) plan).getChild();
        // 同谓词数（各1个）→ 保持原始顺序
        assertEquals("student", getScanTableName(jp.getChildren().get(0)));
        assertEquals("course", getScanTableName(jp.getChildren().get(1)));
        // 两表都有 Filter
        assertInstanceOf(PlanNode.FilterPlan.class, jp.getChildren().get(0));
        assertInstanceOf(PlanNode.FilterPlan.class, jp.getChildren().get(1));
    }

    @Test
    void rboJoinOrder_morePredicates_winsOverMainTable() {
        // SELECT name FROM student JOIN course ON id=cid
        // WHERE age > 18 AND cid > 100 AND cid < 200
        // student: 1 predicate (age>18), course: 2 predicates (cid>100, cid<200)
        // RBO: course (2 predicates) first
        catalog.createTableWithTypes("course", Arrays.asList(
                new CatalogImpl.ColumnInfo("cid", CatalogImpl.DataType.INT),
                new CatalogImpl.ColumnInfo("cname", CatalogImpl.DataType.VARCHAR)
        ));
        ASTNode.BinaryExpr onCond = new ASTNode.BinaryExpr(1, 30, "=",
                new ASTNode.IdentifierExpr(1, 28, "id"),
                new ASTNode.IdentifierExpr(1, 35, "cid"));
        ASTNode.SelectStmt.JoinClause join = new ASTNode.SelectStmt.JoinClause("course", onCond);

        // WHERE age > 18 AND cid > 100 AND cid < 200
        ASTNode.BinaryExpr pred1 = new ASTNode.BinaryExpr(1, 50, ">",
                new ASTNode.IdentifierExpr(1, 48, "age"),
                lit(1, 55, "18", Kind.NUMBER));
        ASTNode.BinaryExpr pred2 = new ASTNode.BinaryExpr(1, 65, ">",
                new ASTNode.IdentifierExpr(1, 63, "cid"),
                lit(1, 70, "100", Kind.NUMBER));
        ASTNode.BinaryExpr pred3 = new ASTNode.BinaryExpr(1, 80, "<",
                new ASTNode.IdentifierExpr(1, 78, "cid"),
                lit(1, 85, "200", Kind.NUMBER));
        ASTNode.BinaryExpr whereCond = new ASTNode.BinaryExpr(1, 60, "AND", pred1,
                new ASTNode.BinaryExpr(1, 75, "AND", pred2, pred3));

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("name"), whereCond,
                List.of(join), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        PlanNode.JoinPlan jp = (PlanNode.JoinPlan) ((PlanNode.ProjectPlan) plan).getChild();
        // course 有更多谓词 → 排在第一位
        assertEquals("course", getScanTableName(jp.getChildren().get(0)));
        assertEquals("student", getScanTableName(jp.getChildren().get(1)));
    }

    @Test
    void rboJoinOrder_threeTable_chainReorderedByPredicate() {
        // SELECT name FROM A JOIN B ON a.id=b.aid JOIN C ON b.id=c.bid WHERE c.x > 5
        // C 有谓词 → RBO: C, B, A
        catalog.createTableWithTypes("B", Arrays.asList(
                new CatalogImpl.ColumnInfo("bid", CatalogImpl.DataType.INT),
                new CatalogImpl.ColumnInfo("aid", CatalogImpl.DataType.INT)
        ));
        catalog.createTableWithTypes("C", Arrays.asList(
                new CatalogImpl.ColumnInfo("bid", CatalogImpl.DataType.INT),
                new CatalogImpl.ColumnInfo("x", CatalogImpl.DataType.INT)
        ));
        ASTNode.BinaryExpr on1 = new ASTNode.BinaryExpr(1, 30, "=",
                new ASTNode.IdentifierExpr(1, 28, "id"),
                new ASTNode.IdentifierExpr(1, 35, "aid"));
        ASTNode.BinaryExpr on2 = new ASTNode.BinaryExpr(1, 60, "=",
                new ASTNode.IdentifierExpr(1, 58, "bid"),
                new ASTNode.IdentifierExpr(1, 65, "bid"));
        ASTNode.SelectStmt.JoinClause join1 = new ASTNode.SelectStmt.JoinClause("B", on1);
        ASTNode.SelectStmt.JoinClause join2 = new ASTNode.SelectStmt.JoinClause("C", on2);

        // WHERE C.x > 5
        ASTNode.BinaryExpr whereCond = new ASTNode.BinaryExpr(1, 80, ">",
                new ASTNode.IdentifierExpr(1, 78, "x"),
                lit(1, 85, "5", Kind.NUMBER));

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("name"), whereCond,
                List.of(join1, join2), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);

        PlanNode.JoinPlan jp = (PlanNode.JoinPlan) ((PlanNode.ProjectPlan) plan).getChild();
        assertEquals(3, jp.getChildren().size());
        // RBO: C (有谓词), B (连接 via bid=bid), student (连接 via id=aid)
        assertEquals("C", getScanTableName(jp.getChildren().get(0)));
        assertEquals("B", getScanTableName(jp.getChildren().get(1)));
        assertEquals("student", getScanTableName(jp.getChildren().get(2)));
        // C 有 Filter
        assertInstanceOf(PlanNode.FilterPlan.class, jp.getChildren().get(0));
    }

    @Test
    void rboJoinOrder_jsonSerialization_correctOnConditions() {
        // 验证 RBO 重排后 JSON 序列化中 ON 条件正确
        catalog.createTableWithTypes("course", Arrays.asList(
                new CatalogImpl.ColumnInfo("cid", CatalogImpl.DataType.INT),
                new CatalogImpl.ColumnInfo("cname", CatalogImpl.DataType.VARCHAR)
        ));
        ASTNode.BinaryExpr onCond = new ASTNode.BinaryExpr(1, 30, "=",
                new ASTNode.IdentifierExpr(1, 28, "id"),
                new ASTNode.IdentifierExpr(1, 35, "cid"));
        ASTNode.SelectStmt.JoinClause join = new ASTNode.SelectStmt.JoinClause("course", onCond);

        // WHERE cid > 100 → course 先
        ASTNode.BinaryExpr whereCond = new ASTNode.BinaryExpr(1, 50, ">",
                new ASTNode.IdentifierExpr(1, 48, "cid"),
                lit(1, 55, "100", Kind.NUMBER));

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("name"), whereCond,
                List.of(join), List.of(), List.of());
        PlanNode plan = generator.generate(stmt);
        String json = generator.toJson(plan);

        assertTrue(json.contains("\"op\":\"join\""));
        assertTrue(json.contains("\"children\""));
        assertTrue(json.contains("\"table\":\"course\""));
        assertTrue(json.contains("\"table\":\"student\""));
        assertTrue(json.contains("\"algorithms\""));
    }
}
