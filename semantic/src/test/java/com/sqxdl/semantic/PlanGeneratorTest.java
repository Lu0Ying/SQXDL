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
                1, 1, "student", Arrays.asList("id", "name"), null);
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
                1, 1, "student", Arrays.asList("id"), cond);
        PlanNode plan = generator.generate(stmt);

        assertTrue(plan instanceof PlanNode.ProjectPlan);
        assertTrue(getChild(plan) instanceof PlanNode.FilterPlan);
        assertTrue(getChild(getChild(plan)) instanceof PlanNode.SeqScanPlan);
    }

    @Test
    void selectStar_expandsToAllColumns() {
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("*"), null);
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
                1, 1, "student", Arrays.asList("id"), cond);
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
                1, 1, "student", Arrays.asList("id"), cond);
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
                1, 1, "student", Arrays.asList("id"), cond);
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
                1, 1, "student", Arrays.asList("id"), cond);
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
                1, 1, "student", Arrays.asList("id"), cond);
        PlanNode plan = generator.generate(stmt);

        assertTrue(getChild(plan) instanceof PlanNode.SeqScanPlan);
    }

    @Test
    void constantFolding_stringNotEqual_false() {
        // WHERE 'abc' != 'abc' → FALSE → Filter(FALSE)
        ASTNode.BinaryExpr cond = new ASTNode.BinaryExpr(
                1, 10, "!=", lit(1, 9, "abc", Kind.STRING), lit(1, 15, "abc", Kind.STRING));

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("id"), cond);
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
                1, 1, "student", Arrays.asList("id"), cond);
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
                1, 1, "student", Arrays.asList("id"), cond);
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
                1, 1, "student", Arrays.asList("id"), cond);
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
                1, 1, "student", Arrays.asList("id"), cond);
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
                1, 1, "student", Arrays.asList("id"), cond);
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
                1, 1, "student", Arrays.asList("id"), cond);
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
                1, 1, "student", Arrays.asList("id"), cond);
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
                1, 1, "student", Arrays.asList("id"), cond);
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
                1, 1, "student", Arrays.asList("id"), cond);
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
                1, 1, "student", Arrays.asList("id"), cond);
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
                1, 1, "student", Arrays.asList("id"), cond);
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
                1, 1, "student", Arrays.asList("*"), null);

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
                1, 1, "student", Arrays.asList("*"), null);
        PlanNode plan = generator.generate(stmt);

        ProjectPlanCheck(plan, Arrays.asList("id", "name", "age"));
    }

    @Test
    void selectStar_withAnalyzer_skipsRedundantExpansion() {
        // 即使没有调用 analyzer.analyze()，setAnalyzer 后应该退化为自行展开
        SemanticAnalyzer analyzer = new SemanticAnalyzer(catalog);
        generator.setAnalyzer(analyzer);

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("id"), null);
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
                1, 1, "student", Arrays.asList("id", "name"), cond);

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
                1, 1, "student", Arrays.asList("id", "name"), cond);
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
                1, 1, "student", Arrays.asList("id"), null);
        PlanNode plan = generator.generate(stmt);

        String output = PlanNode.formatPlan(plan);

        assertTrue(output.contains("ProjectPlan"));
        assertTrue(output.contains("SeqScanPlan"));
        assertFalse(output.contains("FilterPlan"));
    }

    @Test
    void formatPlan_selectStar() {
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("*"), null);
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
                1, 1, "student", Arrays.asList("id", "name"), cond);
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
                1, 1, "student", Arrays.asList("id", "name"), null);
        PlanNode plan = generator.generate(stmt);

        String json = generator.toJson(plan);

        assertTrue(json.contains("\"op\":\"project\""));
        assertFalse(json.contains("\"op\":\"filter\""));
        assertTrue(json.contains("\"op\":\"scan\""));
    }

    @Test
    void toJson_selectStar() {
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("*"), null);
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
                1, 1, "student", Arrays.asList("id"), cond);
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
                1, 1, "student", Arrays.asList("id"), cond);
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
                1, 1, "student", Arrays.asList("id"), cond);
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
                1, 1, "student", Arrays.asList("id"), null);
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
                1, 1, "student", Arrays.asList("id", "name"), cond);
        PlanNode plan = generator.generate(stmt);

        String json = generator.toJson(plan);

        // 和 readme 示例的关键字段对比
        assertTrue(json.startsWith("{\"op\":\"project\",\"columns\":[\"id\",\"name\"],\"child\":"));
        assertTrue(json.contains("\"op\":\"filter\",\"condition\":{\"type\":\"binary\",\"op\":\">\""));
        assertTrue(json.contains("\"left\":{\"type\":\"column\",\"name\":\"id\"}"));
        assertTrue(json.contains("\"right\":{\"type\":\"literal\",\"value\":1}"));
        assertTrue(json.contains("\"child\":{\"op\":\"scan\",\"table\":\"student\"}"));
    }

    // ========== SHOW TABLES ==========

    @Test
    void generate_showTables() {
        ASTNode.ShowTablesStmt stmt = new ASTNode.ShowTablesStmt(1, 1);
        PlanNode plan = generator.generate(stmt);
        assertInstanceOf(PlanNode.ShowTablesPlan.class, plan);
    }

    @Test
    void toJson_showTables() {
        ASTNode.ShowTablesStmt stmt = new ASTNode.ShowTablesStmt(1, 1);
        PlanNode plan = generator.generate(stmt);
        String json = generator.toJson(plan);
        assertTrue(json.contains("\"op\":\"showTables\""));
    }

    @Test
    void formatPlan_showTables() {
        ASTNode.ShowTablesStmt stmt = new ASTNode.ShowTablesStmt(1, 1);
        PlanNode plan = generator.generate(stmt);
        String output = PlanNode.formatPlan(plan);
        assertTrue(output.contains("ShowTablesPlan"));
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

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(1, 1, "student", Arrays.asList("id"), cond);
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

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(1, 1, "student", Arrays.asList("id"), cond);
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

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(1, 1, "student", Arrays.asList("id"), cond);
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

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(1, 1, "student", Arrays.asList("id"), cond);
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

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(1, 1, "student", Arrays.asList("id"), cond);
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
}
