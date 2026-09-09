package com.sqxdl.executor;

import com.sqxdl.executor.storage.StorageResult;

import java.util.List;

/**
 * 执行器（D 组）。
 * 职责：按结果类型（数据集/行数/错误）格式化输出，供 CLI 展示执行结果。
 * 只做展示，不感知存储协议与执行细节（执行统一由 {@link SqlEngine} 门面完成）。
 */
public class Executor {

    /**
     * 渲染执行结果；任何失败都以错误文本呈现，不抛出异常。
     *
     * @param result 引擎返回的统一结果
     */
    public void render(StorageResult result) {
        switch (result.getType()) {
            case RESULTSET -> printResultSet(result);
            case ROWCOUNT -> System.out.println("执行成功，受影响行数: " + result.getRowsAffected());
            case ERROR -> System.err.println("错误[" + result.getErrorCode() + "]: " + result.getErrorMessage());
        }
    }

    /** 以简单文本表格输出查询结果 */
    private void printResultSet(StorageResult result) {
        List<String> columns = result.getColumns();
        List<List<Object>> rows = result.getRows();
        if (rows.isEmpty()) {
            System.out.println("查询完成，共 0 行");
            return;
        }
        // 计算每列显示宽度（取列名与各行该列文本的最大长度）
        int[] widths = new int[columns.size()];
        for (int i = 0; i < widths.length; i++) {
            widths[i] = columns.get(i).length();
        }
        for (List<Object> row : rows) {
            for (int i = 0; i < widths.length && i < row.size(); i++) {
                widths[i] = Math.max(widths[i], String.valueOf(row.get(i)).length());
            }
        }
        printRow(columns, widths);
        printSeparator(widths);
        for (List<Object> row : rows) {
            printRow(row.stream().map(String::valueOf).toList(), widths);
        }
        System.out.println("查询完成，共 " + rows.size() + " 行");
    }

    private void printRow(List<String> cells, int[] widths) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < widths.length; i++) {
            String cell = i < cells.size() ? cells.get(i) : "";
            sb.append(cell).append(" ".repeat(Math.max(0, widths[i] - cell.length()))).append("  ");
        }
        System.out.println(sb.toString().stripTrailing());
    }

    private void printSeparator(int[] widths) {
        StringBuilder sb = new StringBuilder();
        for (int width : widths) {
            sb.append("-".repeat(width)).append("  ");
        }
        System.out.println(sb.toString().stripTrailing());
    }
}
