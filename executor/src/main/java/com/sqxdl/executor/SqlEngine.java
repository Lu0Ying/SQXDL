package com.sqxdl.executor;

import com.sqxdl.executor.storage.StorageClient;
import com.sqxdl.executor.storage.StorageResult;
import com.sqxdl.parser.ASTNode;
import com.sqxdl.semantic.CatalogImpl;
import com.sqxdl.semantic.PlanGenerator;
import com.sqxdl.semantic.PlanNode;
import com.sqxdl.semantic.SemanticAnalyzer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 执行引擎：CLI（Main）与 GUI（SwingDemo）共用的执行门面。
 * 流水线：SQL 文本 -> 构造 AST -> 语义分析 -> 计划生成 -> 存储核心执行。
 * 说明：A 组 Parser 完成前，AST 由本类的正则解析临时构造，接口不变，
 *       之后只需把 parse 方法替换为 Parser 调用即可。
 * 存储核心（storage_core.exe）不可用时回退内置示例数据模拟执行，
 * 保证 Demo 脱离 C++ 存储程序也能完整演示。
 */
public class SqlEngine {

    /**
     * 执行模式：
     * AUTO —— 优先真实存储核心（storage_core.exe），不可用时回退本地模拟；
     * LOCAL —— 始终本地模拟执行（数据保存在 JVM 内），供 GUI 演示使用。
     * 说明：当前存储核心每次调用都是独立进程、无持久化，
     *       数据无法跨语句保留，故界面演示使用 LOCAL 保证效果。
     */
    public enum Mode { AUTO, LOCAL }

    /** 内存表结构：列名 + 数据行（模拟模式的数据载体） */
    private static class TableData {
        final List<String> columns;
        final List<List<Object>> rows;

        TableData(List<String> columns, List<List<Object>> rows) {
            this.columns = columns;
            this.rows = rows;
        }
    }

    // SELECT * FROM 表 [WHERE 列 操作符 值]
    private static final Pattern SELECT_PATTERN = Pattern.compile(
            "(?i)^SELECT\\s+\\*\\s+FROM\\s+(\\w+)(?:\\s+WHERE\\s+(\\w+)\\s*(=|!=|<>|>|<|>=|<=)\\s*(.+?))?$");

    // INSERT INTO 表 VALUES (v1, v2, ...)
    private static final Pattern INSERT_PATTERN = Pattern.compile(
            "(?i)^INSERT\\s+INTO\\s+(\\w+)\\s+VALUES\\s*\\((.+)\\)$");

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

    /** 默认 AUTO 模式：优先真实存储核心 */
    public SqlEngine() {
        this(Mode.AUTO);
    }

    public SqlEngine(Mode mode) {
        this.mode = mode;
        initSampleData();
        // 把示例表注册进数据字典，语义分析即可对表/列做真实校验
        tables.forEach((name, data) -> catalog.createTable(name, data.columns));
    }

    /** 可供浏览的表名列表（供 GUI 左侧列表使用） */
    public List<String> tableNames() {
        return new ArrayList<>(tables.keySet());
    }

    /** 最近一次执行的回退说明；null 表示真实执行 */
    public String getFallbackReason() {
        return fallbackReason;
    }

    /**
     * 执行一条 SQL，任何失败都以 ERROR 结果返回，不抛异常。
     * 仅应在 Swing EDT（GUI）或 REPL 单线程（CLI）中调用，内部数据无并发保护。
     */
    public StorageResult execute(String sql) {
        fallbackReason = null;
        String normalized = normalize(sql);
        if (normalized.isEmpty()) {
            return StorageResult.error("EMPTY_SQL", "SQL 语句为空");
        }

        // 阶段一：解析（语法错误）
        ASTNode ast;
        try {
            ast = parse(normalized);
        } catch (IllegalArgumentException e) {
            return StorageResult.error("SYNTAX_ERROR", e.getMessage());
        }
        // 非 SELECT/INSERT 语句暂不解析，按"执行成功"处理
        if (ast == null) {
            return StorageResult.rowcount(0);
        }

        // 阶段二：语义分析（复用 B 组真实校验）
        try {
            new SemanticAnalyzer(catalog).analyze(ast);
        } catch (IllegalArgumentException e) {
            return StorageResult.error("SEMANTIC_ERROR", e.getMessage());
        }

        // 阶段三：计划生成 + 执行
        PlanNode plan = new PlanGenerator(catalog).generate(ast);
        // LOCAL 模式直接本地模拟（数据保存在 JVM，演示可跨语句看到变化）
        if (mode == Mode.LOCAL) {
            return simulate(ast);
        }
        // AUTO 模式走真实存储核心，不可用时回退模拟
        StorageResult result = storageClient.execute(plan);
        if (!isStorageFailure(result)) {
            return result;
        }
        fallbackReason = "存储核心不可用，已使用内置示例数据模拟执行";
        return simulate(ast);
    }

    // ====================== 解析：SQL 文本 -> AST ======================

    /** 规范化输入：去首尾空白与末尾分号 */
    private String normalize(String sql) {
        String trimmed = sql.trim();
        return trimmed.endsWith(";")
                ? trimmed.substring(0, trimmed.length() - 1).trim()
                : trimmed;
    }

    /**
     * 临时解析器：正则匹配构造 AST。
     * SELECT/INSERT 前缀但格式不符时抛语法错误；其余语句返回 null。
     */
    private ASTNode parse(String sql) {
        Matcher select = SELECT_PATTERN.matcher(sql);
        if (select.matches()) {
            return buildSelect(select);
        }
        Matcher insert = INSERT_PATTERN.matcher(sql);
        if (insert.matches()) {
            return buildInsert(insert);
        }
        String upper = sql.toUpperCase();
        if (upper.startsWith("SELECT") || upper.startsWith("INSERT")) {
            throw new IllegalArgumentException("无法解析的语句: " + sql
                    + "（当前支持 SELECT * FROM 表 [WHERE 列 op 值] 与 INSERT INTO 表 VALUES (...)" +
                    "）");
        }
        return null;
    }

    /** 构造 SELECT AST：selectList 固定 [*]，WHERE 存在时构造 列 op 字面量 */
    private ASTNode buildSelect(Matcher m) {
        ASTNode whereCond = null;
        if (m.group(2) != null) {
            whereCond = new ASTNode.BinaryExpr(1, 1, m.group(3),
                    new ASTNode.ColumnRef(1, 1, m.group(2)),
                    buildLiteral(m.group(4).trim()));
        }
        return new ASTNode.SelectStmt(1, 1, m.group(1), List.of("*"), whereCond);
    }

    /** 构造 INSERT AST：未指定列清单（空列表表示按建表顺序对应） */
    private ASTNode buildInsert(Matcher m) {
        List<ASTNode.LiteralExpr> values = new ArrayList<>();
        for (String item : splitValues(m.group(2))) {
            values.add(buildLiteral(item.trim()));
        }
        return new ASTNode.InsertStmt(1, 1, m.group(1), List.of(), values);
    }

    /** 字面量构造：引号包裹 -> STRING（去引号），纯数字 -> NUMBER（存原文），其余按字符串 */
    private ASTNode.LiteralExpr buildLiteral(String text) {
        boolean quoted = (text.startsWith("'") && text.endsWith("'") && text.length() >= 2)
                || (text.startsWith("\"") && text.endsWith("\"") && text.length() >= 2);
        if (quoted) {
            return new ASTNode.LiteralExpr(1, 1,
                    text.substring(1, text.length() - 1), ASTNode.LiteralExpr.Kind.STRING);
        }
        if (text.matches("\\d+(\\.\\d+)?")) {
            return new ASTNode.LiteralExpr(1, 1, text, ASTNode.LiteralExpr.Kind.NUMBER);
        }
        return new ASTNode.LiteralExpr(1, 1, text, ASTNode.LiteralExpr.Kind.STRING);
    }

    /** 拆分 VALUES 值列表，引号内的逗号不参与拆分 */
    private List<String> splitValues(String content) {
        List<String> items = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '\'' || c == '"') {
                inQuote = !inQuote;
                current.append(c);
            } else if (c == ',' && !inQuote) {
                items.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        items.add(current.toString());
        return items;
    }

    // ====================== 执行：真实存储 / 模拟回退 ======================

    /** 存储核心缺失/超时视为不可用，需要回退模拟 */
    private boolean isStorageFailure(StorageResult result) {
        return result.getType() == StorageResult.Type.ERROR
                && STORAGE_FAILURE_CODES.contains(result.getErrorCode());
    }

    /** 用内置示例数据模拟执行（数据保存在 JVM 内） */
    private StorageResult simulate(ASTNode ast) {
        if (ast instanceof ASTNode.SelectStmt stmt) {
            return simulateSelect(stmt);
        }
        if (ast instanceof ASTNode.InsertStmt stmt) {
            return simulateInsert(stmt);
        }
        return StorageResult.error("UNSUPPORTED",
                "模拟模式不支持该语句: " + ast.getClass().getSimpleName());
    }

    /** 模拟 SELECT：按 WHERE 条件过滤内置数据 */
    private StorageResult simulateSelect(ASTNode.SelectStmt stmt) {
        TableData data = tables.get(stmt.getTableName());
        ASTNode.BinaryExpr cond = stmt.getWhereCond() instanceof ASTNode.BinaryExpr expr
                ? expr : null;

        List<List<Object>> rows = new ArrayList<>();
        for (List<Object> row : data.rows) {
            if (cond == null || matchesCondition(row, data.columns, cond)) {
                rows.add(row);
            }
        }
        return StorageResult.resultset(data.columns, rows);
    }

    /** 模拟 INSERT：把新行追加到内置数据 */
    private StorageResult simulateInsert(ASTNode.InsertStmt stmt) {
        TableData data = tables.get(stmt.getTableName());
        List<Object> row = new ArrayList<>();
        for (ASTNode.LiteralExpr literal : stmt.getValues()) {
            row.add(literal.getKind() == ASTNode.LiteralExpr.Kind.NUMBER
                    ? toNumber(literal.getValue())
                    : literal.getValue());
        }
        data.rows.add(row);
        return StorageResult.rowcount(1);
    }

    // ====================== 模拟过滤的求值与比较 ======================

    /** 求值 WHERE 条件：列 op 字面量，数字列按数值比较，否则按字符串比较 */
    private boolean matchesCondition(List<Object> row, List<String> columns,
                                     ASTNode.BinaryExpr cond) {
        String column = ((ASTNode.ColumnRef) cond.getLeft()).getName();
        ASTNode.LiteralExpr literal = (ASTNode.LiteralExpr) cond.getRight();

        int index = columns.indexOf(column);
        if (index < 0) {
            return false; // 语义分析已保证列存在，此处防御性兜底
        }
        Object target = literal.getKind() == ASTNode.LiteralExpr.Kind.NUMBER
                ? toNumber(literal.getValue())
                : literal.getValue();

        Double left = toDouble(row.get(index));
        Double right = toDouble(target);
        if (left != null && right != null) {
            return compare(left, cond.getOp(), right);
        }
        return compare(String.valueOf(row.get(index)), cond.getOp(), String.valueOf(target));
    }

    private boolean compare(double left, String op, double right) {
        return switch (op) {
            case "=" -> left == right;
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
            case "=" -> left.equals(right);
            case "!=", "<>" -> !left.equals(right);
            case ">" -> cmp > 0;
            case "<" -> cmp < 0;
            case ">=" -> cmp >= 0;
            case "<=" -> cmp <= 0;
            default -> false;
        };
    }

    /** 对象转 Double，失败返回 null（用于判断能否数值比较） */
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

    /** 初始化 student / course / teacher 三张示例表 */
    private void initSampleData() {
        tables.put("student", new TableData(
                List.of("id", "name", "age", "grade"),
                new ArrayList<>(List.of(
                        row(1, "Alice", 20, "A"),
                        row(2, "Bob", 22, "B+"),
                        row(3, "Carol", 21, "A-"),
                        row(4, "David", 23, "B"),
                        row(5, "Eve", 19, "A+")
                ))
        ));
        tables.put("course", new TableData(
                List.of("cid", "title", "credit"),
                new ArrayList<>(List.of(
                        row(101, "Database", 4),
                        row(102, "Operating Sys", 3),
                        row(103, "Compiler", 4)
                ))
        ));
        tables.put("teacher", new TableData(
                List.of("tid", "name", "dept"),
                new ArrayList<>(List.of(
                        row(1, "Yao Xin", "Computer"),
                        row(2, "Gui Ning", "Computer"),
                        row(3, "Deng Lei", "Computer")
                ))
        ));
    }

    /** 便捷构造一行数据 */
    private List<Object> row(Object... cells) {
        return Arrays.asList(cells);
    }
}
