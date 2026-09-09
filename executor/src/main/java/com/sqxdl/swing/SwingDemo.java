package com.sqxdl.swing;

import com.sqxdl.executor.SqlEngine;
import com.sqxdl.executor.storage.StorageResult;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.List;

/**
 * SQXDL 数据库管理系统图形界面（纯 Swing）。
 * 界面只负责展示与交互，SQL 一律交给 {@link SqlEngine} 执行——
 * 与 CLI（Main）共用同一套 解析 -> 语义分析 -> 计划生成 流水线；
 * 引擎以 LOCAL 模式运行，数据保存在 JVM 内，保证演示效果。
 */
public class SwingDemo extends JFrame {

    /** SQL 执行引擎：LOCAL 模式，数据保存在内存；解析与语义校验复用真实流水线 */
    private final SqlEngine engine = new SqlEngine(SqlEngine.Mode.LOCAL);

    // ====================== UI 组件 ======================

    private JList<String> tableList;
    private DefaultTableModel tableModel;
    private JTable dataTable;
    private JLabel rowCountLabel;
    private JTextArea sqlArea;
    private JTextArea logArea;
    private boolean connected = false;

    public SwingDemo() {
        initUI();
        log("系统就绪（本地演示模式，示例数据保存在内存）");
        log("点击「连接」开始使用 SQXDL Database Manager v1.0");
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
        // 左侧：表列表（表名来自引擎的数据字典）
        tableList = new JList<>(engine.tableNames().toArray(new String[0]));
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
        // SQL 输入区：回车直接执行（与 CLI 一致），Shift+Enter 换行
        sqlArea = new JTextArea(3, 60);
        sqlArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        sqlArea.setTabSize(4);
        bindEnterToExecute();
        JScrollPane sqlScroll = new JScrollPane(sqlArea);
        sqlScroll.setBorder(BorderFactory.createTitledBorder("SQL 执行区（Enter 执行 / Shift+Enter 换行）"));

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

        execBtn.addActionListener(e -> executeFromInput());
        clearBtn.addActionListener(e -> sqlArea.setText(""));
        return bottom;
    }

    // ====================== 业务逻辑 ======================

    /** 把输入框的 Enter 绑定为执行（与 CLI 一致），Shift+Enter 保留换行。 */
    private void bindEnterToExecute() {
        InputMap inputMap = sqlArea.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap actionMap = sqlArea.getActionMap();
        inputMap.put(KeyStroke.getKeyStroke("ENTER"), "execute-sql");
        actionMap.put("execute-sql", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                executeFromInput();
            }
        });
    }

    /** 输入框回车 / 点击执行按钮：回显 SQL、执行并清空输入框（模拟 CLI 一轮交互）。 */
    private void executeFromInput() {
        String sql = sqlArea.getText().trim();
        if (sql.isEmpty()) {
            log("⚠ SQL 语句为空");
            return;
        }
        log("sqxdl> " + sql);
        executeSql(sql);
        sqlArea.setText("");
    }

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

    /** 左侧点击表：走 SELECT * FROM 表 的完整流水线加载该表数据。 */
    private void showTable(String tableName) {
        executeSql("SELECT * FROM " + tableName);
    }

    /** 执行单条 SQL：调用引擎流水线，渲染结果、回退提示与耗时。 */
    private void executeSql(String sql) {
        executeSql(sql, true);
    }

    /**
     * 执行单条 SQL 并渲染结果。
     *
     * @param logTiming 是否输出耗时日志（内部刷新当前表时关闭，避免日志重复）
     */
    private void executeSql(String sql, boolean logTiming) {
        String trimmed = sql.trim();
        if (trimmed.isEmpty()) {
            log("⚠ SQL 语句为空");
            return;
        }

        long start = System.currentTimeMillis();
        StorageResult result = engine.execute(trimmed);
        long elapsed = System.currentTimeMillis() - start;

        render(result);
        if (engine.getFallbackReason() != null) {
            log("ℹ " + engine.getFallbackReason());
        }
        if (logTiming) {
            log("⏱ 执行耗时: " + elapsed + "ms");
        }
    }

    /** 按结果类型分发渲染：查询结果进表格，行数/错误进日志。 */
    private void render(StorageResult result) {
        switch (result.getType()) {
            case RESULTSET -> showResultSet(result);
            case ROWCOUNT -> {
                long affected = result.getRowsAffected();
                log(affected > 0
                        ? "✅ 执行成功，" + affected + " 行受影响"
                        : "✅ 执行成功");
                // 写操作成功后刷新当前表，让界面立即反映数据变化
                String current = tableList.getSelectedValue();
                if (current != null) {
                    executeSql("SELECT * FROM " + current, false);
                }
            }
            case ERROR -> log("❌ [" + result.getErrorCode() + "] " + result.getErrorMessage());
        }
    }

    /** 把查询结果渲染到右侧表格。 */
    private void showResultSet(StorageResult result) {
        tableModel.setDataVector(toMatrix(result.getRows()), result.getColumns().toArray());
        updateRowCount(result.getRows().size());
        log("✅ 查询成功，返回 " + result.getRows().size() + " 行");
    }

    // ====================== 工具方法 ======================

    /** 查询行列表转为表格模型所需二维数组。 */
    private Object[][] toMatrix(List<List<Object>> rows) {
        Object[][] matrix = new Object[rows.size()][];
        for (int i = 0; i < rows.size(); i++) {
            matrix[i] = rows.get(i).toArray();
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
