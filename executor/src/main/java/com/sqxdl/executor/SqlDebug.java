package com.sqxdl.executor;

import com.sqxdl.parser.ASTNode;
import com.sqxdl.parser.Lexer;
import com.sqxdl.parser.SqxdlException;
import com.sqxdl.parser.Token;
import com.sqxdl.semantic.PlanNode;

/**
 * DEBUG 输出工具（D 组）：由 {@link SqlEngine} 在流水线各阶段调用，打印
 * Token 流（A 组 Lexer 产物）、AST 树（A 组 Parser 产物）、语义检查结果
 * （B 组 SemanticAnalyzer）与优化前后 Plan 树（B 组 PlanGenerator 产物），
 * 以及端到端耗时分解。指导书要求的"打印 Token 流 / AST / Plan 树"由此实现。
 * <p>开关：默认取 JVM 参数 {@code -Dsqxdl.debug=true}；CLI 中也可随时输入
 * {@code debug} 命令切换，无需重启程序。仅用于调试与报告截图，关闭时零输出。
 */
final class SqlDebug {

    /** 开关：默认由 JVM 参数 -Dsqxdl.debug=true 决定；REPL 内可随时用 debug 命令切换 */
    static boolean ENABLED = Boolean.getBoolean("sqxdl.debug");

    private SqlDebug() {
    }

    /**
     * 打印单条语句的端到端耗时分解（性能检查点）。
     * AUTO 模式细分存储侧四段（序列化/发送/核心执行+回传/响应解析，取自
     * StorageClient 最近一次调用的采样）；LOCAL 与回退模拟只统计"本地模拟"一段。
     *
     * @param outcome        结果类型名（ROWCOUNT/RESULTSET/ERROR 等）
     * @param totalNanos     端到端总耗时（纳秒）
     * @param normalizeNanos 规范化段耗时（纳秒）
     * @param parseNanos     词法+语法解析段耗时（纳秒）
     * @param semanticNanos  语义分析段耗时（纳秒）
     * @param planNanos      计划生成段耗时（纳秒）
     * @param storageNanos   存储执行段总耗时（纳秒；LOCAL/模拟时展示用）
     * @param client         存储客户端（AUTO 路径传实例，展示存储侧细分；LOCAL/模拟传 null）
     */
    static void printTiming(String outcome, long totalNanos, long normalizeNanos, long parseNanos,
                            long semanticNanos, long planNanos, long storageNanos,
                            com.sqxdl.executor.storage.StorageClient client) {
        System.out.printf(java.util.Locale.ROOT, "---- 耗时分解（合计 %.2f ms，%s）----%n",
                totalNanos / 1e6, outcome);
        System.out.printf(java.util.Locale.ROOT, "  规范化 %.2f | 词法+语法 %.2f | 语义 %.2f | 计划生成 %.2f ms%n",
                normalizeNanos / 1e6, parseNanos / 1e6, semanticNanos / 1e6, planNanos / 1e6);
        if (client != null) {
            System.out.printf(java.util.Locale.ROOT,
                    "  存储: 序列化 %.2f | 发送 %.2f | 核心执行+回传 %.2f | 响应解析 %.2f ms%n",
                    client.lastSerializeNanos() / 1e6, client.lastSendNanos() / 1e6,
                    client.lastWaitNanos() / 1e6, client.lastParseNanos() / 1e6);
        } else {
            System.out.printf(java.util.Locale.ROOT, "  本地模拟执行 %.2f ms%n", storageNanos / 1e6);
        }
    }

    /** 打印 Token 流：独立 Lexer 实例遍历到 EOF，不影响交给 Parser 的那份 */
    static void printTokens(String sql) {
        System.out.println("---- Token 流 ----");
        try {
            Lexer lexer = new Lexer(sql);
            for (Token token = lexer.nextToken();
                    token.getType() != Token.Type.EOF;
                    token = lexer.nextToken()) {
                System.out.println(token);
            }
        } catch (SqxdlException e) {
            // 非法 SQL 时打印到出错位置即止；主流程的 SYNTAX_ERROR 输出照常进行
            System.out.println("(词法错误，Token 流在此截断: " + e.getMessage() + ")");
        }
    }

    /** 打印 AST 树形结构：语句节点逐字段，WHERE/ON 条件表达式递归缩进 */
    static void printAst(ASTNode ast) {
        System.out.println("---- AST ----");
        StringBuilder sb = new StringBuilder();
        formatStmt(sb, ast, 0);
        System.out.print(sb);
    }

    /** 打印语义检查结果（analyze 抛异常时走不到这里，错误由主流程输出） */
    static void printSemantic() {
        System.out.println("---- 语义检查 ----");
        System.out.println("通过");
    }

    /** 打印 Plan 树（复用 B 组 PlanNode.formatPlan 的树形格式） */
    static void printPlan(PlanNode plan, String title) {
        System.out.println("---- " + title + " ----");
        System.out.print(PlanNode.formatPlan(plan));
    }

    // ========== AST 树形格式化 ==========

    /** 语句节点：字段单行展示，条件表达式递归展开 */
    private static void formatStmt(StringBuilder sb, ASTNode node, int depth) {
        if (node == null) {
            sb.append(indent(depth)).append("(null)\n");
            return;
        }
        String pad = indent(depth);
        if (node instanceof ASTNode.SelectStmt stmt) {
            sb.append(pad).append("SelectStmt\n");
            sb.append(pad).append("  table: ").append(stmt.getTableName()).append('\n');
            sb.append(pad).append("  columns: ").append(stmt.getSelectList()).append('\n');
            for (ASTNode.SelectStmt.JoinClause join : stmt.getJoins()) {
                sb.append(pad).append("  join: ").append(join.getTableName()).append('\n');
                sb.append(pad).append("    on:\n");
                formatExpr(sb, join.getOnCond(), depth + 3);
            }
            if (stmt.getWhereCond() != null) {
                sb.append(pad).append("  where:\n");
                formatExpr(sb, stmt.getWhereCond(), depth + 2);
            }
            sb.append(pad).append("  groupBy: ").append(stmt.getGroupBy()).append('\n');
            sb.append(pad).append("  orderBy: ").append(stmt.getOrderBy()).append('\n');
        } else if (node instanceof ASTNode.InsertStmt stmt) {
            sb.append(pad).append("InsertStmt\n");
            sb.append(pad).append("  table: ").append(stmt.getTableName()).append('\n');
            sb.append(pad).append("  columns: ").append(stmt.getColumns()).append('\n');
            sb.append(pad).append("  values: ").append(stmt.getValues()).append('\n');
        } else if (node instanceof ASTNode.UpdateStmt stmt) {
            sb.append(pad).append("UpdateStmt\n");
            sb.append(pad).append("  table: ").append(stmt.getTableName()).append('\n');
            sb.append(pad).append("  set: ").append(stmt.getAssignments()).append('\n');
            if (stmt.getWhereCond() != null) {
                sb.append(pad).append("  where:\n");
                formatExpr(sb, stmt.getWhereCond(), depth + 2);
            }
        } else if (node instanceof ASTNode.DeleteStmt stmt) {
            sb.append(pad).append("DeleteStmt\n");
            sb.append(pad).append("  table: ").append(stmt.getTableName()).append('\n');
            if (stmt.getWhereCond() != null) {
                sb.append(pad).append("  where:\n");
                formatExpr(sb, stmt.getWhereCond(), depth + 2);
            }
        } else if (node instanceof ASTNode.CreateTableStmt stmt) {
            sb.append(pad).append("CreateTableStmt\n");
            sb.append(pad).append("  table: ").append(stmt.getTableName()).append('\n');
            sb.append(pad).append("  columns: ").append(stmt.getColumns()).append('\n');
        } else if (node instanceof ASTNode.ShowStmt stmt) {
            sb.append(pad).append("ShowStmt\n");
            sb.append(pad).append("  target: ").append(stmt.getTarget()).append('\n');
            if (stmt.getTableName() != null) {
                sb.append(pad).append("  table: ").append(stmt.getTableName()).append('\n');
            }
        } else if (node instanceof ASTNode.DropTableStmt stmt) {
            sb.append(pad).append("DropTableStmt\n");
            sb.append(pad).append("  table: ").append(stmt.getTableName()).append('\n');
        } else {
            sb.append(pad).append(node).append('\n');
        }
    }

    /** 表达式节点：二元/一元递归展开，叶子直接展示值 */
    private static void formatExpr(StringBuilder sb, ASTNode expr, int depth) {
        if (expr == null) {
            sb.append(indent(depth)).append("(null)\n");
            return;
        }
        String pad = indent(depth);
        if (expr instanceof ASTNode.BinaryExpr binary) {
            sb.append(pad).append("BinaryExpr (").append(binary.getOp()).append(")\n");
            formatExpr(sb, binary.getLeft(), depth + 1);
            formatExpr(sb, binary.getRight(), depth + 1);
        } else if (expr instanceof ASTNode.UnaryExpr unary) {
            sb.append(pad).append("UnaryExpr (").append(unary.getOp()).append(")\n");
            formatExpr(sb, unary.getOperand(), depth + 1);
        } else if (expr instanceof ASTNode.LiteralExpr literal) {
            sb.append(pad).append("Literal: ").append(literal.getValue())
                    .append(" (").append(literal.getKind()).append(")\n");
        } else if (expr instanceof ASTNode.IdentifierExpr id) {
            sb.append(pad).append("Identifier: ").append(id.getName()).append('\n');
        } else {
            sb.append(pad).append(expr).append('\n');
        }
    }

    /** 生成两级空格的缩进 */
    private static String indent(int depth) {
        return "  ".repeat(depth);
    }
}
