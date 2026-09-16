package com.sqxdl.executor;

import com.sqxdl.executor.storage.StorageResult;

import java.util.ArrayList;
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
 * <p>LOCAL 用例之后追加流式协议集成测试段（AUTO 模式，需 storage_core.exe），
 * 验证 C 组分帧流式 resultset（header + rows* + end）在客户端的组装正确性：
 * 大结果集跨帧、流式 filter/project、流式与 rowcount 交替后的帧序对齐、
 * executeBatch 多条流式响应按序组装、join 物化单行响应与流式响应同会话混用。
 * 核心不可用时该段整段跳过（SKIP），不影响 LOCAL 用例的验收结论。
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
        int streamFailed = runStreamingTests();
        if (failed > 0 || streamFailed > 0) {
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

    /**
     * 流式协议集成测试（AUTO 模式）：走真实 storage_core.exe，验证 C 组分帧流式
     * resultset（header + rows* + end，每 256 行一帧）在客户端的组装正确性。
     * 覆盖：600 行大结果集跨帧（256+256+88）与行序、流式 filter/project、
     * 流式与 rowcount 交替后的帧序对齐、executeBatch 连续流式响应按序组装、
     * join（整体物化单行响应）与流式响应同会话混用。
     * 存储核心不可用时整段跳过，返回 0（不计失败）。
     *
     * @return 流式段失败用例数
     */
    private static int runStreamingTests() {
        System.out.println();
        System.out.println("---- 流式协议集成测试（AUTO 模式，真实存储核心）----");
        SqlEngine engine = new SqlEngine(SqlEngine.Mode.AUTO);
        int passed = 0;
        int failed = 0;
        try {
            if (!engine.isStorageAvailable()) {
                System.out.println("  [SKIP] 存储核心不可用，流式用例全部跳过");
                return 0;
            }
            // S1 建表 + 600 行批量插入：同时覆盖 executeBatch 流水线协议
            List<String> setup = new ArrayList<>();
            setup.add("CREATE TABLE stream_t (id INT, name VARCHAR, score DOUBLE);");
            for (int i = 1; i <= 600; i++) {
                setup.add("INSERT INTO stream_t VALUES (" + i + ", 'n" + i + "', " + (i / 10.0) + ");");
            }
            List<StorageResult> setupResults = engine.executeBatch(setup);
            boolean s1 = setupResults.size() == 601 && setupResults.stream()
                    .allMatch(r -> r.getType() == StorageResult.Type.ROWCOUNT);
            if (report("S1 建表+600行批量插入(流水线)", s1,
                    "期望 601 个 rowcount，实际 " + setupResults.size() + " 个")) {
                passed++;
            } else {
                failed++;
            }

            // S2 全表查询跨帧组装：600 行分 3 帧（256+256+88），行序保持插入序
            StorageResult all = engine.execute("SELECT * FROM stream_t;");
            boolean s2 = all.getType() == StorageResult.Type.RESULTSET
                    && all.getRows().size() == 600
                    && all.getColumns().size() == 3
                    && num(all, 0, 0) == 1 && num(all, 255, 0) == 256 && num(all, 599, 0) == 600;
            if (report("S2 全表查询(3帧组装+行序)", s2, describeResult(all))) {
                passed++;
            } else {
                failed++;
            }

            // S3 流式 filter：score > 59.5 命中 id 596..600 共 5 行
            StorageResult filtered = engine.execute("SELECT * FROM stream_t WHERE score > 59.5;");
            boolean s3 = filtered.getType() == StorageResult.Type.RESULTSET
                    && filtered.getRows().size() == 5
                    && num(filtered, 0, 0) == 596 && num(filtered, 4, 0) == 600;
            if (report("S3 流式filter", s3, describeResult(filtered))) {
                passed++;
            } else {
                failed++;
            }

            // S4 流式 project 列裁剪：3 行 × 2 列
            StorageResult projected = engine.execute("SELECT id, name FROM stream_t WHERE id <= 3;");
            boolean s4 = projected.getType() == StorageResult.Type.RESULTSET
                    && projected.getRows().size() == 3
                    && projected.getColumns().size() == 2;
            if (report("S4 流式project列裁剪", s4, describeResult(projected))) {
                passed++;
            } else {
                failed++;
            }

            // S5 流式与 rowcount 交替执行：多帧响应收完后与下一条响应不串位
            StorageResult before = engine.execute("SELECT * FROM stream_t;");
            StorageResult inserted = engine.execute("INSERT INTO stream_t VALUES (601, 'n601', 60.1);");
            StorageResult after = engine.execute("SELECT * FROM stream_t WHERE id = 601;");
            StorageResult counted = engine.execute("SELECT COUNT(*) FROM stream_t;");
            boolean s5 = before.getRows().size() == 600
                    && inserted.getType() == StorageResult.Type.ROWCOUNT
                    && after.getRows().size() == 1
                    && counted.getRows().size() == 1
                    && num(counted, 0, 0) == 601;
            if (report("S5 流式/rowcount交替帧序对齐", s5,
                    "600行→INSERT影响" + inserted.getRowsAffected()
                            + "→" + after.getRows().size() + "行→COUNT=" + num(counted, 0, 0))) {
                passed++;
            } else {
                failed++;
            }

            // S6 executeBatch 连续 3 条流式 SELECT：多帧响应按发送序读回对齐
            // （S5 已插入 id=601，全表 601 行，score>59.5 命中 596..601 共 6 行）
            List<StorageResult> batch = engine.executeBatch(List.of(
                    "SELECT * FROM stream_t;",
                    "SELECT * FROM stream_t WHERE score > 59.5;",
                    "SELECT * FROM stream_t WHERE id = 601;"));
            boolean s6 = batch.size() == 3
                    && batch.get(0).getRows().size() == 601
                    && batch.get(1).getRows().size() == 6
                    && batch.get(2).getRows().size() == 1;
            if (report("S6 批量流水线流式对齐", s6,
                    "期望 601/6/1 行，实际 " + batch.stream()
                            .mapToInt(r -> r.getRows().size()).summaryStatistics())) {
                passed++;
            } else {
                failed++;
            }

            // S7 join 物化单行响应与流式响应同会话混用（stream_t.id 与 stream_u.tid 相等匹配 2 行）
            List<StorageResult> joinSetup = engine.executeBatch(List.of(
                    "CREATE TABLE stream_u (id INT, tid INT);",
                    "INSERT INTO stream_u VALUES (1, 1);",
                    "INSERT INTO stream_u VALUES (2, 2);"));
            StorageResult joined = engine.execute(
                    "SELECT * FROM stream_t JOIN stream_u ON stream_t.id = stream_u.tid;");
            boolean s7 = joinSetup.stream()
                    .allMatch(r -> r.getType() == StorageResult.Type.ROWCOUNT)
                    && joined.getType() == StorageResult.Type.RESULTSET
                    && joined.getRows().size() == 2
                    && joined.getColumns().size() == 5;
            if (report("S7 join物化与流式混用", s7, describeResult(joined))) {
                passed++;
            } else {
                failed++;
            }

            // 清理测试表（清理失败不影响验收结论）
            engine.executeBatch(List.of("DROP TABLE stream_t;", "DROP TABLE stream_u;"));
        } catch (RuntimeException e) {
            // 用例级兜底：断言越界等异常不允许进程崩溃，计为该段失败
            failed++;
            System.out.println("  [FAIL] 流式测试异常中断: " + e);
        } finally {
            engine.close();
        }
        System.out.printf("流式段: 通过 %d | 失败 %d | 总计 %d%n", passed, failed, passed + failed);
        return failed;
    }

    /** 打印流式用例结果并返回是否通过 */
    private static boolean report(String name, boolean ok, String detail) {
        System.out.printf("  [%s] %s | %s%n", ok ? "PASS" : "FAIL", name, detail);
        return ok;
    }

    /** 安全读取 result.rows[row][col] 的数值；越界或非数值返回 -1 */
    private static int num(StorageResult result, int row, int col) {
        if (row >= result.getRows().size() || result.getRows().get(row).size() <= col) {
            return -1;
        }
        Object value = result.getRows().get(row).get(col);
        return value instanceof Number n ? n.intValue() : -1;
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
