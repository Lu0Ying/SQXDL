package com.sqxdl.parser;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * 随机模糊测试（Fuzzing）：用 jqwik 属性测试引擎随机生成 SQL 输入，
 * 验证解析器在任意输入下的健壮性：
 * <ul>
 *   <li>Lexer / Parser 只允许抛出统一异常 SqxdlException，绝不崩溃（不抛其他运行时异常）；</li>
 *   <li>parseAll 的错误恢复：出错语句被跳过并记录，不会中断后续解析；</li>
 *   <li>合法模板 SQL 随机填充后同样不崩溃。</li>
 * </ul>
 * 属性测试由 jqwik 引擎驱动，每个 @Property 默认随机尝试数百组输入。
 */
class FuzzTest {

    /**
     * 属性 1：Lexer 对任意随机字符串切词，只允许抛出 SqxdlException。
     * 覆盖场景：非法字符、未闭合字符串、未闭合注释、乱码、控制字符、极长输入等。
     */
    @Property(tries = 500)
    void lexerOnlyThrowsSqxdlException(@ForAll String input) {
        try {
            Lexer lexer = new Lexer(input);
            Token t;
            do {
                t = lexer.nextToken();
            } while (t.getType() != Token.Type.EOF);
        } catch (SqxdlException expected) {
            // 预期行为：统一异常类型（含非法字符、未闭合字符串/注释等）
        }
        // 其他任何异常类型（如 NumberFormatException、StringIndexOutOfBoundsException）
        // 都会使本测试失败——这正是模糊测试要抓的"崩溃点"
    }

    /**
     * 属性 2：Parser.parseAll 对任意随机 SQL 文本，只允许抛出 SqxdlException。
     * 合法输入应正常解析；非法输入由错误恢复机制收集到 getErrors()，
     * 或由同步检查点抛出 SqxdlException，绝不抛其他异常。
     */
    @Property(tries = 500)
    void parserOnlyThrowsSqxdlException(@ForAll String sql) {
        try {
            new Parser(new Lexer(sql)).parseAll();
        } catch (SqxdlException expected) {
            // 预期行为：统一异常类型
        }
    }

    /**
     * 属性 3：错误恢复与错误格式契约。
     * 无论错误来自哪个路径——构造预读（首字符非法）、parseAll 收集、同步检查点——
     * 都必须是 SqxdlException 且携带统一定位前缀 [行:列]（见 SqxdlException 约定）。
     */
    @Property(tries = 300)
    void collectedErrorsAlwaysCarryLocation(@ForAll String sql) {
        Parser parser;
        try {
            parser = new Parser(new Lexer(sql));
        } catch (SqxdlException direct) {
            // 构造预读第一个 token 时的词法错误（非法字符等），同样带定位前缀
            if (!direct.getMessage().matches("\\s*\\[\\d+:\\d+\\].*")) {
                throw new AssertionError("错误缺少统一定位前缀 [行:列]: " + direct.getMessage());
            }
            return;
        }
        parser.parseAll();
        for (SqxdlException e : parser.getErrors()) {
            if (!e.getMessage().matches("\\s*\\[\\d+:\\d+\\].*")) {
                throw new AssertionError("错误缺少统一定位前缀 [行:列]: " + e.getMessage());
            }
        }
    }

    /**
     * 属性 4：合法模板 SQL 随机填充（随机标识符 / 数字 / 排序方向），
     * 解析结果要么成功，要么只抛统一异常（随机标识符可能恰好撞上关键字）。
     * 用结构化模板证明：靠近合法语法的输入不会触发崩溃。
     */
    @Property(tries = 300)
    void templatedSqlParsesOrThrowsSqxdlException(@ForAll("templatedSql") String sql) {
        try {
            new Parser(new Lexer(sql)).parseAll();
        } catch (SqxdlException expected) {
            // 随机标识符撞关键字等边界情况：统一异常类型，允许
        }
    }

    /**
     * 生成器：随机拼出结构完整的 SELECT ... JOIN ... WHERE ... GROUP BY ... ORDER BY 语句。
     * 表名 / 列名从字母与下划线随机选取，数字限定在 [-1000, 1000]。
     */
    @Provide
    Arbitrary<String> templatedSql() {
        Arbitrary<String> word = Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ_")
                .ofMinLength(1).ofMaxLength(10);
        Arbitrary<Integer> num = Arbitraries.integers().between(-1000, 1000);
        return Combinators.combine(word, word, word, word, num)
                .as((t1, col, t2, key, v) ->
                        "SELECT " + col + " FROM " + t1
                                + " JOIN " + t2 + " ON " + t1 + ".id = " + t2 + ".id"
                                + " WHERE " + key + " > " + v
                                + " GROUP BY " + col
                                + " ORDER BY " + col + " DESC");
    }
}
