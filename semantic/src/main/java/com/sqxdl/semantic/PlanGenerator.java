package com.sqxdl.semantic;

import com.sqxdl.parser.ASTNode;
import com.sqxdl.parser.ASTNode.LiteralExpr;
import com.sqxdl.parser.ASTNode.LiteralExpr.Kind;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 执行计划生成器（B 组）。
 * 职责：将通过语义检查的 AST 转换为逻辑执行计划树（{@link PlanNode}），
 * 与执行器/存储适配层对接。
 *
 * 本类在生成计划时对 WHERE 条件做两层优化：
 *   1. 常量折叠：两边都是字面量时直接计算，如 2+3→5、1=1→TRUE
 *   2. 表达式化简：利用恒等律/零元/单位元简化，如 cond AND TRUE→cond
 * 恒真条件 → 跳过 Filter；恒假条件 → 保留 Filter(FALSE) 让执行器返回空集。
 */
public class PlanGenerator {

    private final CatalogImpl catalog;
    private SemanticAnalyzer analyzer;

    public PlanGenerator(CatalogImpl catalog) {
        this.catalog = catalog;
    }

    /**
     * 设置语义分析器，用于获取 SELECT * 展开后的列清单。
     * 设置后 PlanGenerator 会优先使用语义分析阶段已展开的结果，
     * 避免重复展开。不设置时退化为自行展开。
     *
     * @param analyzer 语义分析器实例
     */
    public void setAnalyzer(SemanticAnalyzer analyzer) {
        this.analyzer = analyzer;
    }

    /**
     * 根据 AST 生成逻辑执行计划。
     *
     * @param ast 语法树根节点
     * @return 计划树根节点
     */
    public PlanNode generate(ASTNode ast) {
        if (ast instanceof ASTNode.SelectStmt stmt) {
            return generateSelect(stmt);
        }
        if (ast instanceof ASTNode.InsertStmt stmt) {
            return new PlanNode.InsertPlan(stmt.getTableName(), stmt.getColumns(), stmt.getValues());
        }
        if (ast instanceof ASTNode.UpdateStmt stmt) {
            return new PlanNode.UpdatePlan(stmt.getTableName(), stmt.getAssignments(),
                    optimizeCondition(stmt.getWhereCond()));
        }
        if (ast instanceof ASTNode.DeleteStmt stmt) {
            return new PlanNode.DeletePlan(stmt.getTableName(),
                    optimizeCondition(stmt.getWhereCond()));
        }
        if (ast instanceof ASTNode.CreateTableStmt stmt) {
            // 列名清单 + 带类型的列定义（存储核心建表协议已升级为对象数组）
            List<String> columnNames = new ArrayList<>();
            List<CatalogImpl.ColumnInfo> columnDefs = new ArrayList<>();
            for (ASTNode.CreateTableStmt.ColumnDef def : stmt.getColumns()) {
                columnNames.add(def.getName());
                columnDefs.add(new CatalogImpl.ColumnInfo(def.getName(), toDataType(def.getType())));
            }
            return new PlanNode.CreateTablePlan(stmt.getTableName(), columnNames, columnDefs);
        }
        if (ast instanceof ASTNode.ShowStmt stmt) {
            return new PlanNode.ShowTablesPlan();
        }
        if (ast instanceof ASTNode.DropTableStmt stmt) {
            return new PlanNode.DropTablePlan(stmt.getTableName());
        }
        throw new IllegalArgumentException("不支持的语句类型: " + ast.getClass().getSimpleName());
    }

    /** 建表类型字符串 -> 数据字典类型（INT/BOOLEAN 之外的类型按 VARCHAR 处理） */
    private CatalogImpl.DataType toDataType(String typeName) {
        if ("INT".equalsIgnoreCase(typeName)) {
            return CatalogImpl.DataType.INT;
        }
        if ("BOOLEAN".equalsIgnoreCase(typeName)) {
            return CatalogImpl.DataType.BOOLEAN;
        }
        return CatalogImpl.DataType.VARCHAR;
    }

    // ========== SELECT 计划生成 ==========

    /**
     * 生成查询计划树：Project -> Filter(可选) -> SeqScan。
     * WHERE 条件经过优化后，恒真则跳过 Filter。
     */
    private PlanNode generateSelect(ASTNode.SelectStmt stmt) {
        PlanNode plan = new PlanNode.SeqScanPlan(stmt.getTableName());

        ASTNode whereCond = optimizeCondition(stmt.getWhereCond());
        if (whereCond != null) {
            plan = new PlanNode.FilterPlan(whereCond, plan);
        }

        return new PlanNode.ProjectPlan(expandColumns(stmt), plan);
    }

    /**
     * 获取 SELECT 列清单：优先使用语义分析阶段已展开的结果，
     * 否则自行展开 SELECT *。
     */
    private List<String> expandColumns(ASTNode.SelectStmt stmt) {
        // 优先使用语义分析器展开后的列清单
        if (analyzer != null) {
            List<String> expanded = analyzer.getExpandedColumns(stmt);
            if (expanded != null) {
                return expanded;
            }
        }
        // 退化为自行展开
        List<String> selectList = stmt.getSelectList();
        if (selectList.size() != 1 || !"*".equals(selectList.get(0))) {
            return selectList;
        }
        List<String> allColumns = catalog.getColumns(stmt.getTableName());
        return allColumns == null ? new ArrayList<>() : allColumns;
    }

    // ========== 条件优化入口 ==========

    /**
     * 优化 WHERE 条件表达式。
     * - 输入 null（无 WHERE）→ 返回 null
     * - 优化后为恒真 → 返回 null（表示无需 Filter）
     * - 优化后为其他 → 返回优化后的表达式
     */
    private ASTNode optimizeCondition(ASTNode cond) {
        if (cond == null) {
            return null;
        }
        ASTNode optimized = optimizeExpr(cond);
        if (isTrueLiteral(optimized)) {
            return null;
        }
        return optimized;
    }

    // ========== 表达式优化（递归） ==========

    /**
     * 递归优化表达式：先处理子节点，再做常量折叠和表达式化简。
     */
    private ASTNode optimizeExpr(ASTNode expr) {
        if (expr instanceof LiteralExpr || expr instanceof ASTNode.IdentifierExpr) {
            return expr;
        }
        if (!(expr instanceof ASTNode.BinaryExpr bin)) {
            return expr;
        }

        // 先递归优化子表达式
        ASTNode left = optimizeExpr(bin.getLeft());
        ASTNode right = optimizeExpr(bin.getRight());
        String op = bin.getOp();

        // 1. 常量折叠：两边都是字面量 → 直接计算
        if (left instanceof LiteralExpr && right instanceof LiteralExpr) {
            LiteralExpr folded = foldConstants(op, (LiteralExpr) left, (LiteralExpr) right, bin);
            if (folded != null) {
                return folded;
            }
        }

        // 2. 逻辑表达式化简（AND/OR 与常量的短路）
        ASTNode simplified = simplifyLogical(op, left, right, bin);
        if (simplified != null) {
            return simplified;
        }

        // 3. 算术恒等式化简（x+0→x, x*1→x 等）
        simplified = simplifyArithmetic(op, left, right, bin);
        if (simplified != null) {
            return simplified;
        }

        // 无变化 → 返回原节点，避免创建多余对象
        if (left == bin.getLeft() && right == bin.getRight()) {
            return bin;
        }
        return new ASTNode.BinaryExpr(bin.getLine(), bin.getCol(), op, left, right);
    }

    // ========== 常量折叠 ==========

    /**
     * 对两个字面量做常量折叠，返回结果字面量；无法折叠时返回 null。
     */
    private LiteralExpr foldConstants(String op, LiteralExpr left, LiteralExpr right, ASTNode node) {
        // 算术运算：两边必须都是数字
        if (isArithmeticOp(op)) {
            if (left.getKind() != Kind.NUMBER || right.getKind() != Kind.NUMBER) {
                return null;
            }
            // 用 Double 统一处理，兼容整数与小数
            double l = Double.parseDouble(left.getValue());
            double r = Double.parseDouble(right.getValue());
            double result;
            switch (op) {
                case "+": result = l + r; break;
                case "-": result = l - r; break;
                case "*": result = l * r; break;
                case "/":
                    if (r == 0) return null; // 除以零不折叠，留给运行时处理
                    result = l / r;
                    break;
                default: return null;
            }
            // 整数操作数且结果为整数时保持整数格式，否则输出小数
            String resultStr = formatNumber(result, left.getValue(), right.getValue());
            return new LiteralExpr(node.getLine(), node.getCol(), resultStr, Kind.NUMBER);
        }

        // 比较运算：两边必须同类型；数值用 Double 比较，同时兼容整数与小数
        if (isComparisonOp(op)) {
            if (left.getKind() != right.getKind()) {
                return null;
            }
            int cmp;
            if (left.getKind() == Kind.NUMBER) {
                cmp = Double.compare(Double.parseDouble(left.getValue()),
                        Double.parseDouble(right.getValue()));
            } else {
                cmp = left.getValue().compareTo(right.getValue());
            }
            boolean result;
            switch (op) {
                case "=":  result = (cmp == 0); break;
                case "!=": result = (cmp != 0); break;
                case ">":  result = (cmp > 0);  break;
                case "<":  result = (cmp < 0);  break;
                case ">=": result = (cmp >= 0); break;
                case "<=": result = (cmp <= 0); break;
                default: return null;
            }
            return new LiteralExpr(node.getLine(), node.getCol(), String.valueOf(result), Kind.BOOLEAN);
        }

        // 逻辑运算：两边必须都是布尔
        if (op.equals("AND") || op.equals("OR")) {
            if (left.getKind() != Kind.BOOLEAN || right.getKind() != Kind.BOOLEAN) {
                return null;
            }
            boolean l = Boolean.parseBoolean(left.getValue());
            boolean r = Boolean.parseBoolean(right.getValue());
            boolean result = op.equals("AND") ? (l && r) : (l || r);
            return new LiteralExpr(node.getLine(), node.getCol(), String.valueOf(result), Kind.BOOLEAN);
        }

        return null;
    }

    // ========== 逻辑表达式化简 ==========

    /**
     * 利用逻辑恒等律化简 AND/OR 表达式，无法化简时返回 null。
     */
    private ASTNode simplifyLogical(String op, ASTNode left, ASTNode right, ASTNode node) {
        if (op.equals("AND")) {
            // cond AND TRUE → cond
            if (isTrueLiteral(right)) return left;
            // TRUE AND cond → cond
            if (isTrueLiteral(left)) return right;
            // cond AND FALSE → FALSE, FALSE AND cond → FALSE
            if (isFalseLiteral(left) || isFalseLiteral(right)) {
                return new LiteralExpr(node.getLine(), node.getCol(), "false", Kind.BOOLEAN);
            }
        }
        if (op.equals("OR")) {
            // cond OR FALSE → cond
            if (isFalseLiteral(right)) return left;
            // FALSE OR cond → cond
            if (isFalseLiteral(left)) return right;
            // cond OR TRUE → TRUE, TRUE OR cond → TRUE
            if (isTrueLiteral(left) || isTrueLiteral(right)) {
                return new LiteralExpr(node.getLine(), node.getCol(), "true", Kind.BOOLEAN);
            }
        }
        return null;
    }

    // ========== 算术恒等式化简 ==========

    /**
     * 利用算术单位元化简，如 x+0→x、x*1→x、x-0→x、x/1→x。无法化简时返回 null。
     */
    private ASTNode simplifyArithmetic(String op, ASTNode left, ASTNode right, ASTNode node) {
        // x + 0 → x, 0 + x → x
        if (op.equals("+")) {
            if (isZeroLiteral(right)) return left;
            if (isZeroLiteral(left)) return right;
        }
        // x - 0 → x
        if (op.equals("-")) {
            if (isZeroLiteral(right)) return left;
        }
        // x * 1 → x, 1 * x → x
        if (op.equals("*")) {
            if (isOneLiteral(right)) return left;
            if (isOneLiteral(left)) return right;
        }
        // x / 1 → x
        if (op.equals("/")) {
            if (isOneLiteral(right)) return left;
        }
        return null;
    }

    // ========== 辅助判断方法 ==========

    private boolean isTrueLiteral(ASTNode node) {
        return node instanceof LiteralExpr lit
                && lit.getKind() == Kind.BOOLEAN
                && Boolean.parseBoolean(lit.getValue());
    }

    private boolean isFalseLiteral(ASTNode node) {
        return node instanceof LiteralExpr lit
                && lit.getKind() == Kind.BOOLEAN
                && !Boolean.parseBoolean(lit.getValue());
    }

    private boolean isZeroLiteral(ASTNode node) {
        return node instanceof LiteralExpr lit
                && lit.getKind() == Kind.NUMBER
                && Integer.parseInt(lit.getValue()) == 0;
    }

    private boolean isOneLiteral(ASTNode node) {
        return node instanceof LiteralExpr lit
                && lit.getKind() == Kind.NUMBER
                && Integer.parseInt(lit.getValue()) == 1;
    }

    private boolean isComparisonOp(String op) {
        return op.equals("=") || op.equals("==") || op.equals("!=") || op.equals(">")
                || op.equals("<") || op.equals(">=") || op.equals("<=");
    }

    private boolean isArithmeticOp(String op) {
        return op.equals("+") || op.equals("-") || op.equals("*") || op.equals("/");
    }

    /**
     * 格式化算术折叠结果。
     * 当两个操作数都是整数且结果也是整数时，输出整数格式（如 "6"）；
     * 否则输出小数格式（如 "3.14"），避免无意义的 ".0" 后缀。
     */
    private String formatNumber(double result, String leftVal, String rightVal) {
        boolean allIntegers = !leftVal.contains(".") && !rightVal.contains(".");
        if (allIntegers && result == Math.floor(result) && !Double.isInfinite(result)) {
            return String.valueOf((long) result);
        }
        // 去掉无意义的尾随 .0（如 3.0 → 3.0 保留，因为操作数含小数）
        String s = String.valueOf(result);
        return s;
    }

    // ========== 物理计划 JSON 序列化 ==========

    /**
     * 将逻辑计划树序列化为物理计划 JSON 字符串（符合 storage_core 规范）。
     * 输出为单行 JSON（无换行），可直接写入 storage_core.exe 的 stdin。
     *
     * @param plan 计划树根节点
     * @return JSON 字符串
     */
    public String toJson(PlanNode plan) {
        StringBuilder sb = new StringBuilder();
        writePlanNode(sb, plan);
        return sb.toString();
    }

    /**
     * 递归写入计划节点。
     * 查询类（project/filter/scan）以树形组织，通过 child 引用子节点；
     * 写操作类（insert/update/delete/createTable）为单个对象。
     */
    private void writePlanNode(StringBuilder sb, PlanNode plan) {
        sb.append("{");

        if (plan instanceof PlanNode.SeqScanPlan p) {
            sb.append("\"op\":\"scan\",\"table\":");
            writeJsonString(sb, p.getTableName());
        } else if (plan instanceof PlanNode.FilterPlan p) {
            sb.append("\"op\":\"filter\",\"condition\":");
            writeExpr(sb, p.getCondition());
            sb.append(",\"child\":");
            writePlanNode(sb, p.getChild());
        } else if (plan instanceof PlanNode.ProjectPlan p) {
            sb.append("\"op\":\"project\",\"columns\":");
            writeStringList(sb, p.getColumns());
            sb.append(",\"child\":");
            writePlanNode(sb, p.getChild());
        } else if (plan instanceof PlanNode.InsertPlan p) {
            sb.append("\"op\":\"insert\",\"table\":");
            writeJsonString(sb, p.getTableName());
            if (!p.getColumns().isEmpty()) {
                sb.append(",\"columns\":");
                writeStringList(sb, p.getColumns());
            }
            sb.append(",\"values\":[");
            List<LiteralExpr> values = p.getValues();
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) sb.append(",");
                writeLiteralValue(sb, values.get(i));
            }
            sb.append("]");
        } else if (plan instanceof PlanNode.UpdatePlan p) {
            sb.append("\"op\":\"update\",\"table\":");
            writeJsonString(sb, p.getTableName());
            sb.append(",\"set\":{");
            int i = 0;
            for (Map.Entry<String, LiteralExpr> entry : p.getAssignments().entrySet()) {
                if (i > 0) sb.append(",");
                writeJsonString(sb, entry.getKey());
                sb.append(":");
                writeLiteralValue(sb, entry.getValue());
                i++;
            }
            sb.append("}");
            if (p.getCondition() != null) {
                sb.append(",\"condition\":");
                writeExpr(sb, p.getCondition());
            }
        } else if (plan instanceof PlanNode.DeletePlan p) {
            sb.append("\"op\":\"delete\",\"table\":");
            writeJsonString(sb, p.getTableName());
            if (p.getCondition() != null) {
                sb.append(",\"condition\":");
                writeExpr(sb, p.getCondition());
            }
        } else if (plan instanceof PlanNode.CreateTablePlan p) {
            sb.append("\"op\":\"createTable\",\"table\":");
            writeJsonString(sb, p.getTableName());
            sb.append(",\"columns\":");
            writeStringList(sb, p.getColumns());
        } else if (plan instanceof PlanNode.ShowTablesPlan) {
            sb.append("\"op\":\"showTables\"");
        } else if (plan instanceof PlanNode.DropTablePlan p) {
            sb.append("\"op\":\"dropTable\",\"table\":");
            writeJsonString(sb, p.getTableName());
        } else {
            throw new IllegalArgumentException("无法序列化的计划节点类型: " + plan.getClass().getSimpleName());
        }

        sb.append("}");
    }

    /**
     * 递归写入条件表达式（AST → JSON condition）。
     * - IdentifierExpr → {"type":"column","name":"id"}
     * - LiteralExpr → {"type":"literal","value":1}（value 用 JSON 原生类型）
     * - BinaryExpr → {"type":"binary","op":">","left":{...},"right":{...}}
     */
    private void writeExpr(StringBuilder sb, ASTNode expr) {
        sb.append("{");
        if (expr instanceof ASTNode.IdentifierExpr col) {
            sb.append("\"type\":\"column\",\"name\":");
            writeJsonString(sb, col.getName());
        } else if (expr instanceof LiteralExpr lit) {
            sb.append("\"type\":\"literal\",\"value\":");
            writeLiteralValue(sb, lit);
        } else if (expr instanceof ASTNode.BinaryExpr bin) {
            sb.append("\"type\":\"binary\",\"op\":");
            writeJsonString(sb, bin.getOp());
            sb.append(",\"left\":");
            writeExpr(sb, bin.getLeft());
            sb.append(",\"right\":");
            writeExpr(sb, bin.getRight());
        } else {
            throw new IllegalArgumentException("无法序列化的表达式类型: " + expr.getClass().getSimpleName());
        }
        sb.append("}");
    }

    /**
     * 写入字面量的值，根据 Kind 使用对应的 JSON 原生类型。
     * - NUMBER → JSON 数字（不带引号）
     * - STRING → JSON 字符串（带引号，处理转义）
     * - BOOLEAN → JSON 布尔（true/false，不带引号）
     */
    private void writeLiteralValue(StringBuilder sb, LiteralExpr lit) {
        if (lit.getKind() == Kind.NUMBER) {
            sb.append(lit.getValue());
        } else if (lit.getKind() == Kind.BOOLEAN) {
            sb.append(Boolean.parseBoolean(lit.getValue()) ? "true" : "false");
        } else {
            writeJsonString(sb, lit.getValue());
        }
    }

    /**
     * 写入字符串列表，如 ["id","name"]。
     */
    private void writeStringList(StringBuilder sb, List<String> list) {
        sb.append("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            writeJsonString(sb, list.get(i));
        }
        sb.append("]");
    }

    /**
     * 写入 JSON 字符串（带引号，处理转义）。
     * 转义规则：\" \\ \n \t \r \b \f
     */
    private void writeJsonString(StringBuilder sb, String str) {
        sb.append("\"");
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                case '\r' -> sb.append("\\r");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> sb.append(c);
            }
        }
        sb.append("\"");
    }
}
