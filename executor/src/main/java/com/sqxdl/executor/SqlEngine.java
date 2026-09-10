package com.sqxdl.executor;

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
 * SQL 执行引擎：CLI（Main）与 GUI（SwingDemo）共用的执行门面。
 * 流水线：SQL 文本 -> Lexer/Parser（A 组）-> 语义分析 -> 计划生成 -> 执行。
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

    /** 默认 AUTO 模式：优先真实存储核心 */
    public SqlEngine() {
        this(Mode.AUTO);
    }

    public SqlEngine(Mode mode) {
        this.mode = mode;
        initSampleData();
        // 把示例表的列名与类型注册进数据字典，语义分析即可做真实校验
        tables.forEach((name, data) -> catalog.createTableWithTypes(name, data.columns));
        // AUTO 模式启动时与存储核心同步表结构（B 组方案 B）；
        // 核心未报告任何表（如骨架阶段 showTables 为空）时跳过，保留内置示例数据
        if (mode == Mode.AUTO) {
            syncCatalogFromStorage();
        }
    }

    /** 可供浏览的表名列表（供 GUI 左侧列表使用） */
    public List<String> tableNames() {
        return new ArrayList<>(tables.keySet());
    }

    /** 最近一次执行的回退说明；null 表示真实执行 */
    public String getFallbackReason() {
        return fallbackReason;
    }

    /** AUTO 启动时从存储核心同步表结构（showTables + describeTable） */
    private void syncCatalogFromStorage() {
        StorageTableMetadataProvider provider = new StorageTableMetadataProvider(storageClient);
        List<String> storageTables = provider.getTableNames();
        if (!storageTables.isEmpty()) {
            catalog.syncFromStorage(provider);
        }
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

        // 阶段一：词法 + 语法解析（复用 A 组真实代码）
        ASTNode ast;
        try {
            ast = new Parser(new Lexer(normalized)).parse();
        } catch (SqxdlException e) {
            return StorageResult.error("SYNTAX_ERROR", e.getMessage());
        }

        // 阶段二：语义分析（复用 B 组真实校验，含列类型校验）
        try {
            new SemanticAnalyzer(catalog).analyze(ast);
        } catch (RuntimeException e) {
            return StorageResult.error("SEMANTIC_ERROR", e.getMessage());
        }

        // 建表元数据在此统一登记（AUTO/LOCAL 共用），保证后续语句的语义校验可见新表；
        // 数据写入由存储核心（AUTO）或内置模拟层（LOCAL/回退）负责
        if (ast instanceof ASTNode.CreateTableStmt createStmt) {
            List<CatalogImpl.ColumnInfo> infos = toColumnInfos(createStmt.getColumns());
            catalog.createTableWithTypes(createStmt.getTableName(), infos);
            tables.put(createStmt.getTableName(), new TableData(infos, new ArrayList<>()));
        }

        // 阶段三：计划生成 + 执行（generate 含表达式优化，任何异常都以 ERROR 返回）
        PlanNode plan;
        try {
            plan = new PlanGenerator(catalog).generate(ast);
        } catch (RuntimeException e) {
            return StorageResult.error("PLAN_ERROR", e.getMessage());
        }
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

    // ====================== 执行：真实存储 / 模拟回退 ======================

    /** 规范化输入：去首尾空白与末尾分号 */
    private String normalize(String sql) {
        String trimmed = sql.trim();
        return trimmed.endsWith(";")
                ? trimmed.substring(0, trimmed.length() - 1).trim()
                : trimmed;
    }

    /** 存储核心缺失/超时视为不可用，需要回退模拟 */
    private boolean isStorageFailure(StorageResult result) {
        return result.getType() == StorageResult.Type.ERROR
                && STORAGE_FAILURE_CODES.contains(result.getErrorCode());
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
            if (ast instanceof ASTNode.ShowTablesStmt) {
                return simulateShowTables();
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

    /** 模拟 SELECT：按 WHERE 条件过滤，再按列清单投影 */
    private StorageResult simulateSelect(ASTNode.SelectStmt stmt) {
        TableData data = tables.get(stmt.getTableName());
        List<String> allNames = data.columnNames();
        // 投影列：SELECT * 为全部列，否则按列清单
        List<String> projectedColumns = stmt.getSelectList().contains("*")
                ? allNames
                : stmt.getSelectList();

        List<List<Object>> rows = new ArrayList<>();
        for (List<Object> row : data.rows) {
            if (stmt.getWhereCond() != null
                    && !matchesCondition(row, allNames, stmt.getWhereCond())) {
                continue;
            }
            List<Object> projected = new ArrayList<>();
            for (String column : projectedColumns) {
                projected.add(row.get(data.indexOf(column)));
            }
            rows.add(projected);
        }
        return StorageResult.resultset(projectedColumns, rows);
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
            CatalogImpl.DataType type = "INT".equalsIgnoreCase(def.getType())
                    ? CatalogImpl.DataType.INT
                    : CatalogImpl.DataType.VARCHAR;
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
            int index = columns.indexOf(ref.getName());
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
