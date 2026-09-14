package com.sqxdl.parser;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 差分验证（Differential Testing）：用 H2 内存数据库充当"裁判"。
 * <p>
 * 流程：parser 解析 SQL 脚本（断言零错误）→ SqlPrinter 还原成规范 SQL →
 * H2 逐条执行（断言执行成功）→ 查询最终数据核对。
 * 从而用工业级 SQL 引擎交叉验证我们手写解析器 + 还原器的正确性。
 */
class DiffTest {

    /** 打开一个新的 H2 内存库（MySQL 兼容模式） */
    private static Connection newH2(String name) throws Exception {
        return DriverManager.getConnection("jdbc:h2:mem:" + name + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
    }

    /** 解析脚本，断言零错误，返回语句列表 */
    private static List<ASTNode> parseScript(String script) {
        Parser parser = new Parser(new Lexer(script));
        List<ASTNode> stmts = parser.parseAll();
        assertTrue(parser.getErrors().isEmpty(), "不应有解析错误: " + parser.getErrors());
        return stmts;
    }

    /** 把脚本里的每条语句在 H2 中顺序执行（重建 → 插入 → 查询/更新/删除） */
    private static void runInH2(Connection conn, List<ASTNode> stmts) throws Exception {
        for (ASTNode stmt : stmts) {
            String sql = SqlPrinter.print(stmt);
            try (Statement s = conn.createStatement()) {
                s.execute(sql);
            }
        }
    }

    @Test
    void fullCrudScriptRunsInH2() throws Exception {
        String script = """
                CREATE TABLE t1 (id INT, name VARCHAR);
                INSERT INTO t1 (id, name) VALUES (1, 'a');
                INSERT INTO t1 (id, name) VALUES (2, 'b');
                SELECT id FROM t1 WHERE id > 1;
                SELECT * FROM t1;
                SELECT name FROM t1 WHERE id = 1 AND name = 'a';
                UPDATE t1 SET name = 'x' WHERE id = 2;
                DELETE FROM t1 WHERE id = 1;
                SELECT * FROM t1;
                """;
        try (Connection conn = newH2("crud")) {
            runInH2(conn, parseScript(script));
            // 最终核对：UPDATE + DELETE 后应只剩一行 (2, 'x')
            try (Statement s = conn.createStatement();
                 ResultSet rs = s.executeQuery("SELECT id, name FROM t1")) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1));
                assertEquals("x", rs.getString(2));
                assertFalse(rs.next(), "应只剩一行");
            }
        }
    }

    @Test
    void joinGroupByOrderByRunsInH2() throws Exception {
        String script = """
                CREATE TABLE dept (id INT, name VARCHAR);
                CREATE TABLE emp (id INT, dept_id INT, age INT);
                INSERT INTO dept (id, name) VALUES (1, 'd1');
                INSERT INTO emp (id, dept_id, age) VALUES (1, 1, 20);
                INSERT INTO emp (id, dept_id, age) VALUES (2, 1, 30);
                INSERT INTO emp (id, dept_id, age) VALUES (3, 1, 10);
                SELECT emp.id FROM emp JOIN dept ON emp.dept_id = dept.id
                    WHERE emp.age > 18 GROUP BY emp.id ORDER BY emp.id DESC;
                """;
        try (Connection conn = newH2("join")) {
            runInH2(conn, parseScript(script));
            // JOIN + WHERE + GROUP BY + ORDER BY 的最终结果：id 降序 = [2, 1]
            try (Statement s = conn.createStatement();
                 ResultSet rs = s.executeQuery("SELECT emp.id FROM emp JOIN dept ON emp.dept_id = dept.id "
                         + "WHERE emp.age > 18 GROUP BY emp.id ORDER BY emp.id DESC")) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1));
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
                assertFalse(rs.next(), "应只剩两行");
            }
        }
    }

    @Test
    void constantFoldingSQLRunsInH2() throws Exception {
        // 常量折叠后的还原文本（WHERE id = 2）交给 H2 执行，语义与手写 1+1 等价
        ASTNode stmt = parseScript("SELECT * FROM t WHERE id = 1 + 1;").get(0);
        assertEquals("SELECT * FROM t WHERE (id = 2)", SqlPrinter.print(stmt));

        String script = """
                CREATE TABLE t (id INT);
                INSERT INTO t (id) VALUES (1);
                INSERT INTO t (id) VALUES (2);
                """ + SqlPrinter.print(stmt) + ";";
        try (Connection conn = newH2("fold")) {
            runInH2(conn, parseScript(script));
            try (Statement s = conn.createStatement();
                 ResultSet rs = s.executeQuery("SELECT id FROM t")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1));
            }
        }
    }

    @Test
    void quotedStringRunsInH2() throws Exception {
        // 含转义单引号的字符串还原后 H2 可正常插入
        String script = """
                CREATE TABLE t (id INT, name VARCHAR);
                INSERT INTO t (id, name) VALUES (1, 'it''s');
                SELECT id FROM t WHERE name = 'it''s';
                """;
        try (Connection conn = newH2("quoted")) {
            runInH2(conn, parseScript(script));
            try (Statement s = conn.createStatement();
                 ResultSet rs = s.executeQuery("SELECT name FROM t WHERE id = 1")) {
                assertTrue(rs.next());
                assertEquals("it's", rs.getString(1));
            }
        }
    }
}
