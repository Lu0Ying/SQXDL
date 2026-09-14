package com.sqxdl.parser;

import java.util.List;
import java.util.Map;

/**
 * 规范 SQL 还原器（Pretty Printer）：把 AST 还原成规范 SQL 文本。
 * <p>
 * 用途：
 * <ul>
 *   <li>演示：输入乱 SQL → 输出规范化 SQL，直观展示解析结果；</li>
 *   <li>验证：round-trip（parse → print → parse）可验证 AST 结构完整性；</li>
 *   <li>差分测试：还原出的 SQL 直接交给工业级引擎（如 H2）执行，交叉验证正确性。</li>
 * </ul>
 * 设计约定：表达式统一加括号（如 {@code (a = 1)}），保证还原文本的运算符优先级
 * 与 AST 结构完全一致，无需再按优先级判断括号归属。
 */
public final class SqlPrinter {

    private SqlPrinter() {
    }

    /** 还原单个 AST 节点为规范 SQL（语句级或表达式级均可） */
    public static String print(ASTNode node) {
        if (node instanceof ASTNode.SelectStmt) {
            return select((ASTNode.SelectStmt) node);
        }
        if (node instanceof ASTNode.InsertStmt) {
            return insert((ASTNode.InsertStmt) node);
        }
        if (node instanceof ASTNode.UpdateStmt) {
            return update((ASTNode.UpdateStmt) node);
        }
        if (node instanceof ASTNode.DeleteStmt) {
            return delete((ASTNode.DeleteStmt) node);
        }
        if (node instanceof ASTNode.CreateTableStmt) {
            return createTable((ASTNode.CreateTableStmt) node);
        }
        if (node instanceof ASTNode.ShowStmt) {
            return show((ASTNode.ShowStmt) node);
        }
        if (node instanceof ASTNode.DropTableStmt) {
            return dropTable((ASTNode.DropTableStmt) node);
        }
        // 表达式节点兜底：交给表达式还原
        return expr(node);
    }

    /** SELECT [列清单] FROM 表 [JOIN 表 ON 条件] [WHERE 条件] [GROUP BY 列] [ORDER BY 列 DIR] */
    private static String select(ASTNode.SelectStmt s) {
        StringBuilder sb = new StringBuilder("SELECT ");
        sb.append(String.join(", ", s.getSelectList()));
        sb.append(" FROM ").append(s.getTableName());
        for (ASTNode.SelectStmt.JoinClause j : s.getJoins()) {
            sb.append(" JOIN ").append(j.getTableName())
                    .append(" ON ").append(expr(j.getOnCond()));
        }
        if (s.getWhereCond() != null) {
            sb.append(" WHERE ").append(expr(s.getWhereCond()));
        }
        if (!s.getGroupBy().isEmpty()) {
            sb.append(" GROUP BY ").append(String.join(", ", s.getGroupBy()));
        }
        if (!s.getOrderBy().isEmpty()) {
            sb.append(" ORDER BY ");
            for (int i = 0; i < s.getOrderBy().size(); i++) {
                ASTNode.SelectStmt.OrderItem item = s.getOrderBy().get(i);
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(item.getColumn()).append(' ').append(item.getDirection());
            }
        }
        return sb.toString();
    }

    /** INSERT INTO 表 [(列清单)] VALUES (值清单) */
    private static String insert(ASTNode.InsertStmt s) {
        StringBuilder sb = new StringBuilder("INSERT INTO ").append(s.getTableName());
        if (!s.getColumns().isEmpty()) {
            sb.append(" (").append(String.join(", ", s.getColumns())).append(')');
        }
        sb.append(" VALUES (");
        List<ASTNode.LiteralExpr> values = s.getValues();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(expr(values.get(i)));
        }
        return sb.append(')').toString();
    }

    /** UPDATE 表 SET 列 = 值, ... [WHERE 条件] */
    private static String update(ASTNode.UpdateStmt s) {
        StringBuilder sb = new StringBuilder("UPDATE ").append(s.getTableName()).append(" SET ");
        boolean first = true;
        for (Map.Entry<String, ASTNode.LiteralExpr> e : s.getAssignments().entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            sb.append(e.getKey()).append(" = ").append(expr(e.getValue()));
            first = false;
        }
        if (s.getWhereCond() != null) {
            sb.append(" WHERE ").append(expr(s.getWhereCond()));
        }
        return sb.toString();
    }

    /** DELETE FROM 表 [WHERE 条件] */
    private static String delete(ASTNode.DeleteStmt s) {
        StringBuilder sb = new StringBuilder("DELETE FROM ").append(s.getTableName());
        if (s.getWhereCond() != null) {
            sb.append(" WHERE ").append(expr(s.getWhereCond()));
        }
        return sb.toString();
    }

    /** CREATE TABLE 表 (列 类型, ...) */
    private static String createTable(ASTNode.CreateTableStmt s) {
        StringBuilder sb = new StringBuilder("CREATE TABLE ").append(s.getTableName()).append(" (");
        List<ASTNode.CreateTableStmt.ColumnDef> columns = s.getColumns();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            ASTNode.CreateTableStmt.ColumnDef c = columns.get(i);
            sb.append(c.getName()).append(' ').append(c.getType());
        }
        return sb.append(')').toString();
    }

    /** SHOW TABLES / SHOW TABLE 表 */
    private static String show(ASTNode.ShowStmt s) {
        return s.getTarget().equals("TABLES")
                ? "SHOW TABLES"
                : "SHOW TABLE " + s.getTableName();
    }

    /** DROP TABLE 表 */
    private static String dropTable(ASTNode.DropTableStmt s) {
        return "DROP TABLE " + s.getTableName();
    }

    /** 表达式还原：二元/一元/叶子统一加括号或引号，保证与 AST 严格对应 */
    private static String expr(ASTNode e) {
        if (e instanceof ASTNode.LiteralExpr) {
            ASTNode.LiteralExpr lit = (ASTNode.LiteralExpr) e;
            switch (lit.getKind()) {
                case NUMBER:
                case BOOLEAN:
                    return lit.getValue();
                default:
                    // STRING：内部单引号按 SQL 约定转义为 ''
                    return "'" + lit.getValue().replace("'", "''") + "'";
            }
        }
        if (e instanceof ASTNode.IdentifierExpr) {
            return ((ASTNode.IdentifierExpr) e).getName();
        }
        if (e instanceof ASTNode.UnaryExpr) {
            ASTNode.UnaryExpr u = (ASTNode.UnaryExpr) e;
            return u.getOp() + " " + expr(u.getOperand());
        }
        if (e instanceof ASTNode.BinaryExpr) {
            ASTNode.BinaryExpr b = (ASTNode.BinaryExpr) e;
            return "(" + expr(b.getLeft()) + " " + b.getOp() + " " + expr(b.getRight()) + ")";
        }
        throw new IllegalArgumentException("不支持的表达式节点: " + e.getClass().getName());
    }
}
