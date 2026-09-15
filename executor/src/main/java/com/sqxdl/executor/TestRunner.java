package com.sqxdl.executor;

import com.sqxdl.executor.storage.StorageResult;

import java.util.List;

/**
 * 集成测试运行器（D 组）：不依赖 JUnit，直接 main 运行。
 * 通过 {@link SqlEngine}（LOCAL 模式：内置示例数据，无需存储核心）逐条执行
 * 测试 SQL，比对实际结果与预期行为，打印 用例名 → SQL → 实际结果 → PASS/FAIL，
 * 末尾输出统计；存在失败用例时退出码为 1，便于脚本化验收。
 *
 * <p>27 个用例按指导书错误测试场景分组：正常流程（建表/插入/查询/删除）、
 * 词法错误（非法字符、未闭合字符串、数字后跟字母）、语法错误（关键字拼错、
 * 截断、括号不匹配、缺失分号）、语义错误（表/列不存在、类型与列数不匹配）、
 * 边界（空输入、超长标识符、大小写混写）以及注释与分号组合的行为。
 *
 * <p>用例按顺序执行且共享同一引擎实例（前面的建表/插入供后面查询/删除使用）；
 * 预期错误码与 SqlEngine 的错误分类对应：词法/语法错误与缺失分号 → SYNTAX_ERROR，
 * 语义校验失败 → SEMANTIC_ERROR，空输入 → EMPTY_SQL。
 *
 * <p>运行方式：java com.sqxdl.executor.TestRunner（退出码 0 = 全部通过）
 */
public class TestRunner {

    /** 预期行为：errorCode 为 null 表示预期执行成功，否则为预期错误码 */
    private record Expected(String errorCode) {

        static Expected success() {
            return new Expected(null);
        }

        static Expected error(String code) {
            return new Expected(code);
        }

        /** 判定实际结果是否符合预期 */
        boolean matches(StorageResult result) {
            if (result.getType() == StorageResult.Type.ERROR) {
                return errorCode != null && result.getErrorCode().equals(errorCode);
            }
            return errorCode == null;
        }

        @Override
        public String toString() {
            return errorCode == null ? "成功" : "报错 " + errorCode;
        }
    }

    /** 测试用例：名称 + SQL + 预期行为 */
    private record Case(String name, String sql, Expected expected) {
    }

    public static void main(String[] args) {
        List<Case> cases = buildCases();
        SqlEngine engine = new SqlEngine(SqlEngine.Mode.LOCAL);
        int passed = 0;
        int failed = 0;
        try {
            for (int i = 0; i < cases.size(); i++) {
                Case tc = cases.get(i);
                StorageResult result = engine.execute(tc.sql());
                boolean ok = tc.expected().matches(result);
                if (ok) {
                    passed++;
                } else {
                    failed++;
                }
                System.out.printf("[%02d] %s%n     SQL: %s%n     预期: %s | 实际: %s | %s%n",
                        i + 1, tc.name(), shortSql(tc.sql()),
                        tc.expected(), describeResult(result), ok ? "PASS" : "FAIL");
            }
        } finally {
            engine.close();
        }
        System.out.println("========================================");
        System.out.printf("总计 %d | 通过 %d | 失败 %d%n", cases.size(), passed, failed);
        System.out.println(failed == 0 ? "结果: 全部通过" : "结果: 存在失败用例");
        if (failed > 0) {
            System.exit(1);
        }
    }

    /** 测试用例清单（按类别分组，顺序执行）；分号必填约定下每条 SQL 均以 ; 结尾 */
    private static List<Case> buildCases() {
        // 超长标识符：200 个字母，词法合法（一个 IDENTIFIER），语义层报表不存在
        String longName = "t" + "a".repeat(199);
        return List.of(
                // ===== 正常流程 =====
                new Case("建表", "CREATE TABLE runner_t (id INT, name VARCHAR);", Expected.success()),
                new Case("插入行", "INSERT INTO runner_t VALUES (1, 'Alice');", Expected.success()),
                new Case("再插入一行", "INSERT INTO runner_t VALUES (2, 'Bob');", Expected.success()),
                new Case("全表查询", "SELECT * FROM runner_t;", Expected.success()),
                new Case("条件查询", "SELECT * FROM runner_t WHERE id = 1;", Expected.success()),
                new Case("条件删除", "DELETE FROM runner_t WHERE id = 2;", Expected.success()),
                // ===== 词法错误（Lexer 抛 SqxdlException → SYNTAX_ERROR） =====
                new Case("非法字符 @", "SELECT * FROM student WHERE id = @;", Expected.error("SYNTAX_ERROR")),
                new Case("非法字符 #", "SELECT # FROM student;", Expected.error("SYNTAX_ERROR")),
                new Case("非法字符 $", "INSERT INTO student VALUES (1, $name);", Expected.error("SYNTAX_ERROR")),
                new Case("未闭合字符串", "SELECT * FROM student WHERE name = 'unclosed;", Expected.error("SYNTAX_ERROR")),
                new Case("数字后跟字母", "SELECT 123abc FROM student;", Expected.error("SYNTAX_ERROR")),
                // ===== 语法错误（Parser 抛 SqxdlException → SYNTAX_ERROR） =====
                new Case("关键字拼错 FORM(距离2不纠正)", "SELECT * FORM student;", Expected.error("SYNTAX_ERROR")),
                new Case("WHERE 子句截断", "SELECT * FROM student WHERE;", Expected.error("SYNTAX_ERROR")),
                new Case("括号不匹配", "CREATE TABLE broken_t (id INT;", Expected.error("SYNTAX_ERROR")),
                new Case("缺少分号", "SELECT * FROM student", Expected.error("SYNTAX_ERROR")),
                new Case("分号后跟行注释", "SELECT * FROM runner_t; -- 查询全部", Expected.success()),
                new Case("注释里写分号不算", "SELECT * FROM runner_t -- 分号忘在注释里;", Expected.error("SYNTAX_ERROR")),
                new Case("块注释夹在语句中", "SELECT * FROM /* 全表查询 */ runner_t;", Expected.success()),
                // ===== 语义错误（SemanticAnalyzer → SEMANTIC_ERROR） =====
                new Case("表不存在", "SELECT * FROM nosuch_table;", Expected.error("SEMANTIC_ERROR")),
                new Case("列不存在", "SELECT nosuch_col FROM student;", Expected.error("SEMANTIC_ERROR")),
                new Case("类型不匹配(字符串进 INT 列)", "INSERT INTO student VALUES ('oops', 'A', 20, 'A');", Expected.error("SEMANTIC_ERROR")),
                new Case("列数不匹配(4列给2值)", "INSERT INTO student VALUES (9, 'Zed');", Expected.error("SEMANTIC_ERROR")),
                // ===== 边界 =====
                new Case("空输入", "", Expected.error("EMPTY_SQL")),
                new Case("纯空白输入", "   ", Expected.error("EMPTY_SQL")),
                new Case("仅一个分号", ";", Expected.error("EMPTY_SQL")),
                new Case("超长标识符(词法合法,表不存在)", "SELECT * FROM " + longName + ";", Expected.error("SEMANTIC_ERROR")),
                new Case("大小写混写(关键字不敏感)", "SeLeCt * FrOm student WhERE id = 1;", Expected.success()));
    }

    /** 结果的中文描述：成功带行数/影响行数，错误带错误码与消息 */
    private static String describeResult(StorageResult result) {
        return switch (result.getType()) {
            case RESULTSET -> "成功，返回 " + result.getRows().size() + " 行";
            case ROWCOUNT -> "成功，受影响 " + result.getRowsAffected() + " 行";
            case ERROR -> "报错[" + result.getErrorCode() + "] " + shortText(result.getErrorMessage());
        };
    }

    /** SQL 展示：压平换行并截断到 60 字符 */
    private static String shortSql(String sql) {
        return shortText(sql.replace('\n', ' ').trim());
    }

    /** 文本截断到 60 字符，超出部分以 ... 结尾 */
    private static String shortText(String text) {
        return text.length() <= 60 ? text : text.substring(0, 57) + "...";
    }
}
