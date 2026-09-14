package com.sqxdl.swing;

import com.sqxdl.executor.CommandHistory;
import com.sqxdl.executor.SqlEngine;
import com.sqxdl.executor.storage.StorageResult;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultEditorKit;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;

/**
 * SQXDL 数据库管理系统图形界面（纯 Swing）。
 * 界面只负责展示与交互，SQL 一律交给 {@link SqlEngine} 执行——
 * 与 CLI（Main）共用同一套 解析 -> 语义分析 -> 计划生成 流水线；
 * 引擎以 AUTO 模式运行：数据写入存储核心并落盘持久化，重启后不还原；
 * 存储核心不可用时自动回退内置示例数据模拟。
 */
public class SwingDemo extends JFrame {

    /** SQL 执行引擎：AUTO 模式优先存储核心（持久化），不可用回退本地模拟 */
    private final SqlEngine engine = new SqlEngine(SqlEngine.Mode.AUTO);

    // ====================== UI 组件 ======================

    private JList<String> tableList;
    private DefaultTableModel tableModel;
    private JTable dataTable;
    private JLabel rowCountLabel;
    private JTextArea sqlArea;
    private JTextArea logArea;
    private boolean connected = false;

    /** 输入历史（↑/↓ 翻阅）；historyIndex 为 -1 表示停留在最新草稿位置 */
    private final CommandHistory inputHistory = new CommandHistory();
    private int historyIndex = -1;
    private String historyDraft = "";

    public SwingDemo() {
        initUI();
        if (engine.isStorageAvailable()) {
            log("✅ 已连接存储核心，数据持久化于 \\SQXDL\\data\\，重启后数据保留");
        } else {
            log("⚠ 未检测到存储核心（storage_core.exe），当前使用本地模拟数据（重启后不保留）");
        }
        refreshTableList();
        log("点击「连接」开始使用 SQXDL Database Manager v1.0");
    }

    /** 构建全部 UI 组件并布局。 */
    private void initUI() {
        setTitle("SQXDL Database Manager");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        // 关窗时结束存储服务会话（协议 exit 正常落盘退出）
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                engine.close();
            }
        });
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

        JLabel title = new JLabel("SQXDL Database Manager");
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
        listScroll.setBorder(BorderFactory.createTitledBorder("📂表列表"));
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
        tableScroll.setBorder(BorderFactory.createTitledBorder("数据浏览"));

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
        bindHistoryNavigation();
        JScrollPane sqlScroll = new JScrollPane(sqlArea);
        sqlScroll.setBorder(BorderFactory.createTitledBorder("SQL 执行区（Enter 执行 / Shift+Enter 换行 / ↑↓ 历史）"));

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

    /**
     * 绑定 ↑/↓ 翻阅输入历史。
     * 仅当光标位于首行（↑）或末行（↓）时才切换历史，
     * 其余情况回退为默认光标移动，保证多行 SQL 的编辑体验不受影响。
     */
    private void bindHistoryNavigation() {
        InputMap inputMap = sqlArea.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap actionMap = sqlArea.getActionMap();
        // 取出默认的光标上下移动动作，未触发历史翻阅时委托给它
        Action defaultUp = actionMap.get(DefaultEditorKit.upAction);
        Action defaultDown = actionMap.get(DefaultEditorKit.downAction);
        inputMap.put(KeyStroke.getKeyStroke("UP"), "history-prev");
        inputMap.put(KeyStroke.getKeyStroke("DOWN"), "history-next");
        actionMap.put("history-prev", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                navigateHistory(-1, defaultUp, e);
            }
        });
        actionMap.put("history-next", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                navigateHistory(1, defaultDown, e);
            }
        });
    }

    /**
     * 历史翻阅：↑ 向旧翻一条、↓ 向新翻一条（翻过最新一条时恢复执行前的草稿）。
     *
     * @param delta    -1 表示 ↑（向旧），1 表示 ↓（向新）
     * @param fallback 未触发翻阅时的默认光标动作（可为 null）
     */
    private void navigateHistory(int delta, Action fallback, ActionEvent event) {
        boolean atEdge = delta < 0 ? caretAtFirstLine() : caretAtLastLine();
        if (inputHistory.size() == 0 || !atEdge) {
            if (fallback != null) {
                fallback.actionPerformed(event);
            }
            return;
        }
        if (delta < 0) {
            if (historyIndex == -1) {
                // 从最新位置开始翻历史：先保存当前未执行的内容作为草稿
                historyDraft = sqlArea.getText();
                historyIndex = inputHistory.size() - 1;
            } else if (historyIndex > 0) {
                historyIndex--;
            }
        } else if (historyIndex != -1) {
            if (historyIndex < inputHistory.size() - 1) {
                historyIndex++;
            } else {
                historyIndex = -1;
            }
        }
        String text = historyIndex == -1 ? historyDraft : inputHistory.get(historyIndex);
        sqlArea.setText(text);
        sqlArea.setCaretPosition(text.length());
    }

    /** 光标是否在首行（光标之前不存在换行符）。 */
    private boolean caretAtFirstLine() {
        try {
            return sqlArea.getText(0, sqlArea.getCaretPosition()).indexOf('\n') < 0;
        } catch (BadLocationException e) {
            return true;
        }
    }

    /** 光标是否在末行（光标之后不存在换行符）。 */
    private boolean caretAtLastLine() {
        try {
            int caret = sqlArea.getCaretPosition();
            int rest = sqlArea.getDocument().getLength() - caret;
            return sqlArea.getText(caret, rest).indexOf('\n') < 0;
        } catch (BadLocationException e) {
            return true;
        }
    }

    /** 输入框回车 / 点击执行按钮：回显 SQL、执行并清空输入框（模拟 CLI 一轮交互）。 */
    private void executeFromInput() {
        String sql = sqlArea.getText().trim();
        if (sql.isEmpty()) {
            log("⚠ SQL 语句为空");
            return;
        }
        log("sqxdl> " + sql);
        // 记入历史并重置浏览状态：下次 ↑ 从刚执行的这条开始向前翻
        inputHistory.add(sql);
        historyIndex = -1;
        historyDraft = "";
        executeSql(sql);
        sqlArea.setText("");
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

        // A 组 Lexer 的拼写自动纠错提示（如 SELEC -> SELECT）
        for (String warning : engine.getSpellWarnings()) {
            log("⚠ " + warning);
        }
        render(result, trimmed);
        if (engine.getFallbackReason() != null) {
            log("ℹ " + engine.getFallbackReason());
        }
        if (logTiming) {
            log("⏱ 执行耗时: " + elapsed + "ms");
        }
    }

    /** 连接：标记连接状态并刷新左侧表列表。 */
    private void connect() {
        if (connected) {
            log("⚠ 已处于连接状态");
            return;
        }
        connected = true;
        log("✅ 已连接到 SQXDL 数据库，版本 v1.0");
        refreshTableList();
        log("📂 当前共 " + tableList.getModel().getSize() + " 张表");
    }

    /** 断开连接：结束存储服务会话（协议 exit 正常落盘）；下次执行自动重连。 */
    private void disconnect() {
        if (!connected) {
            log("⚠ 当前未连接");
            return;
        }
        connected = false;
        engine.close();
        log("🔌 已断开连接（存储服务已关闭，数据已落盘）");
    }

    /** 左侧点击表：走 SELECT * FROM 表 的完整流水线加载该表数据。 */
    private void showTable(String tableName) {
        executeSql("SELECT * FROM " + tableName + ";");
    }

    /** 执行单条 SQL：调用引擎流水线，渲染结果、回退提示与耗时。 */
    private void executeSql(String sql) {
        executeSql(sql, true);
    }

    /** 按结果类型分发渲染：查询结果进表格，行数/错误进日志。 */
    private void render(StorageResult result, String sql) {
        switch (result.getType()) {
            case RESULTSET -> showResultSet(result);
            case ROWCOUNT -> {
                long affected = result.getRowsAffected();
                log(affected > 0
                        ? "✅ 执行成功，" + affected + " 行受影响"
                        : "✅ 执行成功");
                // 建表成功后实时刷新左侧表列表
                if (sql.toLowerCase().startsWith("create table")) {
                    refreshTableList();
                }
                // 写操作成功后刷新当前表，让界面立即反映数据变化
                String current = tableList.getSelectedValue();
                if (current != null) {
                    executeSql("SELECT * FROM " + current + ";", false);
                }
            }
            case ERROR -> log("❌ [" + result.getErrorCode() + "] " + result.getErrorMessage());
        }
    }

    /** 用引擎数据字典的最新表名刷新左侧表列表。 */
    private void refreshTableList() {
        tableList.setListData(engine.tableNames().toArray(new String[0]));
    }

    /** 把查询结果渲染到右侧表格。 */
    private void showResultSet(StorageResult result) {
        tableModel.setDataVector(toMatrix(result.getRows()), result.getColumns().toArray());
        updateRowCount(result.getRows().size());
        log("查询成功，返回 " + result.getRows().size() + " 行");
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
