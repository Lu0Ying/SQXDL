package com.sqxdl.semantic;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 聚合函数解析工具。
 * <p>
 * Parser 将聚合调用归一化为字符串形式（如 "SUM(age)"、"COUNT(*)"），
 * 本类负责解析该字符串，提取函数名与参数列名。
 * <p>
 * 支持的聚合函数：COUNT/SUM/AVG/MIN/MAX。
 *
 * <pre>
 * "COUNT(*)"   → name=COUNT, arg=*（COUNT(*) 特殊参数）
 * "SUM(age)"   → name=SUM,   arg=age
 * "AVG(score)" → name=AVG,   arg=score
 * "MIN(age)"   → name=MIN,   arg=age
 * "MAX(age)"   → name=MAX,   arg=age
 * </pre>
 */
public final class AggregateFunction {

    /** 聚合函数名（大写） */
    public static final String COUNT = "COUNT";
    public static final String SUM = "SUM";
    public static final String AVG = "AVG";
    public static final String MIN = "MIN";
    public static final String MAX = "MAX";

    // 形如 FUNC(arg)，FUNC 为字母序列，arg 为 * 或标识符（可含点限定）
    private static final Pattern AGG_PATTERN = Pattern.compile(
            "^([A-Za-z]+)\\((\\*|[A-Za-z_][A-Za-z0-9_.]*)\\)$");

    private final String name;     // 大写函数名：COUNT/SUM/AVG/MIN/MAX
    private final String argument; // 参数："*" 或列名（可含点限定 table.col）

    private AggregateFunction(String name, String argument) {
        this.name = name;
        this.argument = argument;
    }

    /**
     * 尝试解析投影项字符串为聚合函数调用。
     *
     * @param column 投影项字符串，如 "SUM(age)"、"COUNT(*)"、"name"
     * @return 聚合函数对象；非聚合项返回 null
     */
    public static AggregateFunction parse(String column) {
        if (column == null || column.isEmpty()) {
            return null;
        }
        Matcher m = AGG_PATTERN.matcher(column);
        if (!m.matches()) {
            return null;
        }
        String funcName = m.group(1).toUpperCase();
        String arg = m.group(2);
        // 只识别支持的聚合函数名
        if (!funcName.equals(COUNT) && !funcName.equals(SUM)
                && !funcName.equals(AVG) && !funcName.equals(MIN)
                && !funcName.equals(MAX)) {
            return null;
        }
        // COUNT 的参数可以是 * 或列名；其余函数参数必须是列名
        if (funcName.equals(COUNT)) {
            return new AggregateFunction(COUNT, arg);
        }
        if ("*".equals(arg)) {
            return null;  // SUM/AVG/MIN/MAX 不接受 * 参数
        }
        return new AggregateFunction(funcName, arg);
    }

    /**
     * 判断投影项是否为聚合函数调用。
     * 等价于 {@code parse(column) != null}。
     */
    public static boolean isAggregate(String column) {
        return parse(column) != null;
    }

    /** 函数名（大写）：COUNT/SUM/AVG/MIN/MAX */
    public String getName() {
        return name;
    }

    /** 参数："*"（仅 COUNT）或列名 */
    public String getArgument() {
        return argument;
    }

    /** 是否为 COUNT(*) 形式 */
    public boolean isCountStar() {
        return COUNT.equals(name) && "*".equals(argument);
    }

    /**
     * 返回聚合函数的结果类型。
     * <p>
     * 规则：
     * <ul>
     *   <li>COUNT → INT（行数）</li>
     *   <li>SUM → 与参数列同类型（INT 列求和为 INT，DOUBLE 列求和为 DOUBLE）</li>
     *   <li>AVG → DOUBLE（平均值总是浮点）</li>
     *   <li>MIN/MAX → 与参数列同类型</li>
     * </ul>
     *
     * @param argType 参数列的类型（COUNT(*) 时可传任意值，不使用）
     * @return 聚合结果类型
     */
    public CatalogImpl.DataType resultType(CatalogImpl.DataType argType) {
        return switch (name) {
            case COUNT -> CatalogImpl.DataType.INT;
            case AVG -> CatalogImpl.DataType.DOUBLE;
            case SUM, MIN, MAX -> argType;
            default -> CatalogImpl.DataType.DOUBLE;
        };
    }

    /**
     * 校验参数列类型对该聚合函数是否合法。
     * <p>
     * 规则：
     * <ul>
     *   <li>COUNT → 任意类型都可（计行数）</li>
     *   <li>SUM/AVG → 参数必须为数值类型（INT/DOUBLE）</li>
     *   <li>MIN/MAX → 任意类型都可（数值、字符串均可比较）</li>
     * </ul>
     *
     * @param argType 参数列类型
     * @return true 表示类型合法
     */
    public boolean isArgTypeValid(CatalogImpl.DataType argType) {
        return switch (name) {
            case COUNT -> true;
            case SUM, AVG -> argType == CatalogImpl.DataType.INT
                    || argType == CatalogImpl.DataType.DOUBLE;
            case MIN, MAX -> true;
            default -> false;
        };
    }

    @Override
    public String toString() {
        return name + "(" + argument + ")";
    }
}
