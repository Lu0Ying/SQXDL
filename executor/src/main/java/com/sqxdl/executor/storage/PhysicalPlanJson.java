package com.sqxdl.executor.storage;

import com.sqxdl.parser.ASTNode;
import com.sqxdl.semantic.CatalogImpl;
import com.sqxdl.semantic.PlanNode;

/**
 * 物理计划 JSON 序列化器：把 Java 计划树转换为存储核心（storage_core.exe）
 * 约定的 physic plan JSON，格式见 storage/readme.md。
 * 查询计划按 project -> filter -> scan 树形嵌套；写操作为单个对象。
 */
public final class PhysicalPlanJson {

    private PhysicalPlanJson() {
    }

    /** 序列化计划树为 JSON 字符串 */
    public static String serialize(PlanNode plan) {
        StringBuilder sb = new StringBuilder();
        writeNode(plan, sb);
        return sb.toString();
    }

    /** 按计划节点类型分发写出 */
    private static void writeNode(PlanNode plan, StringBuilder sb) {
        if (plan instanceof PlanNode.ProjectPlan p) {
            sb.append("{\"op\":\"project\",\"columns\":").append(stringArray(p.getColumns()))
              .append(",\"child\":");
            writeNode(p.getChild(), sb);
            sb.append('}');
        } else if (plan instanceof PlanNode.FilterPlan f) {
            sb.append("{\"op\":\"filter\",\"condition\":");
            writeCondition(f.getCondition(), sb);
            sb.append(",\"child\":");
            writeNode(f.getChild(), sb);
            sb.append('}');
        } else if (plan instanceof PlanNode.SeqScanPlan s) {
            sb.append("{\"op\":\"scan\",\"table\":").append(Json.quote(s.getTableName())).append('}');
        } else if (plan instanceof PlanNode.JoinPlan j) {
            // 存储核心的 join 为二元节点，多表连接折叠为左深二叉链
            writeJoinChain(j.getChildren(), j.getOnConditions(), 0, sb);
        } else if (plan instanceof PlanNode.InsertPlan p) {
            sb.append("{\"op\":\"insert\",\"table\":").append(Json.quote(p.getTableName()));
            // 列清单可省略，省略时存储核心按建表顺序对应 values
            if (!p.getColumns().isEmpty()) {
                sb.append(",\"columns\":").append(stringArray(p.getColumns()));
            }
            sb.append(",\"values\":").append(literalArray(p.getValues())).append('}');
        } else if (plan instanceof PlanNode.UpdatePlan p) {
            sb.append("{\"op\":\"update\",\"table\":").append(Json.quote(p.getTableName()))
              .append(",\"set\":").append(literalObject(p.getAssignments()));
            writeOptionalCondition(p.getCondition(), sb);
            sb.append('}');
        } else if (plan instanceof PlanNode.DeletePlan p) {
            sb.append("{\"op\":\"delete\",\"table\":").append(Json.quote(p.getTableName()));
            writeOptionalCondition(p.getCondition(), sb);
            sb.append('}');
        } else if (plan instanceof PlanNode.CreateTablePlan p) {
            sb.append("{\"op\":\"createTable\",\"table\":").append(Json.quote(p.getTableName()))
              .append(",\"columns\":").append(columnDefArray(p)).append('}');
        } else if (plan instanceof PlanNode.ShowTablesPlan) {
            sb.append("{\"op\":\"showTables\"}");
        } else if (plan instanceof PlanNode.DescribeTablePlan p) {
            sb.append("{\"op\":\"describeTable\",\"table\":").append(Json.quote(p.getTableName())).append('}');
        } else if (plan instanceof PlanNode.DropTablePlan p) {
            // 存储核心协议：删表操作名为 deleteTable（非 dropTable）
            sb.append("{\"op\":\"deleteTable\",\"table\":").append(Json.quote(p.getTableName())).append('}');
        } else {
            throw new IllegalArgumentException("不支持的计划节点: " + plan.getClass().getSimpleName());
        }
    }

    /** 写出条件表达式（column / literal / binary / NOT 等价改写四种节点） */
    private static void writeCondition(ASTNode cond, StringBuilder sb) {
        if (cond instanceof ASTNode.BinaryExpr e) {
            sb.append("{\"type\":\"binary\",\"op\":").append(Json.quote(normalizeOp(e.getOp())))
              .append(",\"left\":");
            writeCondition(e.getLeft(), sb);
            sb.append(",\"right\":");
            writeCondition(e.getRight(), sb);
            sb.append('}');
        } else if (cond instanceof ASTNode.UnaryExpr n) {
            // 存储核心协议无 not 节点，等价改写：NOT x -> x = FALSE
            sb.append("{\"type\":\"binary\",\"op\":\"=\",\"left\":");
            writeCondition(n.getOperand(), sb);
            sb.append(",\"right\":{\"type\":\"literal\",\"value\":false}}");
        } else if (cond instanceof ASTNode.IdentifierExpr c) {
            sb.append("{\"type\":\"column\",\"name\":").append(Json.quote(c.getName())).append('}');
        } else if (cond instanceof ASTNode.LiteralExpr l) {
            writeLiteralValue(l, sb);
        } else {
            throw new IllegalArgumentException("不支持的条件表达式: " + cond);
        }
    }

    /** 多表连接折叠为左深二叉 join 链；约定 children[0] 为主表（onCondition 为 null） */
    private static void writeJoinChain(java.util.List<PlanNode> children,
                                       java.util.List<ASTNode> onConditions,
                                       int index, StringBuilder sb) {
        if (children.size() < 2) {
            throw new IllegalArgumentException("join 至少需要两个子计划");
        }
        sb.append("{\"op\":\"join\",\"type\":\"inner\",\"left\":");
        writeNode(children.get(index), sb);
        sb.append(",\"right\":");
        if (index == children.size() - 2) {
            writeNode(children.get(index + 1), sb);
        } else {
            writeJoinChain(children, onConditions, index + 1, sb);
        }
        ASTNode condition = onConditions.get(index + 1);
        if (condition == null) {
            throw new IllegalArgumentException("join 缺少 ON 连接条件");
        }
        sb.append(",\"condition\":");
        writeCondition(condition, sb);
        sb.append('}');
    }

    /** 运算符归一化：== 写法、逻辑运算符符号形式转为存储核心识别的关键字 */
    private static String normalizeOp(String op) {
        return switch (op) {
            case "==" -> "=";
            case "&&" -> "AND";
            case "||" -> "OR";
            default -> op;
        };
    }

    /** 条件可省略（update/delete 无 WHERE 时作用于全表），为 null 时不写字段 */
    private static void writeOptionalCondition(ASTNode condition, StringBuilder sb) {
        if (condition != null) {
            sb.append(",\"condition\":");
            writeCondition(condition, sb);
        }
    }

    /** 写出带 type 包装的字面量条件节点，数字原样、字符串加引号 */
    private static void writeLiteralValue(ASTNode.LiteralExpr literal, StringBuilder sb) {
        sb.append("{\"type\":\"literal\",\"value\":").append(rawLiteral(literal)).append('}');
    }

    /** 字面量裸值：数字取词法原文，字符串转义加引号 */
    private static String rawLiteral(ASTNode.LiteralExpr literal) {
        return literal.getKind() == ASTNode.LiteralExpr.Kind.NUMBER
                ? literal.getValue()
                : Json.quote(literal.getValue());
    }

    private static StringBuilder literalArray(java.util.List<ASTNode.LiteralExpr> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(rawLiteral(values.get(i)));
        }
        return sb.append(']');
    }

    private static StringBuilder literalObject(java.util.Map<String, ASTNode.LiteralExpr> assignments) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (java.util.Map.Entry<String, ASTNode.LiteralExpr> entry : assignments.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(Json.quote(entry.getKey())).append(':').append(rawLiteral(entry.getValue()));
        }
        return sb.append('}');
    }

    private static StringBuilder stringArray(java.util.List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(Json.quote(items.get(i)));
        }
        return sb.append(']');
    }

    /** 建表列定义：带类型时输出对象数组 [{"name":..,"type":..}]，否则退化为列名字符串数组 */
    private static StringBuilder columnDefArray(PlanNode.CreateTablePlan plan) {
        java.util.List<CatalogImpl.ColumnInfo> defs = plan.getColumnDefs();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < plan.getColumns().size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            if (defs != null && i < defs.size()) {
                CatalogImpl.ColumnInfo def = defs.get(i);
                sb.append("{\"name\":").append(Json.quote(def.getName()))
                  .append(",\"type\":").append(Json.quote(typeName(def.getType()))).append('}');
            } else {
                sb.append(Json.quote(plan.getColumns().get(i)));
            }
        }
        return sb.append(']');
    }

    /** 数据字典类型 -> 存储核心类型字符串 */
    private static String typeName(CatalogImpl.DataType type) {
        return switch (type) {
            case INT -> "INT";
            case DOUBLE -> "DOUBLE";
            case BOOLEAN -> "BOOLEAN";
            default -> "VARCHAR";
        };
    }
}
