package com.sqxdl.core.executor;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQXDL 数据库管理系统交互界面 Demo（纯 Swing，无需连接真实数据库）。
 * 提供表浏览、SQL 模拟执行、日志输出等功能，所有数据保存在内存中。
 */
public class SwingDemo extends JFrame {

    // ====================== 内存数据模型 ======================

    /** 表数据：列名 + 数据行。 */
    private static class TableData {
        final String[] columns;
        final List<Object[]> rows;

        TableData(String[] columns, List<Object[]> rows) {
            this.columns = columns;
            this.rows = rows;
        }
    }

    /** 内存中的全部表：表名 -> 表数据。 */
    private final Map<String, TableData> tables = new LinkedHashMap<>();

    // ====================== UI 组件 ======================

    private JList<String> tableList;
    private DefaultTableModel tableModel;
    private JTable dataTable;
    private JLabel rowCountLabel;
    private JTextArea sqlArea;
    private JTextArea logArea;
    private boolean connected = false;

    // ====================== SQL 解析正则 ======================

    // SELECT * FROM 表 [WHERE 列 操作符 值]
    private static final Pattern SELECT_PATTERN = Pattern.compile(
            "(?i)^SELECT\\s+\\*\\s+FROM\\s+(\\w+)(?:\\s+WHERE\\s+(\\w+)\\s*(=|!=|<>|>|<|>=|<=)\\s*(.+?))?$");

    // INSERT INTO 表 VALUES (v1, v2, ...)
    private static final Pattern INSERT_PATTERN = Pattern.compile(
            "(?i)^INSERT\\s+INTO\\s+(\\w+)\\s+VALUES\\s*\\((.+)\\)$");

    // ====================== 构造与初始化 ======================

    public SwingDemo() {
        initSampleData();
        initUI();
    }

    /** 加载三张示例表的样本数据。 */
    private void initSampleData() {
        tables.put("student", new TableData(
                new String[]{"id", "name", "age", "grade"},
                new ArrayList<>(Arrays.asList(
                        new Object[]{1, "Alice", 20, "A"},
                        new Object[]{2, "Bob", 22, "B+"},
                        new Object[]{3, "Carol", 21, "A-"},
                        new Object[]{4, "David", 23, "B"},
                        new Object[]{5, "Eve", 19, "A+"}
                ))
        ));
        tables.put("course", new TableData(
                new String[]{"cid", "title", "credit"},
                new ArrayList<>(Arrays.asList(
                        new Object[]{101, "Database", 4},
                        new Object[]{102, "Operating Sys", 3},
                        new Object[]{103, "Compiler", 4}
                ))
        ));
        tables.put("teacher", new TableData(
                new String[]{"tid", "name", "dept"},
                new ArrayList<>(Arrays.asList(
                        new Object[]{1, "Yao Xin", "Computer"},
                        new Object[]{2, "Gui Ning", "Computer"},
                        new Object[]{3, "Deng Lei", "Computer"}
                ))
        ));
    }

    /** 构建全部 UI 组件并布局。 */
    private void initUI() {
        setTitle("SQXDL Database Manager");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(960, 720);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout(8, 8));

        add(buildToolbar(), BorderLayout.NORTH);
        add(buildMainArea(), BorderLayout.CENTER);
        add(buildBottomArea(), BorderLayout.SOUTH);

        log("系统就绪，点击「连接」开始使用 SQXDL Database Manager v1.0");
    }

    /** 顶部工具栏：连接 / 断开。 */
    private JToolBar buildToolbar() {
        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        toolBar.setBorder(new EmptyBorder(6, 8, 6, 8));

        JLabel title = new JLabel("🗄️ SQXDL Database Manager");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
        toolBar.add(title);
        toolBar.add(Box.createHorizontalGlue());

        JButton connectBtn = new JButton("连接");
        JButton disconnectBtn = new JButton("断开");
        toolBar.add(connectBtn);
        toolBar.add(Box.createHorizontalStrut(8));
        toolBar.add(disconnectBtn);

        connectBtn.addActionListener(e -> connect());
        disconnectBtn.addActionListener(e -> disconnect());
        return toolBar;
    }

    /** 中间主区域：左侧表列表 + 右侧数据表格。 */
    private JSplitPane buildMainArea() {
        // 左侧：表列表
        tableList = new JList<>(tables.keySet().toArray(new String[0]));
        tableList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tableList.setFixedCellHeight(28);
        tableList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && tableList.getSelectedValue() != null) {
                showTable(tableList.getSelectedValue());
            }
        });
        JScrollPane listScroll = new JScrollPane(tableList);
        listScroll.setBorder(BorderFactory.createTitledBorder("📂 表列表"));
        listScroll.setPreferredSize(new Dimension(160, 0));

        // 右侧：数据表格 + 行数标签
        tableModel = new DefaultTableModel() {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false; // 表格只读
            }
        };
        dataTable = new JTable(tableModel);
        dataTable.setRowHeight(24);
        dataTable.setAutoCreateRowSorter(true);
        JScrollPane tableScroll = new JScrollPane(dataTable);
        tableScroll.setBorder(BorderFactory.createTitledBorder("📊 数据浏览"));

        rowCountLabel = new JLabel("共 0 行");
        rowCountLabel.setBorder(new EmptyBorder(2, 8, 4, 8));

        JPanel tablePanel = new JPanel(new BorderLayout());
        tablePanel.add(tableScroll, BorderLayout.CENTER);
        tablePanel.add(rowCountLabel, BorderLayout.SOUTH);

        JSplitPane splitPane = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT, listScroll, tablePanel);
        splitPane.setDividerLocation(160);
        splitPane.setBorder(new EmptyBorder(0, 8, 0, 8));
        return splitPane;
    }

    /** 底部区域：SQL 执行区 + 日志输出。 */
    private JPanel buildBottomArea() {
        // SQL 输入区
        sqlArea = new JTextArea(3, 60);
        sqlArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        sqlArea.setTabSize(4);
        JScrollPane sqlScroll = new JScrollPane(sqlArea);
        sqlScroll.setBorder(BorderFactory.createTitledBorder("SQL 执行区"));

        JButton execBtn = new JButton("▶ 执行");
        JButton clearBtn = new JButton("清空");
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        btnPanel.add(execBtn);
        btnPanel.add(clearBtn);

        JPanel sqlPanel = new JPanel(new BorderLayout());
        sqlPanel.add(sqlScroll, BorderLayout.CENTER);
        sqlPanel.add(btnPanel, BorderLayout.SOUTH);

        // 日志输出区
        logArea = new JTextArea(7, 60);
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        logArea.setForeground(new Color(0x333333));
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createTitledBorder("日志输出"));

        JPanel bottom = new JPanel(new BorderLayout(0, 8));
        bottom.setBorder(new EmptyBorder(0, 8, 8, 8));
        bottom.add(sqlPanel, BorderLayout.NORTH);
        bottom.add(logScroll, BorderLayout.CENTER);

        execBtn.addActionListener(e -> executeSql());
        clearBtn.addActionListener(e -> sqlArea.setText(""));
        return bottom;
    }

    // ====================== 业务逻辑 ======================

    /** 连接数据库（模拟）。 */
    private void connect() {
        if (connected) {
            log("⚠ 已处于连接状态");
            return;
        }
        connected = true;
        log("✅ 已连接到 SQXDL 数据库，版本 v1.0");
    }

    /** 断开连接（模拟）。 */
    private void disconnect() {
        if (!connected) {
            log("⚠ 当前未连接");
            return;
        }
        connected = false;
        log("🔌 已断开连接");
    }

    /** 在右侧表格中显示指定表的全部数据。 */
    private void showTable(String tableName) {
        TableData data = tables.get(tableName);
        if (data == null) {
            log("❌ 表不存在: " + tableName);
            return;
        }
        tableModel.setDataVector(toMatrix(data.rows), data.columns);
        updateRowCount(data.rows.size());
        log("📊 加载表 " + tableName + "，共 " + data.rows.size() + " 行");
    }

    /** 执行 SQL 语句并记录耗时。 */
    private void executeSql() {
        String sql = sqlArea.getText().trim();
        if (sql.isEmpty()) {
            log("⚠ SQL 语句为空");
            return;
        }
        // 去除末尾分号，便于正则匹配
        if (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).trim();
        }

        long start = System.currentTimeMillis();
        try {
            String upper = sql.toUpperCase();
            if (upper.startsWith("SELECT")) {
                executeSelect(sql);
            } else if (upper.startsWith("INSERT")) {
                executeInsert(sql);
            } else {
                // 其他语句（UPDATE/DELETE 等）统一返回成功
                log("✅ 执行成功");
            }
        } catch (Exception ex) {
            log("❌ 执行错误: " + ex.getMessage());
        }
        long elapsed = System.currentTimeMillis() - start;
        log("⏱ 执行耗时: " + elapsed + "ms");
    }

    /** 执行 SELECT 语句，支持 WHERE 单条件过滤。 */
    private void executeSelect(String sql) {
        Matcher m = SELECT_PATTERN.matcher(sql);
        if (!m.matches()) {
            log("❌ 语法错误: SELECT 语句格式不正确，期望 SELECT * FROM 表名 [WHERE 列 操作符 值]");
            return;
        }

        String tableName = m.group(1);
        TableData data = tables.get(tableName);
        if (data == null) {
            log("❌ 表不存在: " + tableName);
            return;
        }

        List<Object[]> result = new ArrayList<>(data.rows);

        // 存在 WHERE 条件时进行过滤
        if (m.group(2) != null) {
            String col = m.group(2);
            String op = m.group(3);
            String rawVal = m.group(4).trim();
            int colIdx = indexOf(data.columns, col);
            if (colIdx < 0) {
                log("❌ 列不存在: " + col);
                return;
            }
            result = filterRows(result, colIdx, op, rawVal);
        }

        tableModel.setDataVector(toMatrix(result), data.columns);
        updateRowCount(result.size());
        log("✅ 查询成功，返回 " + result.size() + " 行");
    }

    /** 执行 INSERT 语句，将新行追加到内存表。 */
    private void executeInsert(String sql) {
        Matcher m = INSERT_PATTERN.matcher(sql);
        if (!m.matches()) {
            log("❌ 语法错误: INSERT 语句格式不正确，期望 INSERT INTO 表名 VALUES (...)");
            return;
        }

        String tableName = m.group(1);
        TableData data = tables.get(tableName);
        if (data == null) {
            log("❌ 表不存在: " + tableName);
            return;
        }

        String[] values = splitValues(m.group(2));
        if (values.length != data.columns.length) {
            log("❌ 列数不匹配: 期望 " + data.columns.length + "，实际 " + values.length);
            return;
        }

        Object[] row = new Object[values.length];
        for (int i = 0; i < values.length; i++) {
            row[i] = parseValue(values[i].trim());
        }
        data.rows.add(row);
        log("✅ 插入成功，1 行受影响");
    }

    // ====================== 工具方法 ======================

    /** 按列名查找下标，未找到返回 -1。 */
    private int indexOf(String[] columns, String name) {
        for (int i = 0; i < columns.length; i++) {
            if (columns[i].equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 根据 WHERE 条件过滤行。
     * 尝试将值解析为数字进行数值比较，失败则按字符串比较。
     */
    private List<Object[]> filterRows(List<Object[]> rows, int colIdx, String op, String rawVal) {
        List<Object[]> result = new ArrayList<>();
        Object target = parseValue(rawVal);

        for (Object[] row : rows) {
            Object cell = row[colIdx];
            if (matchCondition(cell, op, target)) {
                result.add(row);
            }
        }
        return result;
    }

    /** 单元格值与目标值比较，统一数值与字符串两种情形。 */
    private boolean matchCondition(Object cell, String op, Object target) {
        // 数值比较：两侧都能转为数字
        Double a = toDouble(cell);
        Double b = toDouble(target);
        if (a != null && b != null) {
            return compareNumeric(a, op, b);
        }
        // 回退到字符串比较
        return compareString(String.valueOf(cell), op, String.valueOf(target));
    }

    private boolean compareNumeric(double a, String op, double b) {
        switch (op) {
            case "=":
                return a == b;
            case "!=":
            case "<>":
                return a != b;
            case ">":
                return a > b;
            case "<":
                return a < b;
            case ">=":
                return a >= b;
            case "<=":
                return a <= b;
            default:
                return false;
        }
    }

    private boolean compareString(String a, String op, String b) {
        int cmp = a.compareTo(b);
        switch (op) {
            case "=":
                return a.equals(b);
            case "!=":
            case "<>":
                return !a.equals(b);
            case ">":
                return cmp > 0;
            case "<":
                return cmp < 0;
            case ">=":
                return cmp >= 0;
            case "<=":
                return cmp <= 0;
            default:
                return false;
        }
    }

    /** 将对象转为 Double，失败返回 null。 */
    private Double toDouble(Object obj) {
        if (obj instanceof Number) {
            return ((Number) obj).doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(obj));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 解析字面量：数字字符串转为数字，带引号的字符串去除引号。
     */
    private Object parseValue(String raw) {
        // 去掉首尾引号
        if ((raw.startsWith("'") && raw.endsWith("'"))
                || (raw.startsWith("\"") && raw.endsWith("\""))) {
            return raw.substring(1, raw.length() - 1);
        }
        // 尝试整数
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ignore) {
            // 继续尝试小数
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException ignore) {
            // 当作字符串
        }
        return raw;
    }

    /**
     * 拆分 VALUES 中的值列表，避免误拆字符串内的逗号。
     */
    private String[] splitValues(String content) {
        List<String> list = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '\'' || c == '"') {
                inQuote = !inQuote;
                sb.append(c);
            } else if (c == ',' && !inQuote) {
                list.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        list.add(sb.toString());
        return list.toArray(new String[0]);
    }

    /** 将行列表转为 DefaultTableModel 所需的二维数组。 */
    private Object[][] toMatrix(List<Object[]> rows) {
        Object[][] matrix = new Object[rows.size()][];
        for (int i = 0; i < rows.size(); i++) {
            matrix[i] = rows.get(i);
        }
        return matrix;
    }

    private void updateRowCount(int count) {
        rowCountLabel.setText("共 " + count + " 行");
    }

    /** 追加一条日志并自动滚动到底部。 */
    private void log(String message) {
        logArea.append(message + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    // ====================== 入口 ======================

    public static void main(String[] args) {
        // 在 EDT 中启动界面，保证 Swing 线程安全
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignore) {
                // 系统 L&F 不可用时使用默认
            }
            new SwingDemo().setVisible(true);
        });
    }
}
