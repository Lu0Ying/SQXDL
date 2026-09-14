package com.sqxdl.executor;

import com.sqxdl.parser.ASTNode;
import com.sqxdl.parser.Lexer;
import com.sqxdl.parser.SqxdlException;
import com.sqxdl.parser.Token;
import com.sqxdl.semantic.PlanNode;

/**
 * DEBUG 输出工具：由 {@link SqlEngine} 在流水线各阶段调用，打印 Token 流、
 * AST 树、语义检查结果与优化前后 Plan 树。开关默认取 JVM 参数 -Dsqxdl.debug=true；
 * CLI 中也可随时输入 debug 命令切换，无需重启程序。
 */
final class SqlDebug {

    /** 开关：默认由 JVM 参数 -Dsqxdl.debug=true 决定；REPL 内可随时用 debug 命令切换 */
    static boolean ENABLED = Boolean.getBoolean("sqxdl.debug");

    private SqlDebug() {
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
