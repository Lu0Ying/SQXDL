package com.sqxdl.executor;

import com.sqxdl.executor.storage.StorageResult;

import java.util.Locale;

/**
 * 大数据量端到端性能基准（指导书：验证"大量数据的插入与查询"）。
 * <p>测量口径为端到端耗时：SQL 文本 -> 词法/语法/语义 -> 计划生成 -> JSON 序列化
 * -> 存储核心进程往返 -> 结果解析，即用户真实感知的执行时间。
 * <p>运行方式：{@code java com.sqxdl.executor.PerfTest}（AUTO 模式，需 storage_core.exe；
 * 核心不可用时自动回退内置模拟层，此时数据仅存内存，结果仍可参考）。
 * 测试结束后自动删表清理，存储核心在 {@link SqlEngine#close()} 时统一落盘。
 */
public class PerfTest {

    /** 基准表行数：每行约 720 字节，1000 行跨约 180 个 4KB 页，远超缓冲池容量。 */
    private static final int ROWS = 1000;

    private static final String TABLE = "perf_log";
    /** 200 字符固定 tag，让每行足够宽、页数足够多 */
    private static final String TAG = "TAGX".repeat(50);

    /** 汇总行：操作 / 数据量 / 平均耗时 / 吞吐 */
    private record BenchRow(String name, String scale, long millis, double opsPerSec) {
        void print() {
            System.out.printf(Locale.ROOT, "%-28s %10s %10d ms %12s%n",
                    name, scale, millis, opsPerSec > 0 ? String.format(Locale.ROOT, "%.0f 次/秒", opsPerSec) : "-");
        }
    }

    public static void main(String[] args) {
        SqlEngine engine = new SqlEngine(SqlEngine.Mode.AUTO);
        try {
            setup(engine);
            benchInsert(engine);
            benchLookup(engine);
        } catch (IllegalStateException e) {
            System.out.println("⚠基准中断: " + e.getMessage());
        } finally {
            long t0 = System.nanoTime();
            cleanup(engine);
            engine.close(); // 存储核心统一落盘（含 1000 行脏页）
            System.out.printf(Locale.ROOT, "%n落盘退出耗时: %d ms%n",
                    (System.nanoTime() - t0) / 1_000_000);
        }
    }

    /** 清理旧表并建表；旧表不存在时忽略 DROP 报错。 */
    private static void setup(SqlEngine engine) {
        engine.execute("DROP TABLE " + TABLE + ";"); // 首次运行报"表不存在"，忽略
        StorageResult create = engine.execute("CREATE TABLE " + TABLE
                + " (id INT, tag VARCHAR, payload VARCHAR);");
        requireOk(create, "建表");
    }

    /** 大输入：批量 INSERT 1000 行，统计总耗时与每行吞吐。 */
    private static void benchInsert(SqlEngine engine) {
        System.out.println("== 大输入 ==");
        long t0 = System.nanoTime();
        for (int i = 1; i <= ROWS; i++) {
            StorageResult r = engine.execute(insertOf(i));
            if (!r.getType().name().equals("ROWCOUNT")) {
                System.out.println("⚠第 " + i + " 行插入失败: " + r.getErrorMessage());
                return;
            }
        }
        long ms = elapsedMs(t0);
        report(new BenchRow("批量 INSERT", ROWS + " 行", ms, ROWS * 1000.0 / Math.max(ms, 1)));
        // 热身后续查找基准的页缓存（不含首次 IO）
        engine.execute("SELECT COUNT(*) FROM " + TABLE + ";");
    }

    /** 大查找：全表扫描、点查、未命中、范围、聚合、排序，各多次取平均。 */
    private static void benchLookup(SqlEngine engine) {
        System.out.println();
        System.out.println("== 大查找 ==");
        bench(engine, "COUNT(*) 全表扫描", ROWS + " 行", 5, () ->
                engine.execute("SELECT COUNT(*) FROM " + TABLE + ";"));

        bench(engine, "等值点查 id=" + (ROWS / 2), "1 行", 10, () ->
                engine.execute("SELECT id, payload FROM " + TABLE + " WHERE id = " + (ROWS / 2) + ";"));

        bench(engine, "未命中点查(最坏全扫)", "0 行", 10, () ->
                engine.execute("SELECT id FROM " + TABLE + " WHERE id = 999999;"));

        bench(engine, "范围查 id <= 100", "100 行", 5, () ->
                engine.execute("SELECT id FROM " + TABLE + " WHERE id <= 100;"));

        // 注：SUM/AVG 聚合为 A 组 Parser 既定不支持项（仅支持 COUNT），不纳入基准

        bench(engine, "GROUP BY tag 计数", "1 组", 3, () ->
                engine.execute("SELECT tag, COUNT(*) FROM " + TABLE + " GROUP BY tag;"));

        bench(engine, "ORDER BY id 全表排序", ROWS + " 行", 3, () ->
                engine.execute("SELECT id FROM " + TABLE + " ORDER BY id DESC;"));

        // 正确性抽查：COUNT 必须等于插入行数
        StorageResult check = engine.execute("SELECT COUNT(*) FROM " + TABLE + ";");
        Object countValue = check.getRows().isEmpty() ? null : check.getRows().get(0).get(0);
        System.out.println();
        System.out.println("正确性抽查: COUNT(*) = " + countValue
                + (String.valueOf(ROWS).equals(String.valueOf(countValue)) ? "（通过）" : "（⚠不等于 " + ROWS + "）"));
    }

    /** 通用基准：执行 iterations 次（首冷不剔除，本进程已有热身），返回平均耗时行。 */
    private static BenchRow bench(SqlEngine engine, String name, String scale,
                                  int iterations, java.util.function.Supplier<StorageResult> action) {
        long t0 = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            StorageResult r = action.get();
            if (r.getType() == StorageResult.Type.ERROR) {
                System.out.println("⚠" + name + " 执行失败: " + r.getErrorMessage());
                return new BenchRow(name + " (失败)", scale, -1, 0);
            }
        }
        long ms = elapsedMs(t0);
        BenchRow row = new BenchRow(name, scale, ms / iterations, iterations * 1000.0 / Math.max(ms, 1));
        report(row);
        return row;
    }

    private static String insertOf(int i) {
        String payload = String.format("PAYLOAD_%05d_", i) + "x".repeat(488);
        return "INSERT INTO " + TABLE + " VALUES (" + i + ", '" + TAG + "', '" + payload + "');";
    }

    private static void cleanup(SqlEngine engine) {
        StorageResult r = engine.execute("DROP TABLE " + TABLE + ";");
        if (r.getType() == StorageResult.Type.ROWCOUNT) {
            System.out.println("已清理基准表 " + TABLE);
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static void report(BenchRow row) {
        row.print();
    }

    private static void requireOk(StorageResult result, String step) {
        if (result.getType() == StorageResult.Type.ERROR) {
            throw new IllegalStateException(step + "失败: " + result.getErrorMessage());
        }
    }
}
