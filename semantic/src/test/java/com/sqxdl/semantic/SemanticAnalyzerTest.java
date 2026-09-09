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
                new CatalogImpl.ColumnInfo("age", CatalogImpl.DataType.INT)
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
        );
        analyzer.analyze(stmt);
    }

    @Test
    void select_tableNotExists_throwsException() {
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "nonexistent",
                Arrays.asList("id"),
                null
        );
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
        );
        analyzer.analyze(stmt);
    }

    @Test
    void select_columnNotExists_throwsException() {
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student",
                Arrays.asList("id", "nonexistent"),
                null
        );
        SqxdlException ex = assertThrows(SqxdlException.class, () -> analyzer.analyze(stmt));
        assertTrue(ex.getMessage().contains("不存在列"));
    }

    // ========== SELECT：* 展开 ==========

    @Test
    void selectStar_expandsToAllColumns() {
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student",
                Arrays.asList("*"),
                null
        );
        analyzer.analyze(stmt);

        List<String> expanded = analyzer.getExpandedColumns(stmt);
        assertEquals(Arrays.asList("id", "name", "age"), expanded);
    }

    @Test
    void selectNonStar_returnsSameColumns() {
        ASTNode.SelectStmt stmt = new ASTNode.SelectStmt(
                1, 1, "student",
                Arrays.asList("id", "name"),
                null
        );
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
        );
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
        );
        SqxdlException ex = assertThrows(SqxdlException.class, () -> analyzer.analyze(stmt));
        assertTrue(ex.getMessage().contains("不存在列"));
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
        );
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
        );
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
        );
        analyzer.analyze(stmt);
    }

    // ========== INSERT ==========

    @Test
    void insert_success() {
        List<ASTNode.LiteralExpr> values = Arrays.asList(
                new ASTNode.LiteralExpr(1, 20, "1", ASTNode.LiteralExpr.Kind.NUMBER),
                new ASTNode.LiteralExpr(1, 25, "Alice", ASTNode.LiteralExpr.Kind.STRING),
                new ASTNode.LiteralExpr(1, 30, "18", ASTNode.LiteralExpr.Kind.NUMBER)
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
                new ASTNode.LiteralExpr(1, 30, "18", ASTNode.LiteralExpr.Kind.NUMBER)
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
        assertTrue(ex.getMessage().contains("不存在列"));
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
        assertTrue(ex.getMessage().contains("不存在列"));
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
        );
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
        );
        SqxdlException ex = assertThrows(SqxdlException.class, () -> analyzer.analyze(stmt));
        assertTrue(ex.getMessage().contains("整数类型"));
    }
}
