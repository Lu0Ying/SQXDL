package com.sqxdl.executor;

import com.sqxdl.executor.storage.PhysicalPlanJson;
import com.sqxdl.executor.storage.StorageClient;
import com.sqxdl.executor.storage.StorageResult;
import com.sqxdl.executor.storage.StorageTableMetadataProvider;
import com.sqxdl.parser.ASTNode;
import com.sqxdl.parser.Lexer;
import com.sqxdl.parser.Parser;
import com.sqxdl.parser.SqxdlException;
import com.sqxdl.semantic.CatalogImpl;
import com.sqxdl.semantic.PlanGenerator;
import com.sqxdl.semantic.PlanNode;
import com.sqxdl.semantic.SemanticAnalyzer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQL 执行引擎 —— D 组（executor 模块）核心，CLI（Main）与 GUI（SwingDemo）共用的执行门面。
 * <p>完整流水线及各组分工：
 * <pre>
 *   SQL 文本
 *     │ 规范化（剥注释 / 分号校验，D 组）
 *     ▼
 *   A 组 parser 模块：Lexer 分词 + Parser 建语法树（含拼写自动纠错）
 *     ▼
 *   B 组 semantic 模块：SemanticAnalyzer 语义校验（表/列存在性、类型、列数）
 *     ▼
 *   B 组 semantic 模块：PlanGenerator 生成并优化执行计划（PlanNode 树）
 *     ▼
 *   D 组执行：AUTO 走存储核心 / LOCAL 走内置模拟层，结果统一为 StorageResult
 * </pre>
 * 数据字典（表元数据）由 B 组 {@link CatalogImpl} 持有，建表/删表在此登记，
 * 供语义分析、计划生成与 GUI 表列表三方共享。
 * <p>两条执行路径：
 * <ul>
 *   <li>AUTO —— 计划经 {@link PhysicalPlanJson} 序列化为 JSON，由
 *       {@link StorageClient} 以行协议调用 C 组 storage_core.exe（数据落盘持久化）；
 *       核心没有 sort/group 算子，ORDER BY / GROUP BY / COUNT(*) 在 Java 端后处理；
 *       核心不可用时自动回退内置示例数据，保证 Demo 脱离 C++ 程序也能完整演示</li>
 *   <li>LOCAL —— 全部在 JVM 内模拟执行（simulate* 方法族），数据存内存不落盘，
 *       供 TestRunner 离线测试与无存储环境演示</li>
 * </ul>
 * 所有失败（词法/语法/语义/计划/存储）都转为带错误码的 ERROR 结果返回，不抛异常，
 * 保证 REPL/GUI 永不崩溃。非线程安全：仅在 REPL 单线程或 Swing EDT 中调用。
 */
public class SqlEngine implements AutoCloseable {

    /**
     * 执行模式：
     * AUTO —— 优先真实存储核心（storage_core.exe，服务式会话，数据落盘持久化），
     *         不可用时回退本地模拟；
     * LOCAL —— 始终本地模拟执行（数据保存在 JVM 内，仅供离线演示）。
     */
    public enum Mode { AUTO, LOCAL }

    /** 内存表结构：列信息（名+类型）+ 数据行（模拟模式的数据载体） */
    private static class TableData {
        final List<CatalogImpl.ColumnInfo> columns;
        final List<List<Object>> rows;

        TableData(List<CatalogImpl.ColumnInfo> columns, List<List<Object>> rows) {
            this.columns = columns;
            this.rows = rows;
        }

        /** 按列名取下标，不存在返回 -1 */
        int indexOf(String columnName) {
            for (int i = 0; i < columns.size(); i++) {
                if (columns.get(i).getName().equals(columnName)) {
                    return i;
                }
            }
            return -1;
        }

        /** 列名清单（供求值与投影使用） */
        List<String> columnNames() {
            List<String> names = new ArrayList<>();
            for (CatalogImpl.ColumnInfo info : columns) {
                names.add(info.getName());
            }
            return names;
        }
    }

    /** 存储核心不可用的错误码：命中即回退模拟执行 */
    private static final List<String> STORAGE_FAILURE_CODES =
            List.of("STORAGE_UNAVAILABLE", "STORAGE_TIMEOUT");

    /** 数据字典：表元数据，与语义分析/计划生成共享（复用 B 组真实代码） */
    private final CatalogImpl catalog = new CatalogImpl();

    /** 存储核心客户端（复用 D 组真实代码） */
    private final StorageClient storageClient = new StorageClient();

    /** 执行模式 */
    private final Mode mode;

    /** 内置示例数据：表名 -> 表数据（模拟模式使用） */
    private final Map<String, TableData> tables = new LinkedHashMap<>();

    /** 最近一次执行是否回退到模拟模式；null 表示走了真实存储 */
    private String fallbackReason;

    /** 最近一次执行的词法拼写纠错提示（A 组 Lexer 自动纠正时收集） */
    private List<String> spellWarnings = List.of();

    /** 存储核心是否可用（AUTO 模式下构造阶段探测） */
    private boolean storageAvailable;

    /** 默认 AUTO 模式：优先真实存储核心 */
    public SqlEngine() {
        this(Mode.AUTO);
    }

    public SqlEngine(Mode mode) {
        this.mode = mode;
        if (mode == Mode.LOCAL) {
            initLocalDemo();
            return;
        }
        // AUTO：从存储核心同步真实表结构（数据落盘持久化，重启不还原）
        if (syncCatalogFromStorage()) {
            storageAvailable = true;
            return;
        }
        // 存储核心不可用时回退内置示例数据，保证界面/CLI 仍可演示
        initLocalDemo();
    }

    /** 可供浏览的表名列表（按字典序，供 GUI 左侧列表使用） */
    public List<String> tableNames() {
        List<String> names = new ArrayList<>(tables.keySet());
        names.sort(String::compareTo);
        return names;
    }

    /** 最近一次执行的回退说明；null 表示真实执行 */
    public String getFallbackReason() {
        return fallbackReason;
    }

    /** 最近一次执行的词法拼写纠错提示（无纠错时为空列表） */
    public List<String> getSpellWarnings() {
        return spellWarnings;
    }

    /** 存储核心是否可用（AUTO 模式下为真） */
    public boolean isStorageAvailable() {
        return storageAvailable;
    }

    /** 结束存储服务会话（协议 exit 正常落盘退出）；断开连接或窗口关闭时调用 */
    @Override
    public void close() {
        storageClient.close();
    }

    /** AUTO 启动时从存储核心同步表结构；空库时预置示例数据。返回核心是否可用 */
    private boolean syncCatalogFromStorage() {
        StorageTableMetadataProvider provider = new StorageTableMetadataProvider(storageClient);
        List<String> storageTables = provider.getTableNames();
        // showTables 失败（核心不可用）与空库同样返回空表，需二次探测区分
        if (storageTables.isEmpty()
                && storageClient.call("{\"op\":\"showTables\"}").getType()
                        != StorageResult.Type.RESULTSET) {
            return false;
        }
        for (String tableName : storageTables) {
            List<CatalogImpl.ColumnInfo> columns = provider.getTableColumns(tableName);
            if (columns.isEmpty()) {
                continue;
            }
            try {
                catalog.createTableWithTypes(tableName, columns);
                tables.put(tableName, new TableData(columns, new ArrayList<>()));
            } catch (RuntimeException ignore) {
                // 重复登记等异常跳过，不影响其余表
            }
        }
        // 空库时预置示例数据：走完整流水线真实落库，重启后依然存在
        if (tables.isEmpty()) {
            bootstrapSampleData();
        }
        return true;
    }

    /** 空库预置示例数据：逐条走完整流水线（CREATE + INSERT 真实落库）；分号必填约定下同样要带分号 */
    private void bootstrapSampleData() {
        execute("CREATE TABLE student (id INT, name VARCHAR, age INT, grade VARCHAR);");
        execute("CREATE TABLE course (cid INT, title VARCHAR, credit INT);");
        execute("CREATE TABLE teacher (tid INT, name VARCHAR, dept VARCHAR);");
        String[] inserts = {
                "INSERT INTO student VALUES (1, 'Alice', 20, 'A');",
                "INSERT INTO student VALUES (2, 'Bob', 22, 'B+');",
                "INSERT INTO student VALUES (3, 'Carol', 21, 'A-');",
                "INSERT INTO student VALUES (4, 'David', 23, 'B');",
                "INSERT INTO student VALUES (5, 'Eve', 19, 'A+');",
                "INSERT INTO course VALUES (101, 'Database', 4);",
                "INSERT INTO course VALUES (102, 'Operating Sys', 3);",
                "INSERT INTO course VALUES (103, 'Compiler', 4);",
                "INSERT INTO teacher VALUES (1, 'Yao Xin', 'Computer');",
                "INSERT INTO teacher VALUES (2, 'Gui Ning', 'Computer');",
                "INSERT INTO teacher VALUES (3, 'Deng Lei', 'Computer');"
        };
        for (String sql : inserts) {
            execute(sql);
        }
    }

    /**
     * 执行一条 SQL，任何失败都以 ERROR 结果返回，不抛异常。
     * 仅应在 Swing EDT（GUI）或 REPL 单线程（CLI）中调用，内部数据无并发保护。
     */
    public StorageResult execute(String sql) {
        PhaseTiming timing = new PhaseTiming();
        long total0 = System.nanoTime();
        timing.start = total0;
        Prepared prepared = prepare(sql, timing);
        if (prepared.error != null) {
            return finishTiming(timing, total0, prepared.error);
        }
        // LOCAL 模式直接本地模拟（数据保存在 JVM，演示可跨语句看到变化）
        if (mode == Mode.LOCAL) {
            long phase0 = System.nanoTime();
            StorageResult localResult = simulate(prepared.ast);
            timing.storage = System.nanoTime() - phase0;
            return finishTiming(timing, total0, localResult);
        }
        // AUTO 模式走真实存储核心，不可用时回退模拟
        StorageResult result;
        long phase0 = System.nanoTime();
        timing.storageUsed = true; // 本次确实调用了存储核心，耗时分解可展示存储侧细分
        try {
            result = executePlan(prepared.plan);
        } catch (RuntimeException e) {
            timing.storage = System.nanoTime() - phase0;
            return finishTiming(timing, total0, StorageResult.error("PLAN_ERROR", e.getMessage()));
        }
        timing.storage = System.nanoTime() - phase0;
        if (!isStorageFailure(result)) {
            return finishTiming(timing, total0, result);
        }
        fallbackReason = "存储核心不可用，已使用内置示例数据模拟执行";
        phase0 = System.nanoTime();
        StorageResult simulated = simulate(prepared.ast);
        timing.storage += System.nanoTime() - phase0;
        return finishTiming(timing, total0, simulated);
    }

    /**
     * 批量执行多条 SQL（流水线协议）：Java 侧逐条完成准备（前一条的建表
     * 登记对后续语句的语义检查可见），可执行计划按连续段批量写入存储核心、
     * 按序读回结果，返回列表与输入一一对应。任一条在 Java 侧失败即记录
     * ERROR 并继续后续条目（与脚本模式"报错继续"一致）；存储段失败时该段
     * 全部条目标记同一错误。LOCAL/回退模式退化为逐条 execute。
     */
    public List<StorageResult> executeBatch(List<String> sqls) {
        if (sqls.isEmpty()) {
            return List.of();
        }
        if (mode == Mode.LOCAL) {
            List<StorageResult> results = new ArrayList<>(sqls.size());
            for (String sql : sqls) {
                results.add(execute(sql));
            }
            return results;
        }
        long batch0 = System.nanoTime();
        List<StorageResult> results = new ArrayList<>(sqls.size());
        List<PlanNode> plans = new ArrayList<>(sqls.size());
        List<ASTNode> asts = new ArrayList<>(sqls.size());
        boolean[] batchable = new boolean[sqls.size()];
        long prepareSum = 0;
        for (int i = 0; i < sqls.size(); i++) {
            PhaseTiming timing = new PhaseTiming();
            timing.start = System.nanoTime();
            Prepared prepared = prepare(sqls.get(i), timing);
            prepareSum += timing.normalize + timing.parse + timing.semantic + timing.plan;
            results.add(prepared.error);
            plans.add(prepared.plan);
            asts.add(prepared.ast);
            // 含 Java 端算子（OrderBy/GroupBy）的计划无法直接序列化下发，走单条路径
            batchable[i] = prepared.error == null && !needsLocalExecution(prepared.plan);
        }
        // 连续可批量段流水线发送：Java 侧失败条目与本地执行条目夹在中间时分段，
        // 保证批量协议的输入输出 1:1 对齐
        int i = 0;
        while (i < sqls.size()) {
            if (!batchable[i]) {
                // plan 非 null 说明只是含 Java 端算子：走完整单条路径（含回退模拟）
                if (plans.get(i) != null) {
                    results.set(i, execute(sqls.get(i)));
                }
                i++; // plan 为 null 的条目已在结果列表中记 ERROR
                continue;
            }
            int end = i;
            while (end < sqls.size() && batchable[end]) {
                end++;
            }
            List<StorageResult> part = storageClient.executeAll(plans.subList(i, end));
            for (int k = i; k < end; k++) {
                StorageResult r = part.get(k - i);
                if (isStorageFailure(r)) {
                    // 批量段存储不可用：与单条路径一致地回退内置模拟层
                    fallbackReason = "存储核心不可用，已使用内置示例数据模拟执行";
                    results.set(k, simulate(asts.get(k)));
                } else {
                    results.set(k, r);
                }
            }
            i = end;
        }
        lastBatchNanos = new long[]{prepareSum, storageClient.lastSerializeNanos(),
                storageClient.lastSendNanos(), storageClient.lastWaitNanos(),
                System.nanoTime() - batch0};
        return results;
    }

    /**
     * 判断计划是否包含需在 Java 端执行的算子（OrderBy/GroupBy 聚合与排序）。
     * 这类节点没有对应的物理 JSON 表示，批量协议无法直接下发，需走单条
     * {@link #execute} 路径（由 executePlan 先递归执行 child 再本地处理）。
     */
    private static boolean needsLocalExecution(PlanNode plan) {
        if (plan instanceof PlanNode.OrderByPlan || plan instanceof PlanNode.GroupByPlan) {
            return true;
        }
        if (plan instanceof PlanNode.ProjectPlan project) {
            return needsLocalExecution(project.getChild());
        }
        if (plan instanceof PlanNode.FilterPlan filter) {
            return needsLocalExecution(filter.getChild());
        }
        return false;
    }

    /** 最近一次 executeBatch 的批量级耗时（纳秒）：Java 侧准备累计/序列化/发送/接收/合计 */
    private volatile long[] lastBatchNanos = new long[5];

    /**
     * 最近一次 {@link #executeBatch} 的批量级耗时采样，供基准测试展示。
     * 返回 5 元素数组：[Java 侧准备累计, 计划序列化, 发送(写线程), 接收全部结果, 合计]，单位纳秒。
     */
    public long[] lastBatchNanos() {
        return lastBatchNanos.clone();
    }

    /**
     * Java 侧流水线：规范化 → 词法语法解析 → 语义分析 → 建表登记 → 计划生成。
     * 全程不触碰存储核心；失败时以 {@code error} 结果返回（plan 为 null），
     * 成功时保留 AST 供 LOCAL 模拟层使用。
     */
    private Prepared prepare(String sql, PhaseTiming timing) {
        fallbackReason = null;
        // 每条语句先清空上一条的拼写提示，防止语法失败提前返回时旧提示跨语句残留
        spellWarnings = List.of();
        // 规范化：去首尾空白；分号必填（判定与去分号前先剥离注释，注释里出现分号不算数）
        String trimmed = sql.trim();
        if (trimmed.isEmpty()) {
            return new Prepared(null, null, StorageResult.error("EMPTY_SQL", "SQL 语句为空"));
        }
        String stripped = stripComments(trimmed);
        String normalized;
        if (stripped == null) {
            // 未闭合的字符串/块注释：跳过分号校验，交由 Lexer 报出带行列位置的精确错误
            normalized = trimmed;
        } else {
            // 剥离后需 trim：行注释前的空格会成为剥离文本的结尾（"...; -- 注释" → "...; "）
            stripped = stripped.trim();
            if (!stripped.endsWith(";")) {
                return new Prepared(null, null,
                        StorageResult.error("SYNTAX_ERROR", "语句必须以分号 ; 结尾"));
            }
            normalized = stripped.substring(0, stripped.length() - 1).trim();
            if (normalized.isEmpty()) {
                return new Prepared(null, null, StorageResult.error("EMPTY_SQL", "SQL 语句为空"));
            }
        }
        timing.normalize = System.nanoTime() - timing.start;

        // 阶段一：词法 + 语法解析（复用 A 组真实代码，含拼写自动纠错）
        ASTNode ast;
        long phase0 = System.nanoTime();
        Lexer lexer = new Lexer(normalized);
        try {
            if (SqlDebug.ENABLED) {
                SqlDebug.printTokens(normalized);
            }
            ast = new Parser(lexer).parse();
        } catch (SqxdlException e) {
            // 语法错误前 Lexer 可能已产出本条的拼写纠正提示（如 SELEC * student），一并带出
            spellWarnings = lexer.getSpellWarnings();
            return new Prepared(null, null, StorageResult.error("SYNTAX_ERROR", e.getMessage()));
        }
        timing.parse = System.nanoTime() - phase0;
        spellWarnings = lexer.getSpellWarnings();
        if (SqlDebug.ENABLED) {
            SqlDebug.printAst(ast);
        }

        // 阶段二：语义分析（复用 B 组真实校验，含列类型校验）
        phase0 = System.nanoTime();
        try {
            new SemanticAnalyzer(catalog).analyze(ast);
            if (SqlDebug.ENABLED) {
                SqlDebug.printSemantic();
            }
        } catch (RuntimeException e) {
            timing.semantic = System.nanoTime() - phase0;
            return new Prepared(null, null, StorageResult.error("SEMANTIC_ERROR", e.getMessage()));
        }
        timing.semantic = System.nanoTime() - phase0;

        // 建表元数据在此统一登记（AUTO/LOCAL 共用），保证后续语句的语义校验可见新表；
        // 数据写入由存储核心（AUTO）或内置模拟层（LOCAL/回退）负责
        if (ast instanceof ASTNode.CreateTableStmt createStmt) {
            List<CatalogImpl.ColumnInfo> infos = toColumnInfos(createStmt.getColumns());
            catalog.createTableWithTypes(createStmt.getTableName(), infos);
            tables.put(createStmt.getTableName(), new TableData(infos, new ArrayList<>()));
        }

        // 阶段三：计划生成（generate = build + optimize，任何异常都以 ERROR 返回）
        phase0 = System.nanoTime();
        PlanNode plan;
        try {
            PlanGenerator generator = new PlanGenerator(catalog);
            if (SqlDebug.ENABLED) {
                // DEBUG：分别展示优化前后的计划树，观察常量折叠与恒真过滤移除效果
                PlanNode raw = generator.build(ast);
                SqlDebug.printPlan(raw, "Plan 树（优化前）");
                plan = generator.optimize(raw);
                SqlDebug.printPlan(plan, "Plan 树（优化后）");
            } else {
                plan = generator.generate(ast);
            }
        } catch (RuntimeException e) {
            timing.plan = System.nanoTime() - phase0;
            return new Prepared(null, null, StorageResult.error("PLAN_ERROR", e.getMessage()));
        }
        timing.plan = System.nanoTime() - phase0;
        return new Prepared(ast, plan, null);
    }

    /** prepare 的产物：ast 供 LOCAL 模拟层使用，error 非 null 表示 Java 侧已失败 */
    private record Prepared(ASTNode ast, PlanNode plan, StorageResult error) {
    }

    /**
     * DEBUG 开启时打印本条语句的端到端耗时分解（性能检查点）；
     * 存储细分仅在本次确实调用了存储核心（AUTO 路径）时展示，避免错误路径
     * 误读上一次调用的残留采样。
     */
    private StorageResult finishTiming(PhaseTiming timing, long total0, StorageResult result) {
        // 无论开关与否都记录最近一次分段耗时，供 PerfTest 等外部基准累计（开销可忽略）
        long[] storagePhases = timing.storageUsed
                ? new long[]{storageClient.lastSerializeNanos(), storageClient.lastSendNanos(),
                        storageClient.lastWaitNanos(), storageClient.lastParseNanos()}
                : new long[]{-1L, -1L, -1L, -1L};
        lastTimingNanos = new long[]{timing.normalize, timing.parse, timing.semantic, timing.plan,
                storagePhases[0], storagePhases[1], storagePhases[2], storagePhases[3]};
        if (SqlDebug.ENABLED) {
            SqlDebug.printTiming(result.getType().name(), System.nanoTime() - total0,
                    timing.normalize, timing.parse, timing.semantic, timing.plan, timing.storage,
                    timing.storageUsed ? storageClient : null);
        }
        return result;
    }

    /** 最近一次 execute 的分段耗时（纳秒）：规范化/词法+语法/语义/计划/序列化/发送/核心等待/响应解析；未走存储路径时后四段为 -1 */
    private volatile long[] lastTimingNanos = new long[8];

    /**
     * 最近一次 {@link #execute} 的分段耗时采样，供基准测试累计平均。
     * 返回 8 元素数组：[规范化, 词法+语法, 语义, 计划生成, 序列化, 发送, 核心执行+回传, 响应解析]，
     * 单位纳秒；本次未走存储核心（LOCAL/回退/早期错误）时后四段为 -1。返回副本防外部修改。
     */
    public long[] lastTimingNanos() {
        return lastTimingNanos.clone();
    }

    /** 单条语句的分段耗时采样（纳秒）；各段仅在 SqlDebug.ENABLED 时对外展示 */
    private static final class PhaseTiming {
        long start;
        long normalize;
        long parse;
        long semantic;
        long plan;
        long storage;
        boolean storageUsed;
    }

    // ====================== 执行：真实存储 / 模拟回退 ======================

    /**
     * 剥离注释，仅用于分号判定与去分号：行注释（-- 起始至行尾）与块注释（斜杠星起始、可跨行）。
     * 与 Lexer 行为对齐：字符串字面量内的内容不视为注释；行注释丢弃但保留换行以维持行号；
     * 块注释替换为一个空格占位。检测到未闭合的字符串或块注释时返回 null，
     * 调用方跳过分号校验并保留原文，交由 Lexer 报出带行列位置的精确错误。
     */
    private static String stripComments(String sql) {
        StringBuilder sb = new StringBuilder(sql.length());
        boolean inString = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (inString) {
                sb.append(c);
                if (c == '\'') {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                        // '' 转义：仍处于字符串内
                        sb.append('\'');
                        i++;
                    } else {
                        inString = false;
                    }
                }
                continue;
            }
            if (c == '\'') {
                inString = true;
                sb.append(c);
            } else if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                // 行注释：丢弃到行尾（换行符保留，保证错误提示的行号不变）
                while (i < sql.length() && sql.charAt(i) != '\n') {
                    i++;
                }
                if (i < sql.length()) {
                    sb.append('\n');
                }
            } else if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                // 块注释：整体替换为一个空格，避免相邻 token 粘连
                int end = sql.indexOf("*/", i + 2);
                if (end < 0) {
                    return null;
                }
                sb.append(' ');
                i = end + 1;
            } else {
                sb.append(c);
            }
        }
        return inString ? null : sb.toString();
    }

    /** 存储核心缺失/超时视为不可用，需要回退模拟 */
    private boolean isStorageFailure(StorageResult result) {
        return result.getType() == StorageResult.Type.ERROR
                && STORAGE_FAILURE_CODES.contains(result.getErrorCode());
    }

    /**
     * 递归执行计划。存储核心没有 sort/group 算子：含 ORDER BY / GROUP BY 的查询
     * 由 Java 端对子计划结果做后处理（排序/去重分组），其余子树原样序列化执行；
     * JOIN 为存储核心原生算子，随子树一起下发。
     */
    private StorageResult executePlan(PlanNode plan) {
        if (plan instanceof PlanNode.OrderByPlan order) {
            StorageResult rs = executePlan(order.getChild());
            if (rs.getType() == StorageResult.Type.RESULTSET) {
                List<String> columns = new ArrayList<>();
                List<String> directions = new ArrayList<>();
                for (PlanNode.OrderByPlan.OrderItem item : order.getOrderByItems()) {
                    columns.add(item.getColumn());
                    directions.add(item.getDirection());
                }
                applyOrdering(rs, columns, directions);
            }
            return rs;
        }
        if (plan instanceof PlanNode.GroupByPlan group) {
            StorageResult rs = executePlan(group.getChild());
            if (rs.getType() == StorageResult.Type.RESULTSET) {
                return applyGrouping(rs, group.getGroupByColumns());
            }
            return rs;
        }
        // 投影位于最外层，其子树含后处理节点时投影也转 Java 端完成
        if (plan instanceof PlanNode.ProjectPlan project && containsPostProcess(project.getChild())) {
            StorageResult rs = executePlan(project.getChild());
            if (rs.getType() == StorageResult.Type.RESULTSET) {
                return projectResult(rs, project.getColumns());
            }
            return rs;
        }
        return storageClient.call(PhysicalPlanJson.serialize(plan));
    }

    /** 子树是否包含需要 Java 后处理的节点（ORDER BY / GROUP BY） */
    private boolean containsPostProcess(PlanNode plan) {
        if (plan == null) {
            return false;
        }
        if (plan instanceof PlanNode.OrderByPlan || plan instanceof PlanNode.GroupByPlan) {
            return true;
        }
        if (plan instanceof PlanNode.ProjectPlan p) {
            return containsPostProcess(p.getChild());
        }
        if (plan instanceof PlanNode.FilterPlan f) {
            return containsPostProcess(f.getChild());
        }
        return false;
    }

    /** ORDER BY 后处理：按排序项多键排序（数值按大小、字符串按字典序，null 最前） */
    private void applyOrdering(StorageResult result, List<String> columns, List<String> directions) {
        int[] indexes = new int[columns.size()];
        boolean[] descending = new boolean[columns.size()];
        for (int i = 0; i < indexes.length; i++) {
            indexes[i] = requireColumn(result, columns.get(i));
            descending[i] = "DESC".equalsIgnoreCase(directions.get(i));
        }
        result.getRows().sort((row1, row2) -> {
            for (int i = 0; i < indexes.length; i++) {
                int cmp = compareValues(row1.get(indexes[i]), row2.get(indexes[i]));
                if (cmp != 0) {
                    return descending[i] ? -cmp : cmp;
                }
            }
            return 0;
        });
    }

    /**
     * GROUP BY 后处理：按分组键分组，每组输出一行（代表行取组内首行），
     * 并在行尾附加 "COUNT(*)" 列 = 组内行数；投影按列名裁剪，未引用的列自然丢弃。
     * 分组键为空时视为全表一组（SELECT COUNT(*) 无 GROUP BY），空表计数为 0。
     *
     * @return 新的结果集（列 = 原列 + COUNT(*)）
     */
    private StorageResult applyGrouping(StorageResult result, List<String> groupColumns) {
        int[] keyIndexes = new int[groupColumns.size()];
        for (int i = 0; i < keyIndexes.length; i++) {
            keyIndexes[i] = requireColumn(result, groupColumns.get(i));
        }
        // 数值键统一为 double，避免 Long 20 与 Double 20.0 分成两组
        Map<List<Object>, List<List<Object>>> groups = new LinkedHashMap<>();
        for (List<Object> row : result.getRows()) {
            List<Object> key = new ArrayList<>(keyIndexes.length);
            for (int index : keyIndexes) {
                Object value = row.get(index);
                key.add(value instanceof Number number ? number.doubleValue() : value);
            }
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }
        List<List<Object>> outRows = new ArrayList<>();
        if (groups.isEmpty() && keyIndexes.length == 0) {
            // 空表的全表聚合：输出一行计数 0（其余列填 null 占位）
            List<Object> empty = new ArrayList<>(result.getColumns().size());
            for (int i = 0; i < result.getColumns().size(); i++) {
                empty.add(null);
            }
            empty.add(0L);
            outRows.add(empty);
        }
        for (List<List<Object>> group : groups.values()) {
            List<Object> outRow = new ArrayList<>(group.get(0)); // 代表行：键列值即首行值
            outRow.add((long) group.size());
            outRows.add(outRow);
        }
        List<String> columns = new ArrayList<>(result.getColumns());
        columns.add("COUNT(*)");
        return StorageResult.resultset(columns, outRows);
    }

    /** 投影清单是否含聚合项（如 COUNT(*)），与 PlanGenerator.hasAggregate 判定一致 */
    private static boolean hasAggregate(List<String> selectList) {
        for (String column : selectList) {
            if ("COUNT(*)".equalsIgnoreCase(column)) {
                return true;
            }
        }
        return false;
    }

    /** 投影后处理：SELECT * 原样返回，否则按列清单取值 */
    private StorageResult projectResult(StorageResult result, List<String> selectList) {
        if (selectList.contains("*")) {
            return result;
        }
        List<String> outColumns = new ArrayList<>();
        int[] indexes = new int[selectList.size()];
        for (int i = 0; i < indexes.length; i++) {
            String name = selectList.get(i);
            indexes[i] = requireColumn(result, name);
            outColumns.add(name.contains(".") ? name : result.getColumns().get(indexes[i]));
        }
        List<List<Object>> rows = new ArrayList<>();
        for (List<Object> row : result.getRows()) {
            List<Object> projected = new ArrayList<>(indexes.length);
            for (int index : indexes) {
                projected.add(row.get(index));
            }
            rows.add(projected);
        }
        return StorageResult.resultset(outColumns, rows);
    }

    /** 列名解析：要求存在，不存在抛出异常（由执行层转为 ERROR 结果） */
    private int requireColumn(StorageResult result, String name) {
        int index = resolveColumn(result.getColumns(), name);
        if (index < 0) {
            throw new IllegalArgumentException("列不存在: " + name);
        }
        return index;
    }

    /**
     * 列名解析：精确匹配优先；限定名（表.列）在无精确匹配时按唯一裸列名回退。
     * 兼容 JOIN 结果列的两种形态（裸列名 / 表名前缀）。
     */
    private int resolveColumn(List<String> columns, String name) {
        int exact = columns.indexOf(name);
        if (exact >= 0) {
            return exact;
        }
        int dot = name.indexOf('.');
        if (dot <= 0) {
            return -1;
        }
        String shortName = name.substring(dot + 1);
        String table = name.substring(0, dot);
        int unique = -1;
        int hits = 0;
        for (int i = 0; i < columns.size(); i++) {
            String column = columns.get(i);
            if (column.equals(shortName) || column.startsWith(table + "." + shortName)) {
                hits++;
                unique = i;
            }
        }
        return hits == 1 ? unique : -1;
    }

    /** 排序值比较：数值按大小、其余按字符串；null 排最前 */
    private int compareValues(Object left, Object right) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return -1;
        }
        if (right == null) {
            return 1;
        }
        Double a = toDouble(left);
        Double b = toDouble(right);
        if (a != null && b != null) {
            return Double.compare(a, b);
        }
        return String.valueOf(left).compareTo(String.valueOf(right));
    }

    /** 用内置示例数据模拟执行（数据保存在 JVM 内） */
    private StorageResult simulate(ASTNode ast) {
        // 回退模拟前校验模拟层有该表数据（启动同步后目录可能含核心侧表而模拟层无数据）
        String tableName = tableOf(ast);
        if (tableName != null && !tables.containsKey(tableName)) {
            return StorageResult.error("SIMULATE_NO_DATA", "模拟层缺少表数据: " + tableName);
        }
        try {
            if (ast instanceof ASTNode.SelectStmt stmt) {
                return simulateSelect(stmt);
            }
            if (ast instanceof ASTNode.InsertStmt stmt) {
                return simulateInsert(stmt);
            }
            if (ast instanceof ASTNode.UpdateStmt stmt) {
                return simulateUpdate(stmt);
            }
            if (ast instanceof ASTNode.DeleteStmt stmt) {
                return simulateDelete(stmt);
            }
            if (ast instanceof ASTNode.CreateTableStmt stmt) {
                return simulateCreateTable(stmt);
            }
            if (ast instanceof ASTNode.ShowStmt stmt) {
                return "TABLE".equals(stmt.getTarget())
                        ? simulateDescribeTable(stmt.getTableName())
                        : simulateShowTables();
            }
            if (ast instanceof ASTNode.DropTableStmt stmt) {
                return simulateDropTable(stmt);
            }
            return StorageResult.error("UNSUPPORTED",
                    "模拟模式不支持该语句: " + ast.getClass().getSimpleName());
        } catch (RuntimeException e) {
            // 模拟求值中的运行期错误（如除零）不向调用方抛出
            return StorageResult.error("EXEC_ERROR", e.getMessage());
        }
    }

    /** 取语句涉及的表名（SHOW TABLES 等无表语句返回 null） */
    private String tableOf(ASTNode ast) {
        if (ast instanceof ASTNode.SelectStmt stmt) {
            return stmt.getTableName();
        }
        if (ast instanceof ASTNode.InsertStmt stmt) {
            return stmt.getTableName();
        }
        if (ast instanceof ASTNode.UpdateStmt stmt) {
            return stmt.getTableName();
        }
        if (ast instanceof ASTNode.DeleteStmt stmt) {
            return stmt.getTableName();
        }
        return null;
    }

    /**
     * 模拟 SELECT：连接 -> ON/WHERE 过滤 -> GROUP BY 去重分组 -> ORDER BY 排序 -> 投影。
     * 计划组合顺序与 B 组 PlanGenerator 一致（投影最外层）。
     */
    private StorageResult simulateSelect(ASTNode.SelectStmt stmt) {
        // 1. 数据源：单表或内存嵌套循环连接（内连接语义）
        List<String> tableNames = new ArrayList<>();
        tableNames.add(stmt.getTableName());
        for (ASTNode.SelectStmt.JoinClause join : stmt.getJoins()) {
            tableNames.add(join.getTableName());
        }
        StorageResult source = buildJoinSource(tableNames);
        // 2. ON 条件（内连接）与 WHERE 过滤
        List<ASTNode> conditions = new ArrayList<>();
        for (ASTNode.SelectStmt.JoinClause join : stmt.getJoins()) {
            if (join.getOnCond() != null) {
                conditions.add(join.getOnCond());
            }
        }
        if (stmt.getWhereCond() != null) {
            conditions.add(stmt.getWhereCond());
        }
        if (!conditions.isEmpty()) {
            List<List<Object>> kept = new ArrayList<>();
            for (List<Object> row : source.getRows()) {
                boolean matched = true;
                for (ASTNode condition : conditions) {
                    if (!matchesCondition(row, source.getColumns(), condition)) {
                        matched = false;
                        break;
                    }
                }
                if (matched) {
                    kept.add(row);
                }
            }
            source = StorageResult.resultset(source.getColumns(), kept);
        }
        // 3. GROUP BY 分组并计算聚合（COUNT(*)）；无 GROUP BY 但投影含聚合时全表一组
        if (!stmt.getGroupBy().isEmpty() || hasAggregate(stmt.getSelectList())) {
            source = applyGrouping(source, stmt.getGroupBy());
        }
        // 4. ORDER BY
        if (!stmt.getOrderBy().isEmpty()) {
            List<String> columns = new ArrayList<>();
            List<String> directions = new ArrayList<>();
            for (ASTNode.SelectStmt.OrderItem item : stmt.getOrderBy()) {
                columns.add(item.getColumn());
                directions.add(item.getDirection());
            }
            applyOrdering(source, columns, directions);
        }
        // 5. 投影
        return projectResult(source, stmt.getSelectList());
    }

    /** 构建连接数据源：多表笛卡尔积，全部列中重名的加 "表名." 前缀消歧 */
    private StorageResult buildJoinSource(List<String> tableNames) {
        List<TableData> datas = new ArrayList<>(tableNames.size());
        List<String> bareNames = new ArrayList<>();
        for (String name : tableNames) {
            TableData data = tables.get(name);
            if (data == null) {
                throw new IllegalArgumentException("模拟层缺少表数据: " + name);
            }
            datas.add(data);
            bareNames.addAll(data.columnNames());
        }
        // 重名检测：出现多于一次的裸列名，所有同名列都加来源前缀
        List<String> columns = new ArrayList<>();
        for (int t = 0; t < datas.size(); t++) {
            for (String column : datas.get(t).columnNames()) {
                int occurrences = 0;
                for (String bare : bareNames) {
                    if (bare.equals(column)) {
                        occurrences++;
                    }
                }
                columns.add(occurrences > 1 ? tableNames.get(t) + "." + column : column);
            }
        }
        // 笛卡尔积：从一行空行出发逐表展开
        List<List<Object>> rows = new ArrayList<>();
        rows.add(new ArrayList<>());
        for (TableData data : datas) {
            List<List<Object>> next = new ArrayList<>();
            for (List<Object> partial : rows) {
                for (List<Object> row : data.rows) {
                    List<Object> combined = new ArrayList<>(partial);
                    combined.addAll(row);
                    next.add(combined);
                }
            }
            rows = next;
        }
        return StorageResult.resultset(columns, rows);
    }

    /** 模拟 INSERT：把新行追加到内置数据 */
    private StorageResult simulateInsert(ASTNode.InsertStmt stmt) {
        TableData data = tables.get(stmt.getTableName());
        List<Object> row = new ArrayList<>();
        for (ASTNode.LiteralExpr literal : stmt.getValues()) {
            row.add(literalValue(literal));
        }
        data.rows.add(row);
        return StorageResult.rowcount(1);
    }

    /** 模拟 UPDATE：更新满足条件的行（无 WHERE 作用于全表） */
    private StorageResult simulateUpdate(ASTNode.UpdateStmt stmt) {
        TableData data = tables.get(stmt.getTableName());
        List<String> columnNames = data.columnNames();
        long affected = 0;
        for (List<Object> row : data.rows) {
            if (stmt.getWhereCond() != null
                    && !matchesCondition(row, columnNames, stmt.getWhereCond())) {
                continue;
            }
            for (Map.Entry<String, ASTNode.LiteralExpr> assignment
                    : stmt.getAssignments().entrySet()) {
                int index = data.indexOf(assignment.getKey());
                if (index >= 0) {
                    row.set(index, literalValue(assignment.getValue()));
                }
            }
            affected++;
        }
        return StorageResult.rowcount(affected);
    }

    /** 模拟 DELETE：删除满足条件的行（无 WHERE 作用于全表） */
    private StorageResult simulateDelete(ASTNode.DeleteStmt stmt) {
        TableData data = tables.get(stmt.getTableName());
        List<String> columnNames = data.columnNames();
        Iterator<List<Object>> iterator = data.rows.iterator();
        long affected = 0;
        while (iterator.hasNext()) {
            List<Object> row = iterator.next();
            if (stmt.getWhereCond() == null
                    || matchesCondition(row, columnNames, stmt.getWhereCond())) {
                iterator.remove();
                affected++;
            }
        }
        return StorageResult.rowcount(affected);
    }

    /** 模拟 CREATE TABLE：元数据已在 execute 中统一登记，此处仅返回行数 */
    private StorageResult simulateCreateTable(ASTNode.CreateTableStmt stmt) {
        return StorageResult.rowcount(0);
    }

    /** 模拟 SHOW TABLES：列出模拟层全部表名 */
    private StorageResult simulateShowTables() {
        List<List<Object>> rows = new ArrayList<>();
        for (String name : tables.keySet()) {
            List<Object> r = new ArrayList<>();
            r.add(name);
            rows.add(r);
        }
        return StorageResult.resultset(List.of("table"), rows);
    }

    /** 模拟 SHOW TABLE 表名：从数据字典输出列名与类型（协议 describeTable 格式） */
    private StorageResult simulateDescribeTable(String tableName) {
        List<List<Object>> rows = new ArrayList<>();
        TableData data = tables.get(tableName);
        if (data != null) {
            for (CatalogImpl.ColumnInfo col : data.columns) {
                List<Object> r = new ArrayList<>();
                r.add(col.getName());
                r.add(col.getType().name());
                rows.add(r);
            }
        }
        return StorageResult.resultset(List.of("column", "type"), rows);
    }

    /** 模拟 DROP TABLE：元数据已在语义层校验存在，此处同步清理目录与数据 */
    private StorageResult simulateDropTable(ASTNode.DropTableStmt stmt) {
        removeTableMetadata(stmt.getTableName());
        return StorageResult.rowcount(0);
    }

    /** 删表后同步清理数据字典与模拟层数据 */
    private void removeTableMetadata(String tableName) {
        catalog.dropTable(tableName);
        tables.remove(tableName);
    }

    /** 把建表列定义转为列信息清单（类型字符串 -> DataType，未知类型按 VARCHAR） */
    private List<CatalogImpl.ColumnInfo> toColumnInfos(List<ASTNode.CreateTableStmt.ColumnDef> defs) {
        List<CatalogImpl.ColumnInfo> infos = new ArrayList<>();
        for (ASTNode.CreateTableStmt.ColumnDef def : defs) {
            CatalogImpl.DataType type;
            if ("INT".equalsIgnoreCase(def.getType())) {
                type = CatalogImpl.DataType.INT;
            } else if ("DOUBLE".equalsIgnoreCase(def.getType())) {
                type = CatalogImpl.DataType.DOUBLE;
            } else if ("BOOLEAN".equalsIgnoreCase(def.getType())) {
                type = CatalogImpl.DataType.BOOLEAN;
            } else {
                type = CatalogImpl.DataType.VARCHAR;
            }
            infos.add(new CatalogImpl.ColumnInfo(def.getName(), type));
        }
        return infos;
    }

    // ====================== 模拟执行的表达式求值 ======================

    /** 求值 WHERE 条件：结果为真时该行命中 */
    private boolean matchesCondition(List<Object> row, List<String> columns, ASTNode cond) {
        return Boolean.TRUE.equals(evalExpr(cond, row, columns));
    }

    /** 递归求值表达式：字面量/列引用直接取值，NOT 与二元表达式按运算符分发 */
    private Object evalExpr(ASTNode expr, List<Object> row, List<String> columns) {
        if (expr instanceof ASTNode.LiteralExpr literal) {
            return literalValue(literal);
        }
        if (expr instanceof ASTNode.IdentifierExpr ref) {
            int index = resolveColumn(columns, ref.getName());
            if (index < 0) {
                throw new IllegalArgumentException("列不存在: " + ref.getName());
            }
            return row.get(index);
        }
        if (expr instanceof ASTNode.UnaryExpr unary) {
            return !toBoolean(evalExpr(unary.getOperand(), row, columns));
        }
        if (expr instanceof ASTNode.BinaryExpr binary) {
            return evalBinary(binary, row, columns);
        }
        throw new IllegalArgumentException("不支持的表达式节点: " + expr.getClass().getSimpleName());
    }

    /** 二元表达式求值：逻辑运算短路求值；算术/比较按数值或字符串适配 */
    private Object evalBinary(ASTNode.BinaryExpr binary, List<Object> row, List<String> columns) {
        String op = binary.getOp();

        // 逻辑运算（Parser 将关键字 AND/OR 规范化为 AND/OR，&& / || 保持原样）
        if ("&&".equals(op) || "AND".equalsIgnoreCase(op)) {
            if (!toBoolean(evalExpr(binary.getLeft(), row, columns))) {
                return false; // 短路
            }
            return toBoolean(evalExpr(binary.getRight(), row, columns));
        }
        if ("||".equals(op) || "OR".equalsIgnoreCase(op)) {
            if (toBoolean(evalExpr(binary.getLeft(), row, columns))) {
                return true; // 短路
            }
            return toBoolean(evalExpr(binary.getRight(), row, columns));
        }

        Object left = evalExpr(binary.getLeft(), row, columns);
        Object right = evalExpr(binary.getRight(), row, columns);
        Double a = toDouble(left);
        Double b = toDouble(right);
        if (a != null && b != null) {
            switch (op) {
                case "+" -> { return a + b; }
                case "-" -> { return a - b; }
                case "*" -> { return a * b; }
                case "/" -> {
                    if (b == 0) {
                        throw new IllegalArgumentException("除数为零");
                    }
                    return a / b;
                }
                default -> { return compare(a, op, b); }
            }
        }
        return compare(String.valueOf(left), op, String.valueOf(right));
    }

    private boolean compare(double left, String op, double right) {
        return switch (op) {
            case "=", "==" -> left == right;
            case "!=", "<>" -> left != right;
            case ">" -> left > right;
            case "<" -> left < right;
            case ">=" -> left >= right;
            case "<=" -> left <= right;
            default -> false;
        };
    }

    private boolean compare(String left, String op, String right) {
        int cmp = left.compareTo(right);
        return switch (op) {
            case "=", "==" -> left.equals(right);
            case "!=", "<>" -> !left.equals(right);
            case ">" -> cmp > 0;
            case "<" -> cmp < 0;
            case ">=" -> cmp >= 0;
            case "<=" -> cmp <= 0;
            default -> false;
        };
    }

    /** 字面量取值：NUMBER 转数值，BOOLEAN 转布尔，STRING 保持文本 */
    private Object literalValue(ASTNode.LiteralExpr literal) {
        return switch (literal.getKind()) {
            case NUMBER -> toNumber(literal.getValue());
            case STRING -> literal.getValue();
            case BOOLEAN -> "TRUE".equals(literal.getValue());
        };
    }

    /** 对象转 Boolean：支持布尔、TRUE/FALSE 文本与数字（非 0 为真） */
    private boolean toBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if ("TRUE".equalsIgnoreCase(String.valueOf(value))) {
            return true;
        }
        if ("FALSE".equalsIgnoreCase(String.valueOf(value))) {
            return false;
        }
        Double number = toDouble(value);
        return number != null && number != 0;
    }

    /** 对象转 Double，失败返回 null（用于判断能否数值运算/比较） */
    private Double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 数字原文转数值：优先整数，失败按小数 */
    private Object toNumber(String text) {
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException e) {
            return Double.valueOf(text);
        }
    }

    // ====================== 内置示例数据 ======================

    /** 初始化内置示例数据并注册进数据字典（LOCAL 模式与回退场景使用） */
    private void initLocalDemo() {
        initSampleData();
        tables.forEach((name, data) -> catalog.createTableWithTypes(name, data.columns));
    }

    /** 初始化 student / course / teacher 三张示例表（列带类型，供语义校验） */
    private void initSampleData() {
        tables.put("student", new TableData(
                List.of(
                        new CatalogImpl.ColumnInfo("id", CatalogImpl.DataType.INT),
                        new CatalogImpl.ColumnInfo("name", CatalogImpl.DataType.VARCHAR),
                        new CatalogImpl.ColumnInfo("age", CatalogImpl.DataType.INT),
                        new CatalogImpl.ColumnInfo("grade", CatalogImpl.DataType.VARCHAR)
                ),
                new ArrayList<>(List.of(
                        row(1, "Alice", 20, "A"),
                        row(2, "Bob", 22, "B+"),
                        row(3, "Carol", 21, "A-"),
                        row(4, "David", 23, "B"),
                        row(5, "Eve", 19, "A+")
                ))
        ));
        tables.put("course", new TableData(
                List.of(
                        new CatalogImpl.ColumnInfo("cid", CatalogImpl.DataType.INT),
                        new CatalogImpl.ColumnInfo("title", CatalogImpl.DataType.VARCHAR),
                        new CatalogImpl.ColumnInfo("credit", CatalogImpl.DataType.INT)
                ),
                new ArrayList<>(List.of(
                        row(101, "Database", 4),
                        row(102, "Operating Sys", 3),
                        row(103, "Compiler", 4)
                ))
        ));
        tables.put("teacher", new TableData(
                List.of(
                        new CatalogImpl.ColumnInfo("tid", CatalogImpl.DataType.INT),
                        new CatalogImpl.ColumnInfo("name", CatalogImpl.DataType.VARCHAR),
                        new CatalogImpl.ColumnInfo("dept", CatalogImpl.DataType.VARCHAR)
                ),
                new ArrayList<>(List.of(
                        row(1, "Yao Xin", "Computer"),
                        row(2, "Gui Ning", "Computer"),
                        row(3, "Deng Lei", "Computer")
                ))
        ));
    }

    /** 便捷构造一行数据（可变列表，支持 UPDATE 就地修改） */
    private List<Object> row(Object... cells) {
        return new ArrayList<>(Arrays.asList(cells));
    }
}
