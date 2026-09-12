package com.sqxdl.parser;

import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * 交互式演示入口（A 组自测用）：
 * 逐行输入 SQL，输出该语句的 Token 流（含类别与是否 KEYWORD 标注）和 AST 树形结构。
 *
 * <p>用法：直接在 IDE 中运行 main 方法，或在项目根目录执行
 * {@code mvn -pl parser compile} 后运行 {@code java -cp parser/target/classes com.sqxdl.parser.ParserDemo}。
 * 空行或 Ctrl+D 退出。
 */
public class ParserDemo {

    public static void main(String[] args) {
        System.out.println("===== SQL 词法 / 语法分析演示 =====");
        System.out.println("输入 SQL 查看 Token 流与 AST（空行退出）：");
        Scanner in = new Scanner(System.in);
        while (true) {
            System.out.print("\nSQL> ");
            String sql;
            if (in.hasNextLine()) {
                sql = in.nextLine().trim();
            } else {
                break;
            }
            if (sql.isEmpty()) {
                break;
            }
            System.out.println("输入: " + sql);
            printTokens(sql);
            printAst(sql);
        }
        System.out.println("演示结束");
    }

    /** 逐个取 Token 并打印：序号、类别、原文、行列，KEYWORD 额外标注；末尾统一输出拼写提示（仅一次） */
    private static void printTokens(String sql) {
        System.out.println("\n--- Token 流 ---");
        Lexer lexer = new Lexer(sql);
        int i = 1;
        while (true) {
            Token t = lexer.nextToken();
            if (t.getType() == Token.Type.EOF) {
                break;
            }
            String isKw = t.getType() == Token.Type.KEYWORD ? "  [KEYWORD]" : "";
            System.out.printf("[%d] %-10s '%s'  @%d:%d%s%n",
                    i++, t.getType(), t.getLexeme(), t.getLine(), t.getCol(), isKw);
        }
        // 拼写提示从该 Lexer 一次性取回打印；AST 解析用的 Lexer 不再重复输出
        for (String w : lexer.getSpellWarnings()) {
            System.out.println(w);
        }
    }

    /** 解析全部语句并打印 AST 树；有语法错误则一并输出 */
    private static void printAst(String sql) {
        System.out.println("\n--- AST ---");
        Parser parser = new Parser(new Lexer(sql));
        List<ASTNode> stmts = parser.parseAll();
        for (ASTNode stmt : stmts) {
            printStmt(stmt, "");
            System.out.println();
        }
        if (!parser.getErrors().isEmpty()) {
            System.out.println("语法错误:");
            for (SqxdlException e : parser.getErrors()) {
                System.out.println("  " + e.getMessage());
            }
        }
    }

    /** 打印语句级节点：节点名 + 关键字段，再递归子表达式 */
    private static void printStmt(ASTNode node, String indent) {
        if (node instanceof ASTNode.SelectStmt s) {
            System.out.println(indent + "SelectStmt");
            System.out.println(indent + "  table : " + s.getTableName());
            System.out.println(indent + "  columns: " + s.getSelectList());
            for (ASTNode.SelectStmt.JoinClause j : s.getJoins()) {
                System.out.println(indent + "  join  : " + j.getTableName());
                System.out.println(indent + "    on :");
                printNode(j.getOnCond(), indent + "      ");
            }
            if (s.getWhereCond() != null) {
                System.out.println(indent + "  where:");
                printNode(s.getWhereCond(), indent + "    ");
            }
            if (!s.getGroupBy().isEmpty()) {
                System.out.println(indent + "  groupBy: " + s.getGroupBy());
            }
            if (!s.getOrderBy().isEmpty()) {
                System.out.println(indent + "  orderBy: " + s.getOrderBy());
            }
        } else if (node instanceof ASTNode.InsertStmt i) {
            System.out.println(indent + "InsertStmt");
            System.out.println(indent + "  table : " + i.getTableName());
            System.out.println(indent + "  columns: " + i.getColumns());
            System.out.println(indent + "  values: " + i.getValues());
        } else if (node instanceof ASTNode.UpdateStmt u) {
            System.out.println(indent + "UpdateStmt");
            System.out.println(indent + "  table : " + u.getTableName());
            System.out.println(indent + "  set   :");
            for (Map.Entry<String, ASTNode.LiteralExpr> e : u.getAssignments().entrySet()) {
                System.out.println(indent + "    " + e.getKey() + " = " + e.getValue());
            }
            if (u.getWhereCond() != null) {
                System.out.println(indent + "  where:");
                printNode(u.getWhereCond(), indent + "    ");
            }
        } else if (node instanceof ASTNode.DeleteStmt d) {
            System.out.println(indent + "DeleteStmt");
            System.out.println(indent + "  table : " + d.getTableName());
            if (d.getWhereCond() != null) {
                System.out.println(indent + "  where:");
                printNode(d.getWhereCond(), indent + "    ");
            }
        } else if (node instanceof ASTNode.CreateTableStmt c) {
            System.out.println(indent + "CreateTableStmt");
            System.out.println(indent + "  table : " + c.getTableName());
            System.out.println(indent + "  columns:");
            for (ASTNode.CreateTableStmt.ColumnDef col : c.getColumns()) {
                System.out.println(indent + "    " + col.getName() + " " + col.getType());
            }
        } else if (node instanceof ASTNode.ShowStmt s) {
            System.out.println(indent + "ShowStmt");
            System.out.println(indent + "  target : " + s.getTarget());
            if (s.getTableName() != null) {
                System.out.println(indent + "  table  : " + s.getTableName());
            }
        } else if (node instanceof ASTNode.DropTableStmt d) {
            System.out.println(indent + "DropTableStmt");
            System.out.println(indent + "  table : " + d.getTableName());
        } else {
            // 兜底：直接打印 toString
            System.out.println(indent + node);
        }
    }

    /** 打印表达式节点：二元/一元/字面量/标识符，树形缩进 */
    private static void printNode(ASTNode node, String indent) {
        if (node instanceof ASTNode.BinaryExpr b) {
            System.out.println(indent + "BinaryExpr(op=" + b.getOp() + ")");
            System.out.println(indent + "  left:");
            printNode(b.getLeft(), indent + "    ");
            System.out.println(indent + "  right:");
            printNode(b.getRight(), indent + "    ");
        } else if (node instanceof ASTNode.UnaryExpr u) {
            System.out.println(indent + "UnaryExpr(op=" + u.getOp() + ")");
            System.out.println(indent + "  operand:");
            printNode(u.getOperand(), indent + "    ");
        } else if (node instanceof ASTNode.LiteralExpr l) {
            System.out.println(indent + "LiteralExpr(kind=" + l.getKind() + ", value=" + l.getValue() + ")");
        } else if (node instanceof ASTNode.IdentifierExpr id) {
            System.out.println(indent + "IdentifierExpr(name=" + id.getName() + ")");
        } else {
            System.out.println(indent + node);
        }
    }
}
