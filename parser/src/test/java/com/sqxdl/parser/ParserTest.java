package com.sqxdl.parser;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parser 单元测试：覆盖无 WHERE 版本的 SELECT 解析、SELECT *、多列、大小写与语法错误。
 */
class ParserTest {

    private ASTNode parse(String sql) {
        return new Parser(new Lexer(sql)).parse();
    }

    private List<ASTNode> parseAll(String sql) {
        return new Parser(new Lexer(sql)).parseAll();
    }

    @Test
    void basicSelect() {
        ASTNode.SelectStmt stmt = (ASTNode.SelectStmt) parse("SELECT id FROM t;");
        assertEquals("t", stmt.getTableName());
        assertEquals(List.of("id"), stmt.getSelectList());
        assertNull(stmt.getWhereCond(), "无 WHERE 子句时 whereCond 应为 null");
    }

    @Test
    void selectMultipleColumns() {
        ASTNode.SelectStmt stmt = (ASTNode.SelectStmt) parse("SELECT id, name, age FROM student");
        assertEquals("student", stmt.getTableName());
        assertEquals(List.of("id", "name", "age"), stmt.getSelectList());
    }

    @Test
    void selectStarStoredAsAsterisk() {
        ASTNode.SelectStmt stmt = (ASTNode.SelectStmt) parse("SELECT * FROM t;");
        assertEquals(List.of("*"), stmt.getSelectList(), "SELECT * 按约定存单元素 [\"*\"]");
    }

    @Test
    void keywordCaseInsensitive() {
        ASTNode.SelectStmt stmt = (ASTNode.SelectStmt) parse("select id from t");
        assertEquals("t", stmt.getTableName());
        assertEquals(List.of("id"), stmt.getSelectList());
    }

    @Test
    void missingColumnListThrows() {
        SqxdlException e = assertThrows(SqxdlException.class, () -> parse("SELECT FROM t;"));
        assertTrue(e.getMessage().contains("期望 IDENTIFIER"), "缺列清单应报期望标识符");
    }

    @Test
    void missingTableNameThrows() {
        SqxdlException e = assertThrows(SqxdlException.class, () -> parse("SELECT id FROM;"));
        assertTrue(e.getMessage().contains("期望 IDENTIFIER"));
    }

    @Test
    void trailingContentAfterSemicolonThrows() {
        // 分号后的 garbage 不是下一条语句，应报错
        SqxdlException e = assertThrows(SqxdlException.class,
                () -> parse("SELECT id FROM t; garbage"));
        assertTrue(e.getMessage().contains("期望分号或下一条语句"));
    }

    // ========== 多语句输入 ==========

    @Test
    void multipleStatementsParsedInOrder() {
        List<ASTNode> stmts = parseAll("SELECT id FROM t; INSERT INTO s VALUES (1); DELETE FROM u;");
        assertEquals(3, stmts.size());
        assertTrue(stmts.get(0) instanceof ASTNode.SelectStmt);
        assertTrue(stmts.get(1) instanceof ASTNode.InsertStmt);
        assertTrue(stmts.get(2) instanceof ASTNode.DeleteStmt);
    }

    @Test
    void multipleStatementsWithoutTrailingSemicolon() {
        // 最后一条可省略分号
        List<ASTNode> stmts = parseAll("SELECT id FROM t; SELECT name FROM t");
        assertEquals(2, stmts.size());
    }

    @Test
    void emptyInputReturnsNoStatements() {
        assertTrue(parseAll("").isEmpty());
        assertTrue(parseAll(";;;").isEmpty(), "只有分号视为空输入");
    }

    @Test
    void mixedCreateSelectUpdate() {
        List<ASTNode> stmts = parseAll(
                "CREATE TABLE t (a, b); INSERT INTO t VALUES (1, 2); UPDATE t SET a = 3;");
        assertEquals(3, stmts.size());
        assertTrue(stmts.get(0) instanceof ASTNode.CreateTableStmt);
        assertTrue(stmts.get(2) instanceof ASTNode.UpdateStmt);
    }

    @Test
    void statementMissingSemicolonBetweenTwoThrows() {
        // "SELECT id FROM t SELECT x" 第二个 SELECT 是下一条语句，宽容通过；
        // 但 "SELECT id FROM t 42" 的 42 既非分号也非语句，应报错
        SqxdlException e = assertThrows(SqxdlException.class,
                () -> parseAll("SELECT id FROM t 42"));
        assertTrue(e.getMessage().contains("期望分号或下一条语句"));
    }

    @Test
    void missingSelectKeywordThrows() {
        // 以标识符开头不是合法语句，由 parse() 分发层拦截
        SqxdlException e = assertThrows(SqxdlException.class, () -> parse("id FROM t;"));
        assertTrue(e.getMessage().contains("无法识别的语句"));
    }

    @Test
    void nodeCarriesPosition() {
        ASTNode.SelectStmt stmt = (ASTNode.SelectStmt) parse("SELECT id FROM t");
        assertEquals(1, stmt.getLine());
        assertEquals(1, stmt.getCol(), "SelectStmt 位置应指向 SELECT 关键字");
    }

    // ========== WHERE 与表达式 ==========

    @Test
    void whereSimpleComparison() {
        ASTNode.SelectStmt stmt = (ASTNode.SelectStmt) parse("SELECT id FROM t WHERE age >= 18");
        ASTNode.BinaryExpr cond = (ASTNode.BinaryExpr) stmt.getWhereCond();
        assertEquals(">=", cond.getOp());
        assertEquals("age", ((ASTNode.ColumnRef) cond.getLeft()).getName());
        assertEquals("18", ((ASTNode.LiteralExpr) cond.getRight()).getValue());
    }

    @Test
    void whereLeftIsColumnRightIsColumn() {
        // 列对列比较：a = b
        ASTNode.SelectStmt stmt = (ASTNode.SelectStmt) parse("SELECT * FROM t WHERE a = b");
        ASTNode.BinaryExpr cond = (ASTNode.BinaryExpr) stmt.getWhereCond();
        assertEquals("a", ((ASTNode.ColumnRef) cond.getLeft()).getName());
        assertEquals("b", ((ASTNode.ColumnRef) cond.getRight()).getName());
    }

    @Test
    void whereStringLiteralKind() {
        ASTNode.SelectStmt stmt = (ASTNode.SelectStmt) parse("SELECT * FROM t WHERE name = 'Tom'");
        ASTNode.BinaryExpr cond = (ASTNode.BinaryExpr) stmt.getWhereCond();
        ASTNode.LiteralExpr right = (ASTNode.LiteralExpr) cond.getRight();
        assertEquals(ASTNode.LiteralExpr.Kind.STRING, right.getKind());
        assertEquals("Tom", right.getValue());
    }

    @Test
    void whereArithmeticAssociatesLeft() {
        // 优先级：age = (1 + 2*x) 而不是 (age = 1) + 2*x；含列 x 的部分保持树形
        ASTNode.SelectStmt stmt = (ASTNode.SelectStmt) parse("SELECT * FROM t WHERE age = 1 + 2 * x");
        ASTNode.BinaryExpr cond = (ASTNode.BinaryExpr) stmt.getWhereCond();
        assertEquals("=", cond.getOp());
        ASTNode.BinaryExpr right = (ASTNode.BinaryExpr) cond.getRight();
        assertEquals("+", right.getOp());
        assertEquals("1", ((ASTNode.LiteralExpr) right.getLeft()).getValue());
        ASTNode.BinaryExpr mul = (ASTNode.BinaryExpr) right.getRight();
        assertEquals("*", mul.getOp(), "* 优先级高于 +");
        assertEquals("2", ((ASTNode.LiteralExpr) mul.getLeft()).getValue());
        assertEquals("x", ((ASTNode.ColumnRef) mul.getRight()).getName());
    }

    @Test
    void whereLogicalAnd() {
        ASTNode.SelectStmt stmt = (ASTNode.SelectStmt) parse("SELECT * FROM t WHERE age >= 18 AND name != 'Tom'");
        ASTNode.BinaryExpr cond = (ASTNode.BinaryExpr) stmt.getWhereCond();
        assertEquals("AND", cond.getOp(), "AND 关键字应规范化为大写");
    }

    @Test
    void whereSymbolicAndOrBothSupported() {
        // && 符号与 || 符号写法
        ASTNode.SelectStmt stmt = (ASTNode.SelectStmt) parse("SELECT * FROM t WHERE a = 1 && b = 2 || c = 3");
        ASTNode.BinaryExpr or = (ASTNode.BinaryExpr) stmt.getWhereCond();
        assertEquals("||", or.getOp(), "|| 优先级低于 &&");
        assertEquals("&&", ((ASTNode.BinaryExpr) or.getLeft()).getOp());
    }

    @Test
    void whereMissingRightOperandThrows() {
        SqxdlException e = assertThrows(SqxdlException.class, () -> parse("SELECT * FROM t WHERE age >"));
        assertTrue(e.getMessage().contains("期望列名或常量"), "缺右操作数应报错");
    }

    // ========== 常量折叠与逻辑简化 ==========

    @Test
    void constantFoldArithmetic() {
        // age = 1+2 → age = 3
        ASTNode.SelectStmt stmt = (ASTNode.SelectStmt) parse("SELECT * FROM t WHERE age = 1 + 2");
        ASTNode.BinaryExpr cond = (ASTNode.BinaryExpr) stmt.getWhereCond();
        assertEquals("=", cond.getOp());
        ASTNode.LiteralExpr right = (ASTNode.LiteralExpr) cond.getRight();
        assertEquals(ASTNode.LiteralExpr.Kind.NUMBER, right.getKind());
        assertEquals("3", right.getValue());
    }

    @Test
    void constantFoldNestedExpression() {
        // 2*3 在内部先折叠：(1 + 2*3) → (1 + 6) → 7
        ASTNode.SelectStmt stmt = (ASTNode.SelectStmt) parse("SELECT * FROM t WHERE x = 1 + 2 * 3");
        ASTNode.BinaryExpr cond = (ASTNode.BinaryExpr) stmt.getWhereCond();
        assertEquals("7", ((ASTNode.LiteralExpr) cond.getRight()).getValue());
    }

    @Test
    void constantFoldDecimal() {
        ASTNode.SelectStmt stmt = (ASTNode.SelectStmt) parse("SELECT * FROM t WHERE x = 1.5 + 2");
        ASTNode.BinaryExpr cond = (ASTNode.BinaryExpr) stmt.getWhereCond();
        assertEquals("3.5", ((ASTNode.LiteralExpr) cond.getRight()).getValue());
    }

    @Test
    void constantFoldDivisionByZeroThrows() {
        SqxdlException e = assertThrows(SqxdlException.class,
                () -> parse("SELECT * FROM t WHERE x = 1 / 0"));
        assertTrue(e.getMessage().contains("除零错误"));
    }

    @Test
    void logicalSimplifyAndTrue() {
        // cond AND true → cond（老师要求）
        ASTNode.SelectStmt stmt = (ASTNode.SelectStmt) parse("SELECT * FROM t WHERE age > 18 AND true");
        ASTNode.BinaryExpr cond = (ASTNode.BinaryExpr) stmt.getWhereCond();
        assertEquals(">", cond.getOp(), "true 应被消除，剩下 age > 18");
    }

    @Test
    void logicalSimplifyAndFalse() {
        // cond AND false → false
        ASTNode.SelectStmt stmt = (ASTNode.SelectStmt) parse("SELECT * FROM t WHERE age > 18 && false");
        ASTNode.LiteralExpr cond = (ASTNode.LiteralExpr) stmt.getWhereCond();
        assertEquals(ASTNode.LiteralExpr.Kind.BOOLEAN, cond.getKind());
        assertEquals("FALSE", cond.getValue());
    }

    @Test
    void logicalSimplifyOrFalse() {
        // cond OR false → cond
        ASTNode.SelectStmt stmt = (ASTNode.SelectStmt) parse("SELECT * FROM t WHERE age > 18 OR false");
        ASTNode.BinaryExpr cond = (ASTNode.BinaryExpr) stmt.getWhereCond();
        assertEquals(">", cond.getOp());
    }

    @Test
    void logicalSimplifyOrTrue() {
        // cond OR true → true（恒真条件）
        ASTNode.SelectStmt stmt = (ASTNode.SelectStmt) parse("SELECT * FROM t WHERE age > 18 || true");
        ASTNode.LiteralExpr cond = (ASTNode.LiteralExpr) stmt.getWhereCond();
        assertEquals("TRUE", cond.getValue());
    }

    @Test
    void booleanKeywordCaseInsensitive() {
        ASTNode.SelectStmt stmt = (ASTNode.SelectStmt) parse("SELECT * FROM t WHERE active = true");
        ASTNode.BinaryExpr cond = (ASTNode.BinaryExpr) stmt.getWhereCond();
        ASTNode.LiteralExpr right = (ASTNode.LiteralExpr) cond.getRight();
        assertEquals(ASTNode.LiteralExpr.Kind.BOOLEAN, right.getKind());
        assertEquals("TRUE", right.getValue(), "小写 true 应规范化为大写");
    }

    // ========== INSERT / UPDATE / DELETE / CREATE ==========

    @Test
    void insertWithColumns() {
        ASTNode.InsertStmt stmt = (ASTNode.InsertStmt) parse(
                "INSERT INTO student (id, name) VALUES (1, 'Tom');");
        assertEquals("student", stmt.getTableName());
        assertEquals(List.of("id", "name"), stmt.getColumns());
        assertEquals(2, stmt.getValues().size());
        assertEquals(ASTNode.LiteralExpr.Kind.NUMBER, stmt.getValues().get(0).getKind());
        assertEquals(ASTNode.LiteralExpr.Kind.STRING, stmt.getValues().get(1).getKind());
        assertEquals("Tom", stmt.getValues().get(1).getValue());
    }

    @Test
    void insertWithoutColumns() {
        ASTNode.InsertStmt stmt = (ASTNode.InsertStmt) parse(
                "INSERT INTO student VALUES (1, 'Tom')");
        assertEquals(0, stmt.getColumns().size(), "未指定列清单时应为空列表");
    }

    @Test
    void insertMissingValuesKeywordThrows() {
        SqxdlException e = assertThrows(SqxdlException.class,
                () -> parse("INSERT INTO t (a) (1)"));
        assertTrue(e.getMessage().contains("期望关键字 VALUES"));
    }

    @Test
    void updateSingleAssignment() {
        ASTNode.UpdateStmt stmt = (ASTNode.UpdateStmt) parse("UPDATE student SET age = 20 WHERE id = 1;");
        assertEquals("student", stmt.getTableName());
        assertEquals(1, stmt.getAssignments().size());
        assertEquals("20", stmt.getAssignments().get("age").getValue());
        ASTNode.BinaryExpr where = (ASTNode.BinaryExpr) stmt.getWhereCond();
        assertEquals("id", ((ASTNode.ColumnRef) where.getLeft()).getName());
    }

    @Test
    void updateMultipleAssignments() {
        ASTNode.UpdateStmt stmt = (ASTNode.UpdateStmt) parse(
                "UPDATE student SET age = 20, name = 'Bob'");
        assertEquals(Map.of("age", 1, "name", 2).keySet(), stmt.getAssignments().keySet());
        assertNull(stmt.getWhereCond());
    }

    @Test
    void deleteWithWhere() {
        ASTNode.DeleteStmt stmt = (ASTNode.DeleteStmt) parse("DELETE FROM student WHERE age < 18");
        assertEquals("student", stmt.getTableName());
        assertEquals("<", ((ASTNode.BinaryExpr) stmt.getWhereCond()).getOp());
    }

    @Test
    void deleteWithoutWhere() {
        ASTNode.DeleteStmt stmt = (ASTNode.DeleteStmt) parse("DELETE FROM student");
        assertNull(stmt.getWhereCond());
    }

    @Test
    void createTable() {
        ASTNode.CreateTableStmt stmt = (ASTNode.CreateTableStmt) parse(
                "CREATE TABLE student (id, name, age);");
        assertEquals("student", stmt.getTableName());
        assertEquals(List.of("id", "name", "age"), stmt.getColumns());
    }

    @Test
    void createTableMissingClosingParenThrows() {
        SqxdlException e = assertThrows(SqxdlException.class,
                () -> parse("CREATE TABLE t (id, name"));
        assertTrue(e.getMessage().contains("期望 ')'"));
    }

    @Test
    void unknownStatementThrows() {
        SqxdlException e = assertThrows(SqxdlException.class, () -> parse("DROP TABLE t"));
        assertTrue(e.getMessage().contains("无法识别的语句"));
    }
}
