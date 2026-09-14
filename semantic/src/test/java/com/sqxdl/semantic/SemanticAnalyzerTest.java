package com.sqxdl.semantic;

import com.sqxdl.parser.ASTNode;
import com.sqxdl.parser.SqxdlException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SemanticAnalyzerTest {

    private CatalogImpl catalog;
    private SemanticAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        catalog = new CatalogImpl();
        List<CatalogImpl.ColumnInfo> studentCols = Arrays.asList(
                new CatalogImpl.ColumnInfo("id", CatalogImpl.DataType.INT),
                new CatalogImpl.ColumnInfo("name", CatalogImpl.DataType.VARCHAR),
                new CatalogImpl.ColumnInfo("age", CatalogImpl.DataType.INT),
                new CatalogImpl.ColumnInfo("score", CatalogImpl.DataType.DOUBLE)
        );
        catalog.createTableWithTypes("student", studentCols);
        analyzer = new SemanticAnalyzer(catalog);
    }

    // ========== SELECT：表存在性 ==========

    @Test
    void select_tableExists_success() {
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student",
                Arrays.asList("id", "name"),
                null
        , List.of(), List.of(), List.of());
        analyzer.analyze(stmt);
    }

    @Test
    void select_tableNotExists_throwsException() {
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "nonexistent",
                Arrays.asList("id"),
                null
        , List.of(), List.of(), List.of());
        SqxdlException ex = assertThrows(SqxdlException.class, () -> analyzer.analyze(stmt));
        assertTrue(ex.getMessage().contains("不存在"));
        assertEquals(1, ex.getLine());
        assertEquals(1, ex.getCol());
    }

    // ========== SELECT：列存在性 ==========

    @Test
    void select_columnExists_success() {
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student",
                Arrays.asList("id", "name", "age"),
                null
        , List.of(), List.of(), List.of());
        analyzer.analyze(stmt);
    }

    @Test
    void select_columnNotExists_throwsException() {
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student",
                Arrays.asList("id", "nonexistent"),
                null
        , List.of(), List.of(), List.of());
        SqxdlException ex = assertThrows(SqxdlException.class, () -> analyzer.analyze(stmt));
        assertTrue(ex.getMessage().contains("不存在"));
    }

    // ========== SELECT：* 展开 ==========

    @Test
    void selectStar_expandsToAllColumns() {
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student",
                Arrays.asList("*"),
                null
        , List.of(), List.of(), List.of());
        analyzer.analyze(stmt);

        List<String> expanded = analyzer.getExpandedColumns(stmt);
        assertEquals(Arrays.asList("id", "name", "age", "score"), expanded);
    }

    @Test
    void selectNonStar_returnsSameColumns() {
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student",
                Arrays.asList("id", "name"),
                null
        , List.of(), List.of(), List.of());
        analyzer.analyze(stmt);

        List<String> expanded = analyzer.getExpandedColumns(stmt);
        assertEquals(Arrays.asList("id", "name"), expanded);
    }

    // ========== SELECT：WHERE 条件列检查 ==========

    @Test
    void select_whereColumnExists_success() {
        ASTNode.IdentifierExpr col = new ASTNode.IdentifierExpr(1, 10, "age");
        ASTNode.LiteralExpr lit = new ASTNode.LiteralExpr(1, 15, "18", ASTNode.LiteralExpr.Kind.NUMBER);
        ASTNode.BinaryExpr cond = new ASTNode.BinaryExpr(1, 12, ">", col, lit);

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student",
                Arrays.asList("id"),
                cond
        , List.of(), List.of(), List.of());
        analyzer.analyze(stmt);
    }

    @Test
    void select_whereColumnNotExists_throwsException() {
        ASTNode.IdentifierExpr col = new ASTNode.IdentifierExpr(1, 10, "nonexistent");
        ASTNode.LiteralExpr lit = new ASTNode.LiteralExpr(1, 15, "18", ASTNode.LiteralExpr.Kind.NUMBER);
        ASTNode.BinaryExpr cond = new ASTNode.BinaryExpr(1, 12, ">", col, lit);

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student",
                Arrays.asList("id"),
                cond
        , List.of(), List.of(), List.of());
        SqxdlException ex = assertThrows(SqxdlException.class, () -> analyzer.analyze(stmt));
        assertTrue(ex.getMessage().contains("不存在"));
        assertEquals(1, ex.getLine());
        assertEquals(10, ex.getCol());
    }

    // ========== SELECT：类型检查 ==========

    @Test
    void select_typeMismatch_intVsString_throwsException() {
        ASTNode.IdentifierExpr col = new ASTNode.IdentifierExpr(1, 10, "age");
        ASTNode.LiteralExpr lit = new ASTNode.LiteralExpr(1, 15, "abc", ASTNode.LiteralExpr.Kind.STRING);
        ASTNode.BinaryExpr cond = new ASTNode.BinaryExpr(1, 12, ">", col, lit);

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student",
                Arrays.asList("id"),
                cond
        , List.of(), List.of(), List.of());
        SqxdlException ex = assertThrows(SqxdlException.class, () -> analyzer.analyze(stmt));
        assertTrue(ex.getMessage().contains("类型不兼容"));
    }

    @Test
    void select_typeMatch_intVsInt_success() {
        ASTNode.IdentifierExpr col = new ASTNode.IdentifierExpr(1, 10, "age");
        ASTNode.LiteralExpr lit = new ASTNode.LiteralExpr(1, 15, "18", ASTNode.LiteralExpr.Kind.NUMBER);
        ASTNode.BinaryExpr cond = new ASTNode.BinaryExpr(1, 12, ">", col, lit);

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student",
                Arrays.asList("id"),
                cond
        , List.of(), List.of(), List.of());
        analyzer.analyze(stmt);
    }

    @Test
    void select_typeMatch_stringVsString_success() {
        ASTNode.IdentifierExpr col = new ASTNode.IdentifierExpr(1, 10, "name");
        ASTNode.LiteralExpr lit = new ASTNode.LiteralExpr(1, 15, "Alice", ASTNode.LiteralExpr.Kind.STRING);
        ASTNode.BinaryExpr cond = new ASTNode.BinaryExpr(1, 12, "=", col, lit);

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student",
                Arrays.asList("id"),
                cond
        , List.of(), List.of(), List.of());
        analyzer.analyze(stmt);
    }

    // ========== INSERT ==========

    @Test
    void insert_success() {
        List<ASTNode.LiteralExpr> values = Arrays.asList(
                new ASTNode.LiteralExpr(1, 20, "1", ASTNode.LiteralExpr.Kind.NUMBER),
                new ASTNode.LiteralExpr(1, 25, "Alice", ASTNode.LiteralExpr.Kind.STRING),
                new ASTNode.LiteralExpr(1, 30, "18", ASTNode.LiteralExpr.Kind.NUMBER),
                new ASTNode.LiteralExpr(1, 35, "95.5", ASTNode.LiteralExpr.Kind.NUMBER)
        );
        ASTNode.InsertStmt stmt = new ASTNode.InsertStmt(
                1, 1, "student",
                Arrays.asList(),
                values
        );
        analyzer.analyze(stmt);
    }

    @Test
    void insert_valueCountMismatch_throwsException() {
        List<ASTNode.LiteralExpr> values = Arrays.asList(
                new ASTNode.LiteralExpr(1, 20, "1", ASTNode.LiteralExpr.Kind.NUMBER)
        );
        ASTNode.InsertStmt stmt = new ASTNode.InsertStmt(
                1, 1, "student",
                Arrays.asList(),
                values
        );
        SqxdlException ex = assertThrows(SqxdlException.class, () -> analyzer.analyze(stmt));
        assertTrue(ex.getMessage().contains("不一致"));
    }

    @Test
    void insert_typeMismatch_throwsException() {
        List<ASTNode.LiteralExpr> values = Arrays.asList(
                new ASTNode.LiteralExpr(1, 20, "not_a_number", ASTNode.LiteralExpr.Kind.STRING),
                new ASTNode.LiteralExpr(1, 25, "Alice", ASTNode.LiteralExpr.Kind.STRING),
                new ASTNode.LiteralExpr(1, 30, "18", ASTNode.LiteralExpr.Kind.NUMBER),
                new ASTNode.LiteralExpr(1, 35, "95.5", ASTNode.LiteralExpr.Kind.NUMBER)
        );
        ASTNode.InsertStmt stmt = new ASTNode.InsertStmt(
                1, 1, "student",
                Arrays.asList(),
                values
        );
        SqxdlException ex = assertThrows(SqxdlException.class, () -> analyzer.analyze(stmt));
        assertTrue(ex.getMessage().contains("类型不兼容"));
    }

    @Test
    void insert_withColumnList_success() {
        List<ASTNode.LiteralExpr> values = Arrays.asList(
                new ASTNode.LiteralExpr(1, 20, "1", ASTNode.LiteralExpr.Kind.NUMBER),
                new ASTNode.LiteralExpr(1, 25, "Alice", ASTNode.LiteralExpr.Kind.STRING)
        );
        ASTNode.InsertStmt stmt = new ASTNode.InsertStmt(
                1, 1, "student",
                Arrays.asList("id", "name"),
                values
        );
        analyzer.analyze(stmt);
    }

    @Test
    void insert_columnNotExists_throwsException() {
        List<ASTNode.LiteralExpr> values = Arrays.asList(
                new ASTNode.LiteralExpr(1, 20, "1", ASTNode.LiteralExpr.Kind.NUMBER)
        );
        ASTNode.InsertStmt stmt = new ASTNode.InsertStmt(
                1, 1, "student",
                Arrays.asList("nonexistent"),
                values
        );
        SqxdlException ex = assertThrows(SqxdlException.class, () -> analyzer.analyze(stmt));
        assertTrue(ex.getMessage().contains("不存在"));
    }

    // ========== UPDATE ==========

    @Test
    void update_success() {
        Map<String, ASTNode.LiteralExpr> assignments = new LinkedHashMap<>();
        assignments.put("name", new ASTNode.LiteralExpr(1, 20, "Bob", ASTNode.LiteralExpr.Kind.STRING));
        assignments.put("age", new ASTNode.LiteralExpr(1, 25, "20", ASTNode.LiteralExpr.Kind.NUMBER));

        ASTNode.UpdateStmt stmt = new ASTNode.UpdateStmt(
                1, 1, "student",
                assignments,
                null
        );
        analyzer.analyze(stmt);
    }

    @Test
    void update_columnNotExists_throwsException() {
        Map<String, ASTNode.LiteralExpr> assignments = new LinkedHashMap<>();
        assignments.put("nonexistent", new ASTNode.LiteralExpr(1, 20, "val", ASTNode.LiteralExpr.Kind.STRING));

        ASTNode.UpdateStmt stmt = new ASTNode.UpdateStmt(
                1, 1, "student",
                assignments,
                null
        );
        SqxdlException ex = assertThrows(SqxdlException.class, () -> analyzer.analyze(stmt));
        assertTrue(ex.getMessage().contains("不存在"));
    }

    @Test
    void update_typeMismatch_throwsException() {
        Map<String, ASTNode.LiteralExpr> assignments = new LinkedHashMap<>();
        assignments.put("age", new ASTNode.LiteralExpr(1, 20, "not_number", ASTNode.LiteralExpr.Kind.STRING));

        ASTNode.UpdateStmt stmt = new ASTNode.UpdateStmt(
                1, 1, "student",
                assignments,
                null
        );
        SqxdlException ex = assertThrows(SqxdlException.class, () -> analyzer.analyze(stmt));
        assertTrue(ex.getMessage().contains("类型不兼容"));
    }

    // ========== DELETE ==========

    @Test
    void delete_success() {
        ASTNode.DeleteStmt stmt = new ASTNode.DeleteStmt(
                1, 1, "student",
                null
        );
        analyzer.analyze(stmt);
    }

    @Test
    void delete_tableNotExists_throwsException() {
        ASTNode.DeleteStmt stmt = new ASTNode.DeleteStmt(
                1, 1, "nonexistent",
                null
        );
        SqxdlException ex = assertThrows(SqxdlException.class, () -> analyzer.analyze(stmt));
        assertTrue(ex.getMessage().contains("不存在"));
    }

    // ========== CREATE TABLE ==========

    @Test
    void createTable_success() {
        ASTNode.CreateTableStmt stmt = new ASTNode.CreateTableStmt(
                1, 1, "course",
                Arrays.asList(
                        new ASTNode.CreateTableStmt.ColumnDef("cid", "INT"),
                        new ASTNode.CreateTableStmt.ColumnDef("cname", "VARCHAR"))
        );
        analyzer.analyze(stmt);
    }

    @Test
    void createTable_alreadyExists_throwsException() {
        ASTNode.CreateTableStmt stmt = new ASTNode.CreateTableStmt(
                1, 1, "student",
                Arrays.asList(
                        new ASTNode.CreateTableStmt.ColumnDef("id", "INT"),
                        new ASTNode.CreateTableStmt.ColumnDef("name", "VARCHAR"))
        );
        SqxdlException ex = assertThrows(SqxdlException.class, () -> analyzer.analyze(stmt));
        assertTrue(ex.getMessage().contains("已存在"));
    }

    // ========== 嵌套表达式 ==========

    @Test
    void select_nestedArithmetic_success() {
        ASTNode.IdentifierExpr col = new ASTNode.IdentifierExpr(1, 10, "age");
        ASTNode.LiteralExpr lit = new ASTNode.LiteralExpr(1, 15, "10", ASTNode.LiteralExpr.Kind.NUMBER);
        ASTNode.BinaryExpr add = new ASTNode.BinaryExpr(1, 12, "+", col, lit);

        ASTNode.LiteralExpr lit2 = new ASTNode.LiteralExpr(1, 20, "30", ASTNode.LiteralExpr.Kind.NUMBER);
        ASTNode.BinaryExpr cond = new ASTNode.BinaryExpr(1, 18, ">", add, lit2);

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student",
                Arrays.asList("id"),
                cond
        , List.of(), List.of(), List.of());
        analyzer.analyze(stmt);
    }

    @Test
    void select_arithmeticWithString_throwsException() {
        ASTNode.IdentifierExpr col = new ASTNode.IdentifierExpr(1, 10, "name");
        ASTNode.LiteralExpr lit = new ASTNode.LiteralExpr(1, 15, "10", ASTNode.LiteralExpr.Kind.NUMBER);
        ASTNode.BinaryExpr add = new ASTNode.BinaryExpr(1, 12, "+", col, lit);

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student",
                Arrays.asList("id"),
                add
        , List.of(), List.of(), List.of());
        SqxdlException ex = assertThrows(SqxdlException.class, () -> analyzer.analyze(stmt));
        assertTrue(ex.getMessage().contains("数值类型"));
    }

    // ========== AND/OR 逻辑运算符类型检查 ==========

    @Test
    void select_andBothComparison_succeeds() {
        // WHERE (id > 1) AND (age < 20)
        ASTNode.BinaryExpr left = new ASTNode.BinaryExpr(1, 9, ">",
                new ASTNode.IdentifierExpr(1, 8, "id"), new ASTNode.LiteralExpr(1, 12, "1", ASTNode.LiteralExpr.Kind.NUMBER));
        ASTNode.BinaryExpr right = new ASTNode.BinaryExpr(1, 19, "<",
                new ASTNode.IdentifierExpr(1, 18, "age"), new ASTNode.LiteralExpr(1, 22, "20", ASTNode.LiteralExpr.Kind.NUMBER));
        ASTNode.BinaryExpr cond = new ASTNode.BinaryExpr(1, 14, "AND", left, right);

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(1, 1, "student", Arrays.asList("id"), cond, List.of(), List.of(), List.of());
        assertDoesNotThrow(() -> analyzer.analyze(stmt));
    }

    @Test
    void select_orBothComparison_succeeds() {
        // WHERE (id = 1) OR (id = 2)
        ASTNode.BinaryExpr left = new ASTNode.BinaryExpr(1, 9, "=",
                new ASTNode.IdentifierExpr(1, 8, "id"), new ASTNode.LiteralExpr(1, 12, "1", ASTNode.LiteralExpr.Kind.NUMBER));
        ASTNode.BinaryExpr right = new ASTNode.BinaryExpr(1, 19, "=",
                new ASTNode.IdentifierExpr(1, 18, "id"), new ASTNode.LiteralExpr(1, 23, "2", ASTNode.LiteralExpr.Kind.NUMBER));
        ASTNode.BinaryExpr cond = new ASTNode.BinaryExpr(1, 14, "OR", left, right);

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(1, 1, "student", Arrays.asList("id"), cond, List.of(), List.of(), List.of());
        assertDoesNotThrow(() -> analyzer.analyze(stmt));
    }

    @Test
    void select_andLeftNotBoolean_throwsException() {
        // WHERE (id + 1) AND (age > 18) — 左侧是算术表达式，不是布尔
        ASTNode.BinaryExpr left = new ASTNode.BinaryExpr(1, 9, "+",
                new ASTNode.IdentifierExpr(1, 8, "id"), new ASTNode.LiteralExpr(1, 12, "1", ASTNode.LiteralExpr.Kind.NUMBER));
        ASTNode.BinaryExpr right = new ASTNode.BinaryExpr(1, 19, ">",
                new ASTNode.IdentifierExpr(1, 18, "age"), new ASTNode.LiteralExpr(1, 22, "18", ASTNode.LiteralExpr.Kind.NUMBER));
        ASTNode.BinaryExpr cond = new ASTNode.BinaryExpr(1, 14, "AND", left, right);

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(1, 1, "student", Arrays.asList("id"), cond, List.of(), List.of(), List.of());
        SqxdlException ex = assertThrows(SqxdlException.class, () -> analyzer.analyze(stmt));
        assertTrue(ex.getMessage().contains("左侧必须是布尔表达式"));
    }

    @Test
    void select_andRightNotBoolean_throwsException() {
        // WHERE (id > 1) AND (name + 'x') — 右侧是字符串算术
        ASTNode.BinaryExpr left = new ASTNode.BinaryExpr(1, 9, ">",
                new ASTNode.IdentifierExpr(1, 8, "id"), new ASTNode.LiteralExpr(1, 12, "1", ASTNode.LiteralExpr.Kind.NUMBER));
        ASTNode.BinaryExpr right = new ASTNode.BinaryExpr(1, 19, "+",
                new ASTNode.IdentifierExpr(1, 18, "name"), new ASTNode.LiteralExpr(1, 22, "x", ASTNode.LiteralExpr.Kind.STRING));
        ASTNode.BinaryExpr cond = new ASTNode.BinaryExpr(1, 14, "AND", left, right);

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(1, 1, "student", Arrays.asList("id"), cond, List.of(), List.of(), List.of());
        SqxdlException ex = assertThrows(SqxdlException.class, () -> analyzer.analyze(stmt));
        // 右侧的 name+x 会先报算术运算错误（字符串不能做算术）
        assertTrue(ex.getMessage().contains("数值类型"));
    }

    @Test
    void select_nestedAndOr_succeeds() {
        // WHERE (id > 1 AND age < 20) OR (id = 0)
        ASTNode.BinaryExpr leftAnd = new ASTNode.BinaryExpr(1, 9, "AND",
                new ASTNode.BinaryExpr(1, 8, ">", new ASTNode.IdentifierExpr(1, 7, "id"), new ASTNode.LiteralExpr(1, 11, "1", ASTNode.LiteralExpr.Kind.NUMBER)),
                new ASTNode.BinaryExpr(1, 18, "<", new ASTNode.IdentifierExpr(1, 17, "age"), new ASTNode.LiteralExpr(1, 21, "20", ASTNode.LiteralExpr.Kind.NUMBER)));
        ASTNode.BinaryExpr right = new ASTNode.BinaryExpr(1, 28, "=",
                new ASTNode.IdentifierExpr(1, 27, "id"), new ASTNode.LiteralExpr(1, 31, "0", ASTNode.LiteralExpr.Kind.NUMBER));
        ASTNode.BinaryExpr cond = new ASTNode.BinaryExpr(1, 24, "OR", leftAnd, right);

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(1, 1, "student", Arrays.asList("id"), cond, List.of(), List.of(), List.of());
        assertDoesNotThrow(() -> analyzer.analyze(stmt));
    }

    // ========== SHOW TABLES ==========

    @Test
    void showTables_succeeds() {
        ASTNode.ShowStmt stmt = new ASTNode.ShowStmt(1, 1, "TABLES", null);
        assertDoesNotThrow(() -> analyzer.analyze(stmt));
    }

    // ========== DROP TABLE ==========

    @Test
    void dropTable_existing_succeeds() {
        ASTNode.DropTableStmt stmt = new ASTNode.DropTableStmt(1, 1, "student");
        assertDoesNotThrow(() -> analyzer.analyze(stmt));
    }

    @Test
    void dropTable_notExisting_throwsException() {
        ASTNode.DropTableStmt stmt = new ASTNode.DropTableStmt(1, 1, "nonexistent");
        SqxdlException ex = assertThrows(SqxdlException.class, () -> analyzer.analyze(stmt));
        assertTrue(ex.getMessage().contains("不存在"));
    }

    // ========== DOUBLE 类型检查 ==========

    @Test
    void select_doubleColumnComparison_succeeds() {
        // WHERE score > 3.14 —— DOUBLE 列与 DOUBLE 字面量比较
        ASTNode.IdentifierExpr col = new ASTNode.IdentifierExpr(1, 10, "score");
        ASTNode.LiteralExpr lit = new ASTNode.LiteralExpr(1, 18, "3.14", ASTNode.LiteralExpr.Kind.NUMBER);
        ASTNode.BinaryExpr cond = new ASTNode.BinaryExpr(1, 15, ">", col, lit);
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(1, 1, "student",
                Arrays.asList("id", "score"), cond, List.of(), List.of(), List.of());
        assertDoesNotThrow(() -> analyzer.analyze(stmt));
    }

    @Test
    void select_doubleComparedWithInt_succeeds() {
        // WHERE score > 90 —— DOUBLE 列与 INT 字面量比较（类型兼容）
        ASTNode.IdentifierExpr col = new ASTNode.IdentifierExpr(1, 10, "score");
        ASTNode.LiteralExpr lit = new ASTNode.LiteralExpr(1, 18, "90", ASTNode.LiteralExpr.Kind.NUMBER);
        ASTNode.BinaryExpr cond = new ASTNode.BinaryExpr(1, 15, ">", col, lit);
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(1, 1, "student",
                Arrays.asList("id"), cond, List.of(), List.of(), List.of());
        assertDoesNotThrow(() -> analyzer.analyze(stmt));
    }

    @Test
    void select_intColumnComparedWithDouble_succeeds() {
        // WHERE id > 1.5 —— INT 列与 DOUBLE 字面量比较（类型兼容）
        ASTNode.IdentifierExpr col = new ASTNode.IdentifierExpr(1, 10, "id");
        ASTNode.LiteralExpr lit = new ASTNode.LiteralExpr(1, 15, "1.5", ASTNode.LiteralExpr.Kind.NUMBER);
        ASTNode.BinaryExpr cond = new ASTNode.BinaryExpr(1, 12, ">", col, lit);
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(1, 1, "student",
                Arrays.asList("id"), cond, List.of(), List.of(), List.of());
        assertDoesNotThrow(() -> analyzer.analyze(stmt));
    }

    @Test
    void select_doubleArithmetic_succeeds() {
        // WHERE score + 0.5 > 60 —— DOUBLE 算术运算
        ASTNode.IdentifierExpr col = new ASTNode.IdentifierExpr(1, 10, "score");
        ASTNode.LiteralExpr lit = new ASTNode.LiteralExpr(1, 19, "0.5", ASTNode.LiteralExpr.Kind.NUMBER);
        ASTNode.BinaryExpr addExpr = new ASTNode.BinaryExpr(1, 15, "+", col, lit);
        ASTNode.LiteralExpr lit60 = new ASTNode.LiteralExpr(1, 25, "60", ASTNode.LiteralExpr.Kind.NUMBER);
        ASTNode.BinaryExpr cond = new ASTNode.BinaryExpr(1, 22, ">", addExpr, lit60);
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(1, 1, "student",
                Arrays.asList("id"), cond, List.of(), List.of(), List.of());
        assertDoesNotThrow(() -> analyzer.analyze(stmt));
    }

    @Test
    void select_doubleWithString_throwsTypeMismatch() {
        // WHERE score > 'abc' —— DOUBLE 与 VARCHAR 不兼容
        ASTNode.IdentifierExpr col = new ASTNode.IdentifierExpr(1, 10, "score");
        ASTNode.LiteralExpr lit = new ASTNode.LiteralExpr(1, 18, "abc", ASTNode.LiteralExpr.Kind.STRING);
        ASTNode.BinaryExpr cond = new ASTNode.BinaryExpr(1, 15, ">", col, lit);
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(1, 1, "student",
                Arrays.asList("id"), cond, List.of(), List.of(), List.of());
        SqxdlException ex = assertThrows(SqxdlException.class, () -> analyzer.analyze(stmt));
        assertTrue(ex.getMessage().contains("不兼容") || ex.getMessage().contains("类型"));
    }

    @Test
    void insert_doubleValue_succeeds() {
        catalog.createTableWithTypes("products", Arrays.asList(
                new CatalogImpl.ColumnInfo("id", CatalogImpl.DataType.INT),
                new CatalogImpl.ColumnInfo("price", CatalogImpl.DataType.DOUBLE)
        ));
        ASTNode.InsertStmt stmt = new ASTNode.InsertStmt(1, 1, "products",
                Arrays.asList("id", "price"),
                Arrays.asList(
                        new ASTNode.LiteralExpr(1, 25, "1", ASTNode.LiteralExpr.Kind.NUMBER),
                        new ASTNode.LiteralExpr(1, 30, "9.99", ASTNode.LiteralExpr.Kind.NUMBER)
                ));
        assertDoesNotThrow(() -> analyzer.analyze(stmt));
    }

    @Test
    void insert_doubleColumnWithString_throwsTypeMismatch() {
        catalog.createTableWithTypes("products", Arrays.asList(
                new CatalogImpl.ColumnInfo("id", CatalogImpl.DataType.INT),
                new CatalogImpl.ColumnInfo("price", CatalogImpl.DataType.DOUBLE)
        ));
        ASTNode.InsertStmt stmt = new ASTNode.InsertStmt(1, 1, "products",
                Arrays.asList("id", "price"),
                Arrays.asList(
                        new ASTNode.LiteralExpr(1, 25, "1", ASTNode.LiteralExpr.Kind.NUMBER),
                        new ASTNode.LiteralExpr(1, 30, "free", ASTNode.LiteralExpr.Kind.STRING)
                ));
        SqxdlException ex = assertThrows(SqxdlException.class, () -> analyzer.analyze(stmt));
        assertTrue(ex.getMessage().contains("不兼容") || ex.getMessage().contains("类型"));
    }

    @Test
    void update_doubleColumn_succeeds() {
        catalog.createTableWithTypes("products", Arrays.asList(
                new CatalogImpl.ColumnInfo("id", CatalogImpl.DataType.INT),
                new CatalogImpl.ColumnInfo("price", CatalogImpl.DataType.DOUBLE)
        ));
        Map<String, ASTNode.LiteralExpr> set = new LinkedHashMap<>();
        set.put("price", new ASTNode.LiteralExpr(1, 20, "19.99", ASTNode.LiteralExpr.Kind.NUMBER));
        ASTNode.UpdateStmt stmt = new ASTNode.UpdateStmt(1, 1, "products", set, null);
        assertDoesNotThrow(() -> analyzer.analyze(stmt));
    }

    // ========== 多表 JOIN 语义检查 ==========

    @Test
    void join_validOnCondition_succeeds() {
        // course 表：cid 仅在 course 中存在，id 仅在 student 中存在
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
        assertDoesNotThrow(() -> analyzer.analyze(stmt));
    }

    @Test
    void join_tableNotExists_throwsException() {
        ASTNode.SelectStmt.JoinClause join = new ASTNode.SelectStmt.JoinClause("nonexistent", null);
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("id"), null,
                List.of(join), List.of(), List.of());
        SqxdlException ex = assertThrows(SqxdlException.class, () -> analyzer.analyze(stmt));
        assertTrue(ex.getMessage().contains("不存在"));
    }

    @Test
    void join_onCondColumnNotExists_throwsException() {
        catalog.createTableWithTypes("course", Arrays.asList(
                new CatalogImpl.ColumnInfo("cid", CatalogImpl.DataType.INT),
                new CatalogImpl.ColumnInfo("cname", CatalogImpl.DataType.VARCHAR)
        ));
        // ON student.id = course.nonexistent
        ASTNode.BinaryExpr onCond = new ASTNode.BinaryExpr(1, 30, "=",
                new ASTNode.IdentifierExpr(1, 28, "id"),
                new ASTNode.IdentifierExpr(1, 35, "nonexistent"));
        ASTNode.SelectStmt.JoinClause join = new ASTNode.SelectStmt.JoinClause("course", onCond);

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("id"), null,
                List.of(join), List.of(), List.of());
        SqxdlException ex = assertThrows(SqxdlException.class, () -> analyzer.analyze(stmt));
        assertTrue(ex.getMessage().contains("不存在"));
    }

    @Test
    void join_ambiguousColumnInSelect_throwsException() {
        // enrollment 表也有 id 列 → SELECT id 歧义
        catalog.createTableWithTypes("enrollment", Arrays.asList(
                new CatalogImpl.ColumnInfo("id", CatalogImpl.DataType.INT),
                new CatalogImpl.ColumnInfo("student_id", CatalogImpl.DataType.INT),
                new CatalogImpl.ColumnInfo("grade", CatalogImpl.DataType.DOUBLE)
        ));
        ASTNode.BinaryExpr onCond = new ASTNode.BinaryExpr(1, 30, "=",
                new ASTNode.IdentifierExpr(1, 28, "student_id"),
                new ASTNode.IdentifierExpr(1, 40, "id"));
        ASTNode.SelectStmt.JoinClause join = new ASTNode.SelectStmt.JoinClause("enrollment", onCond);

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("id"), null,
                List.of(join), List.of(), List.of());
        SqxdlException ex = assertThrows(SqxdlException.class, () -> analyzer.analyze(stmt));
        assertTrue(ex.getMessage().contains("歧义"));
    }

    @Test
    void join_selectStar_expandsToAllTables() {
        catalog.createTableWithTypes("course", Arrays.asList(
                new CatalogImpl.ColumnInfo("cid", CatalogImpl.DataType.INT),
                new CatalogImpl.ColumnInfo("cname", CatalogImpl.DataType.VARCHAR)
        ));
        ASTNode.BinaryExpr onCond = new ASTNode.BinaryExpr(1, 30, "=",
                new ASTNode.IdentifierExpr(1, 28, "id"),
                new ASTNode.IdentifierExpr(1, 35, "cid"));
        ASTNode.SelectStmt.JoinClause join = new ASTNode.SelectStmt.JoinClause("course", onCond);

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("*"), null,
                List.of(join), List.of(), List.of());
        analyzer.analyze(stmt);

        List<String> expanded = analyzer.getExpandedColumns(stmt);
        // 应展开为 student(id,name,age,score) + course(cid,cname) 共 6 列
        assertEquals(6, expanded.size());
        assertTrue(expanded.contains("id"));
        assertTrue(expanded.contains("cname"));
    }

    // ========== GROUP BY 语义检查 ==========

    @Test
    void groupBy_validColumn_succeeds() {
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("age"), null,
                List.of(), List.of("age"), List.of());
        assertDoesNotThrow(() -> analyzer.analyze(stmt));
    }

    @Test
    void groupBy_columnNotExists_throwsException() {
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("age"), null,
                List.of(), List.of("nonexistent"), List.of());
        SqxdlException ex = assertThrows(SqxdlException.class, () -> analyzer.analyze(stmt));
        assertTrue(ex.getMessage().contains("不存在"));
    }

    @Test
    void groupBy_multipleColumns_succeeds() {
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("age", "score"), null,
                List.of(), List.of("age", "score"), List.of());
        assertDoesNotThrow(() -> analyzer.analyze(stmt));
    }

    // ========== ORDER BY 语义检查 ==========

    @Test
    void orderBy_validColumn_succeeds() {
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("id"), null,
                List.of(), List.of(),
                List.of(new ASTNode.SelectStmt.OrderItem("age", "ASC")));
        assertDoesNotThrow(() -> analyzer.analyze(stmt));
    }

    @Test
    void orderBy_columnNotExists_throwsException() {
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("id"), null,
                List.of(), List.of(),
                List.of(new ASTNode.SelectStmt.OrderItem("nonexistent", "DESC")));
        SqxdlException ex = assertThrows(SqxdlException.class, () -> analyzer.analyze(stmt));
        assertTrue(ex.getMessage().contains("不存在"));
    }

    @Test
    void orderBy_multipleColumns_succeeds() {
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("id"), null,
                List.of(), List.of(),
                List.of(
                        new ASTNode.SelectStmt.OrderItem("age", "ASC"),
                        new ASTNode.SelectStmt.OrderItem("score", "DESC")));
        assertDoesNotThrow(() -> analyzer.analyze(stmt));
    }

    // ========== 组合：JOIN + WHERE + GROUP BY + ORDER BY ==========

    @Test
    void join_where_groupBy_orderBy_allSucceeds() {
        catalog.createTableWithTypes("course", Arrays.asList(
                new CatalogImpl.ColumnInfo("cid", CatalogImpl.DataType.INT),
                new CatalogImpl.ColumnInfo("cname", CatalogImpl.DataType.VARCHAR),
                new CatalogImpl.ColumnInfo("credit", CatalogImpl.DataType.INT)
        ));
        // ON student.id = course.cid
        ASTNode.BinaryExpr onCond = new ASTNode.BinaryExpr(1, 30, "=",
                new ASTNode.IdentifierExpr(1, 28, "id"),
                new ASTNode.IdentifierExpr(1, 35, "cid"));
        ASTNode.SelectStmt.JoinClause join = new ASTNode.SelectStmt.JoinClause("course", onCond);

        // WHERE age > 18
        ASTNode.BinaryExpr whereCond = new ASTNode.BinaryExpr(1, 50, ">",
                new ASTNode.IdentifierExpr(1, 48, "age"),
                new ASTNode.LiteralExpr(1, 55, "18", ASTNode.LiteralExpr.Kind.NUMBER));

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("name", "cname"), whereCond,
                List.of(join), List.of("age"),
                List.of(new ASTNode.SelectStmt.OrderItem("cname", "ASC")));
        assertDoesNotThrow(() -> analyzer.analyze(stmt));
    }

    // ========== 点限定标识符（table.column） ==========

    @Test
    void qualifiedColumn_inSelectList_succeeds() {
        // SELECT student.id FROM student JOIN course ON student.id = course.cid
        catalog.createTableWithTypes("course", Arrays.asList(
                new CatalogImpl.ColumnInfo("cid", CatalogImpl.DataType.INT),
                new CatalogImpl.ColumnInfo("cname", CatalogImpl.DataType.VARCHAR)
        ));
        ASTNode.BinaryExpr onCond = new ASTNode.BinaryExpr(1, 30, "=",
                new ASTNode.IdentifierExpr(1, 28, "student.id"),
                new ASTNode.IdentifierExpr(1, 40, "course.cid"));
        ASTNode.SelectStmt.JoinClause join = new ASTNode.SelectStmt.JoinClause("course", onCond);

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("student.id"), null,
                List.of(join), List.of(), List.of());
        assertDoesNotThrow(() -> analyzer.analyze(stmt));
    }

    @Test
    void qualifiedColumn_resolvesAmbiguity_succeeds() {
        // 两表都有 id 列，用 student.id 消歧义
        catalog.createTableWithTypes("enrollment", Arrays.asList(
                new CatalogImpl.ColumnInfo("id", CatalogImpl.DataType.INT),
                new CatalogImpl.ColumnInfo("student_id", CatalogImpl.DataType.INT)
        ));
        ASTNode.BinaryExpr onCond = new ASTNode.BinaryExpr(1, 30, "=",
                new ASTNode.IdentifierExpr(1, 28, "student.id"),
                new ASTNode.IdentifierExpr(1, 40, "enrollment.student_id"));
        ASTNode.SelectStmt.JoinClause join = new ASTNode.SelectStmt.JoinClause("enrollment", onCond);

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("student.id"), null,
                List.of(join), List.of(), List.of());
        assertDoesNotThrow(() -> analyzer.analyze(stmt));
    }

    @Test
    void qualifiedColumn_tableNotInQuery_throwsException() {
        // student.id 但 student 表不在查询中（只有 course）
        catalog.createTableWithTypes("course", Arrays.asList(
                new CatalogImpl.ColumnInfo("cid", CatalogImpl.DataType.INT)
        ));
        ASTNode.BinaryExpr onCond = new ASTNode.BinaryExpr(1, 30, "=",
                new ASTNode.IdentifierExpr(1, 28, "cid"),
                new ASTNode.IdentifierExpr(1, 40, "course.cid"));
        ASTNode.SelectStmt.JoinClause join = new ASTNode.SelectStmt.JoinClause("course", onCond);

        // 主表 nonexistent，但 JOIN course
        // 这里改为：主表是 course，引用 nonexistent.id
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "course", Arrays.asList("nonexistent.id"), null,
                List.of(), List.of(), List.of());
        SqxdlException ex = assertThrows(SqxdlException.class, () -> analyzer.analyze(stmt));
        assertTrue(ex.getMessage().contains("不在当前查询涉及的表中"));
    }

    @Test
    void qualifiedColumn_columnNotExists_throwsException() {
        catalog.createTableWithTypes("course", Arrays.asList(
                new CatalogImpl.ColumnInfo("cid", CatalogImpl.DataType.INT)
        ));
        ASTNode.BinaryExpr onCond = new ASTNode.BinaryExpr(1, 30, "=",
                new ASTNode.IdentifierExpr(1, 28, "student.id"),
                new ASTNode.IdentifierExpr(1, 40, "course.cid"));
        ASTNode.SelectStmt.JoinClause join = new ASTNode.SelectStmt.JoinClause("course", onCond);

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("course.nonexistent"), null,
                List.of(join), List.of(), List.of());
        SqxdlException ex = assertThrows(SqxdlException.class, () -> analyzer.analyze(stmt));
        assertTrue(ex.getMessage().contains("不存在"));
    }

    @Test
    void qualifiedColumn_inOnCondition_succeeds() {
        catalog.createTableWithTypes("course", Arrays.asList(
                new CatalogImpl.ColumnInfo("cid", CatalogImpl.DataType.INT),
                new CatalogImpl.ColumnInfo("cname", CatalogImpl.DataType.VARCHAR)
        ));
        // ON student.id = course.cid — 两边都用点限定
        ASTNode.BinaryExpr onCond = new ASTNode.BinaryExpr(1, 30, "=",
                new ASTNode.IdentifierExpr(1, 28, "student.id"),
                new ASTNode.IdentifierExpr(1, 40, "course.cid"));
        ASTNode.SelectStmt.JoinClause join = new ASTNode.SelectStmt.JoinClause("course", onCond);

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("student.name", "course.cname"), null,
                List.of(join), List.of(), List.of());
        assertDoesNotThrow(() -> analyzer.analyze(stmt));
    }

    @Test
    void qualifiedColumn_inWhere_succeeds() {
        catalog.createTableWithTypes("course", Arrays.asList(
                new CatalogImpl.ColumnInfo("cid", CatalogImpl.DataType.INT),
                new CatalogImpl.ColumnInfo("cname", CatalogImpl.DataType.VARCHAR)
        ));
        ASTNode.BinaryExpr onCond = new ASTNode.BinaryExpr(1, 30, "=",
                new ASTNode.IdentifierExpr(1, 28, "student.id"),
                new ASTNode.IdentifierExpr(1, 40, "course.cid"));
        ASTNode.SelectStmt.JoinClause join = new ASTNode.SelectStmt.JoinClause("course", onCond);

        // WHERE student.age > 18
        ASTNode.BinaryExpr whereCond = new ASTNode.BinaryExpr(1, 50, ">",
                new ASTNode.IdentifierExpr(1, 48, "student.age"),
                new ASTNode.LiteralExpr(1, 55, "18", ASTNode.LiteralExpr.Kind.NUMBER));

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("student.name"), whereCond,
                List.of(join), List.of(), List.of());
        assertDoesNotThrow(() -> analyzer.analyze(stmt));
    }

    @Test
    void qualifiedColumn_inGroupBy_succeeds() {
        catalog.createTableWithTypes("course", Arrays.asList(
                new CatalogImpl.ColumnInfo("cid", CatalogImpl.DataType.INT),
                new CatalogImpl.ColumnInfo("cname", CatalogImpl.DataType.VARCHAR)
        ));
        ASTNode.BinaryExpr onCond = new ASTNode.BinaryExpr(1, 30, "=",
                new ASTNode.IdentifierExpr(1, 28, "student.id"),
                new ASTNode.IdentifierExpr(1, 40, "course.cid"));
        ASTNode.SelectStmt.JoinClause join = new ASTNode.SelectStmt.JoinClause("course", onCond);

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("student.age"), null,
                List.of(join), List.of("student.age"), List.of());
        assertDoesNotThrow(() -> analyzer.analyze(stmt));
    }

    @Test
    void qualifiedColumn_inOrderBy_succeeds() {
        catalog.createTableWithTypes("course", Arrays.asList(
                new CatalogImpl.ColumnInfo("cid", CatalogImpl.DataType.INT),
                new CatalogImpl.ColumnInfo("cname", CatalogImpl.DataType.VARCHAR)
        ));
        ASTNode.BinaryExpr onCond = new ASTNode.BinaryExpr(1, 30, "=",
                new ASTNode.IdentifierExpr(1, 28, "student.id"),
                new ASTNode.IdentifierExpr(1, 40, "course.cid"));
        ASTNode.SelectStmt.JoinClause join = new ASTNode.SelectStmt.JoinClause("course", onCond);

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("student.name"), null,
                List.of(join), List.of(),
                List.of(new ASTNode.SelectStmt.OrderItem("course.cname", "ASC")));
        assertDoesNotThrow(() -> analyzer.analyze(stmt));
    }

    // ========== 聚合函数 SUM/AVG/MIN/MAX ==========

    @Test
    void sum_validNumericColumn_succeeds() {
        // SELECT SUM(age) FROM student（age 为 INT）
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("SUM(age)"), null,
                List.of(), List.of(), List.of());
        assertDoesNotThrow(() -> analyzer.analyze(stmt));
    }

    @Test
    void avg_validNumericColumn_succeeds() {
        // SELECT AVG(age) FROM student
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("AVG(age)"), null,
                List.of(), List.of(), List.of());
        assertDoesNotThrow(() -> analyzer.analyze(stmt));
    }

    @Test
    void min_validColumn_succeeds() {
        // SELECT MIN(age) FROM student
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("MIN(age)"), null,
                List.of(), List.of(), List.of());
        assertDoesNotThrow(() -> analyzer.analyze(stmt));
    }

    @Test
    void max_validColumn_succeeds() {
        // SELECT MAX(age) FROM student
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("MAX(age)"), null,
                List.of(), List.of(), List.of());
        assertDoesNotThrow(() -> analyzer.analyze(stmt));
    }

    @Test
    void sum_varcharColumn_throwsTypeMismatch() {
        // SELECT SUM(name) FROM student（name 为 VARCHAR，不合法）
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("SUM(name)"), null,
                List.of(), List.of(), List.of());
        SqxdlException ex = assertThrows(SqxdlException.class,
                () -> analyzer.analyze(stmt));
        assertTrue(ex.getMessage().contains("SUM"));
        assertTrue(ex.getMessage().contains("不合法"));
    }

    @Test
    void avg_varcharColumn_throwsTypeMismatch() {
        // SELECT AVG(name) FROM student（name 为 VARCHAR，不合法）
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("AVG(name)"), null,
                List.of(), List.of(), List.of());
        assertThrows(SqxdlException.class, () -> analyzer.analyze(stmt));
    }

    @Test
    void min_varcharColumn_succeeds() {
        // SELECT MIN(name) FROM student（MIN 允许字符串）
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("MIN(name)"), null,
                List.of(), List.of(), List.of());
        assertDoesNotThrow(() -> analyzer.analyze(stmt));
    }

    @Test
    void max_varcharColumn_succeeds() {
        // SELECT MAX(name) FROM student（MAX 允许字符串）
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("MAX(name)"), null,
                List.of(), List.of(), List.of());
        assertDoesNotThrow(() -> analyzer.analyze(stmt));
    }

    @Test
    void sum_nonExistentColumn_throwsError() {
        // SELECT SUM(gpa) FROM student（gpa 列不存在）
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student", Arrays.asList("SUM(gpa)"), null,
                List.of(), List.of(), List.of());
        assertThrows(SqxdlException.class, () -> analyzer.analyze(stmt));
    }

    @Test
    void aggregate_withGroupBy_succeeds() {
        // SELECT grade, SUM(age), COUNT(*) FROM student GROUP BY grade
        // 需要添加 grade 列
        catalog.createTableWithTypes("temp_student", Arrays.asList(
                new CatalogImpl.ColumnInfo("id", CatalogImpl.DataType.INT),
                new CatalogImpl.ColumnInfo("grade", CatalogImpl.DataType.VARCHAR),
                new CatalogImpl.ColumnInfo("age", CatalogImpl.DataType.INT)
        ));
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "temp_student",
                Arrays.asList("grade", "SUM(age)", "COUNT(*)"),
                null, List.of(), List.of("grade"), List.of());
        assertDoesNotThrow(() -> analyzer.analyze(stmt));
    }

    @Test
    void aggregate_multiTable_qualifiedColumn_succeeds() {
        // SELECT student.name, SUM(course.cid) FROM student JOIN course ON ...
        catalog.createTableWithTypes("course", Arrays.asList(
                new CatalogImpl.ColumnInfo("cid", CatalogImpl.DataType.INT),
                new CatalogImpl.ColumnInfo("cname", CatalogImpl.DataType.VARCHAR)
        ));
        ASTNode.BinaryExpr onCond = new ASTNode.BinaryExpr(1, 30, "=",
                new ASTNode.IdentifierExpr(1, 28, "student.id"),
                new ASTNode.IdentifierExpr(1, 40, "course.cid"));
        ASTNode.SelectStmt.JoinClause join = new ASTNode.SelectStmt.JoinClause("course", onCond);

        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student",
                Arrays.asList("student.name", "SUM(course.cid)"),
                null, List.of(join), List.of(), List.of());
        assertDoesNotThrow(() -> analyzer.analyze(stmt));
    }
}
