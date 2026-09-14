package com.sqxdl.parser;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SqlPrinter（规范 SQL 还原）单元测试。
 * 验证：还原文本正确、常量折叠在还原中体现、round-trip 结构稳定
 * （parse → print → parse 两次还原结果一致，证明 AST 结构完整）。
 */
class SqlPrinterTest {

    /** 解析单条语句并断言无错误 */
    private static ASTNode parseSingle(String sql) {
        Parser parser = new Parser(new Lexer(sql));
        List<ASTNode> stmts = parser.parseAll();
        assertTrue(parser.getErrors().isEmpty(), "不应有解析错误: " + parser.getErrors());
        assertEquals(1, stmts.size(), "应恰好解析出 1 条语句");
        return stmts.get(0);
    }

    @Test
    void selectWithWhereAndOrder() {
        ASTNode stmt = parseSingle("select * from t where age >= 18 order by age desc");
        assertEquals("SELECT * FROM t WHERE (age >= 18) ORDER BY age DESC", SqlPrinter.print(stmt));
    }

    @Test
    void joinGroupByOrderBy() {
        ASTNode stmt = parseSingle("SELECT emp.id FROM emp JOIN dept ON emp.dept_id = dept.id "
                + "WHERE emp.age > 18 GROUP BY emp.id ORDER BY emp.id DESC");
        assertEquals("SELECT emp.id FROM emp JOIN dept ON (emp.dept_id = dept.id) "
                + "WHERE (emp.age > 18) GROUP BY emp.id ORDER BY emp.id DESC", SqlPrinter.print(stmt));
    }

    @Test
    void constantFoldingVisibleInOutput() {
        // 1 + 2 在 AST 构建期折叠为 3，还原输出应直接是 3
        ASTNode stmt = parseSingle("SELECT * FROM t WHERE age = 1 + 2");
        assertEquals("SELECT * FROM t WHERE (age = 3)", SqlPrinter.print(stmt));
    }

    @Test
    void logicalSimplificationVisibleInOutput() {
        // cond AND true 化简为 cond
        ASTNode stmt = parseSingle("SELECT * FROM t WHERE age > 18 AND true");
        assertEquals("SELECT * FROM t WHERE (age > 18)", SqlPrinter.print(stmt));
    }

    @Test
    void stringLiteralEscapedBack() {
        // 'a''b' 还原后仍为 'a''b'（单引号转义往返一致）
        ASTNode stmt = parseSingle("SELECT * FROM t WHERE name = 'a''b'");
        assertEquals("SELECT * FROM t WHERE (name = 'a''b')", SqlPrinter.print(stmt));
    }

    @Test
    void notUnaryExpr() {
        ASTNode stmt = parseSingle("SELECT * FROM t WHERE NOT age = 18");
        assertEquals("SELECT * FROM t WHERE NOT (age = 18)", SqlPrinter.print(stmt));
    }

    @Test
    void insertUpdateDeleteCreateShowDrop() {
        assertEquals("INSERT INTO t (id, name) VALUES (1, 'a')",
                SqlPrinter.print(parseSingle("insert into t (id, name) values (1, 'a')")));
        assertEquals("UPDATE t SET name = 'x', age = 20 WHERE (id = 1)",
                SqlPrinter.print(parseSingle("update t set name = 'x', age = 20 where id = 1")));
        assertEquals("DELETE FROM t WHERE (id > 5)",
                SqlPrinter.print(parseSingle("delete from t where id > 5")));
        assertEquals("CREATE TABLE t (id INT, name VARCHAR)",
                SqlPrinter.print(parseSingle("create table t (id int, name varchar)")));
        assertEquals("SHOW TABLES", SqlPrinter.print(parseSingle("show tables")));
        assertEquals("SHOW TABLE stu", SqlPrinter.print(parseSingle("describe stu")));
        assertEquals("DROP TABLE stu", SqlPrinter.print(parseSingle("drop table stu")));
    }

    /**
     * round-trip 稳定性：对同一 AST 反复 print 结果不变，
     * 且 print 结果可再次 parse 成功并得到相同还原文本。
     */
    @Test
    void roundTripIsStable() {
        String[] samples = {
                "SELECT * FROM t WHERE age = 1 + 2 AND name = 'a''b' ORDER BY age DESC;",
                "INSERT INTO t (id, name) VALUES (1, 'a');",
                "UPDATE t SET name = 'x' WHERE id = 1;",
                "DELETE FROM t WHERE id > 5;",
                "CREATE TABLE t (id INT, name VARCHAR);",
                "SELECT emp.id FROM emp JOIN dept ON emp.dept_id = dept.id GROUP BY emp.id;",
                "SHOW TABLES;",
                "SHOW TABLE stu;",
                "DROP TABLE stu;",
        };
        for (String sql : samples) {
            String first = SqlPrinter.print(parseSingle(sql));
            String second = SqlPrinter.print(parseSingle(first));
            assertEquals(first, second, "round-trip 还原不稳定: " + sql);
        }
    }
}
