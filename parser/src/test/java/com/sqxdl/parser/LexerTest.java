package com.sqxdl.parser;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lexer 单元测试，按功能分为以下分组：
 * <ul>
 *   <li>五类 Token 识别（关键字 / 标识符 / 常量 / 运算符 / 分隔符）与 EOF；</li>
 *   <li>行列号跟踪（跨行、换行累计）；</li>
 *   <li>异常分支：非法字符、未闭合字符串、非法标识符、未闭合块注释；</li>
 *   <li>拼写纠错：编辑距离为 1 的关键字自动纠正；</li>
 *   <li>对照课件要求的补充用例：单行/块注释、转义引号、小数、==、全关键字集、多语句、行列号复刻。</li>
 * </ul>
 * 共 23 个用例。运行：mvn -pl parser test
 */
class LexerTest {

    /** 将整条 SQL 全部切词，末尾含 EOF Token */
    private List<Token> tokenize(String sql) {
        Lexer lexer = new Lexer(sql);
        List<Token> tokens = new ArrayList<>();
        Token t;
        do {
            t = lexer.nextToken();
            tokens.add(t);
        } while (t.getType() != Token.Type.EOF);
        return tokens;
    }

    @Test
    void basicSelectProducesExpectedTokens() {
        // 一条最简 SELECT 的完整切词结果：SELECT/id/FROM/t/;/EOF 共 6 个 Token
        List<Token> ts = tokenize("SELECT id FROM t;");
        assertEquals(6, ts.size(), "应为 SELECT/id/FROM/t/;/EOF 共 6 个 Token");

        assertEquals(Token.Type.KEYWORD, ts.get(0).getType());
        assertEquals("SELECT", ts.get(0).getLexeme());

        assertEquals(Token.Type.IDENTIFIER, ts.get(1).getType());
        assertEquals("id", ts.get(1).getLexeme());

        assertEquals(Token.Type.KEYWORD, ts.get(2).getType());
        assertEquals("FROM", ts.get(2).getLexeme());

        assertEquals(Token.Type.IDENTIFIER, ts.get(3).getType());
        assertEquals("t", ts.get(3).getLexeme());

        assertEquals(Token.Type.DELIMITER, ts.get(4).getType());
        assertEquals(";", ts.get(4).getLexeme());

        assertEquals(Token.Type.EOF, ts.get(5).getType());
    }

    @Test
    void keywordIsCaseInsensitive() {
        // 关键字不区分大小写：小写 select/from/where 均识别为关键字；* 为运算符
        List<Token> ts = tokenize("select * from stu where age > 18");
        assertEquals(Token.Type.KEYWORD, ts.get(0).getType(), "小写 select 应识别为关键字");
        assertEquals(Token.Type.OPERATOR, ts.get(1).getType(), "* 应为运算符");
        assertEquals("*", ts.get(1).getLexeme());
        assertEquals(Token.Type.KEYWORD, ts.get(2).getType(), "小写 from 应识别为关键字");
        assertEquals(Token.Type.KEYWORD, ts.get(4).getType(), "小写 where 应识别为关键字");
    }

    @Test
    void stringConstantDropsQuotes() {
        // 字符串字面量：词素不含引号（'Tom' → Tom）
        List<Token> ts = tokenize("WHERE name = 'Tom'");
        Token s = ts.get(3);
        assertEquals(Token.Type.CONST, s.getType());
        assertEquals("Tom", s.getLexeme(), "字符串词素不应包含引号");
    }

    @Test
    void twoCharOperatorsRecognized() {
        // 双字符运算符整体识别：<= 不会被拆成 < 与 =，&& 同理
        List<Token> ts = tokenize("a <= b && c >= d || e != f");
        List<String> ops = new ArrayList<>();
        for (Token t : ts) {
            if (t.getType() == Token.Type.OPERATOR) {
                ops.add(t.getLexeme());
            }
        }
        assertEquals(List.of("<=", "&&", ">=", "||", "!="), ops, "应识别全部双字符运算符");
    }

    @Test
    void emptyInputReturnsEofOnly() {
        // 空输入：只产生一个 EOF Token
        List<Token> ts = tokenize("");
        assertEquals(1, ts.size());
        assertEquals(Token.Type.EOF, ts.get(0).getType());
    }

    @Test
    void whitespaceOnlyReturnsEofOnly() {
        // 纯空白输入：空白被跳过，只产生一个 EOF Token
        List<Token> ts = tokenize("   \t\r\n  ");
        assertEquals(1, ts.size());
        assertEquals(Token.Type.EOF, ts.get(0).getType());
    }

    @Test
    void lineAndColTrackingAcrossNewline() {
        // 行列号跟踪：换行后行号 +1、列号从 1 重新计数
        List<Token> ts = tokenize("SELECT id\nFROM t");
        // SELECT 在第 1 行第 1 列
        assertEquals(1, ts.get(0).getLine());
        assertEquals(1, ts.get(0).getCol());
        // id 在第 1 行第 8 列
        assertEquals(1, ts.get(1).getLine());
        assertEquals(8, ts.get(1).getCol());
        // 换行后 FROM 在第 2 行第 1 列
        assertEquals(2, ts.get(2).getLine());
        assertEquals(1, ts.get(2).getCol());
    }

    @Test
    void delimiterDoesNotLoopForever() {
        // 回归测试：分隔符分支必须推进 pos，否则会无限返回同一个 ';'
        List<Token> ts = tokenize(";");
        assertEquals(2, ts.size());
        assertEquals(Token.Type.DELIMITER, ts.get(0).getType());
        assertEquals(Token.Type.EOF, ts.get(1).getType());
    }

    @Test
    void illegalCharThrowsWithPosition() {
        // 非法字符（@）抛异常，并携带精确行列号
        Lexer lexer = new Lexer("SELECT @ FROM t");
        lexer.nextToken(); // 消费 SELECT
        SqxdlException e = assertThrows(SqxdlException.class, lexer::nextToken);
        assertEquals(1, e.getLine());
        assertEquals(8, e.getCol(), "@ 应定位在第 1 行第 8 列");
        assertTrue(e.getMessage().contains("非法字符"));
    }

    @Test
    void unterminatedStringThrows() {
        // 字符串未闭合：抛异常
        Lexer lexer = new Lexer("SELECT 'abc");
        lexer.nextToken(); // 消费 SELECT，未闭合字符串在第 2 个 Token 触发异常
        SqxdlException e = assertThrows(SqxdlException.class, lexer::nextToken);
        assertTrue(e.getMessage().contains("未闭合"));
    }

    @Test
    void digitFollowedByLetterThrows() {
        // 数字后紧跟字母不是合法标识符（123abc）：抛异常
        SqxdlException e = assertThrows(SqxdlException.class,
                () -> new Lexer("123abc").nextToken());
        assertTrue(e.getMessage().contains("非法标识符"));
    }

    @Test
    void misspelledKeywordIsCorrected() {
        // "selec" 与 "SELECT" 编辑距离为 1，应自动纠正为关键字
        List<Token> ts = tokenize("selec id FROM t");
        assertEquals(Token.Type.KEYWORD, ts.get(0).getType());
        assertEquals("SELECT", ts.get(0).getLexeme());
    }

    @Test
    void distantMisspellingIsNotCorrected() {
        // "abc" 与所有关键字编辑距离都大于 1，应保持为普通标识符
        List<Token> ts = tokenize("abc id FROM t");
        assertEquals(Token.Type.IDENTIFIER, ts.get(0).getType());
        assertEquals("abc", ts.get(0).getLexeme());
    }

    @Test
    void underscoreIdentifierSupported() {
        // 标识符支持下划线：_id 与 my_table 均识别为标识符
        List<Token> ts = tokenize("SELECT _id FROM my_table");
        assertEquals(Token.Type.IDENTIFIER, ts.get(1).getType());
        assertEquals("_id", ts.get(1).getLexeme());
        assertEquals(Token.Type.IDENTIFIER, ts.get(3).getType());
        assertEquals("my_table", ts.get(3).getLexeme());
    }

    // ========== 以下为对照课件要求的补充用例 ==========

    @Test
    void lineCommentSkipped() {
        // 单行注释 -- ... 被跳过：注释里的 SELECT 不产出 Token
        List<Token> ts = tokenize("SELECT id -- 这是注释 SELECT\nFROM t");
        assertEquals(5, ts.size(), "注释内容不应产出 Token：SELECT/id/FROM/t/EOF");
        assertEquals(Token.Type.EOF, ts.get(4).getType());
    }

    @Test
    void blockCommentSkippedAcrossLines() {
        // 块注释 /* ... */ 跨行被跳过：注释里的 FROM 不应被识别；id 应落在第 2 行
        List<Token> ts = tokenize("SELECT /* 多行\n注释 FROM */ id FROM t");
        // 注释里的 "FROM" 不应被识别；id 应落在第 2 行
        assertEquals("id", ts.get(1).getLexeme());
        assertEquals(2, ts.get(1).getLine(), "块注释跨行后行列号应正确累计");
    }

    @Test
    void unterminatedBlockCommentThrows() {
        // 块注释未闭合：抛异常
        SqxdlException e = assertThrows(SqxdlException.class,
                () -> tokenize("SELECT /* 未闭合"));
        assertTrue(e.getMessage().contains("未闭合的块注释"));
    }

    @Test
    void escapedQuoteInString() {
        // 字符串内 '' 转义为字面单引号：'Tom''s book' → Tom's book
        List<Token> ts = tokenize("'Tom''s book'");
        assertEquals(2, ts.size());
        assertEquals(Token.Type.CONST, ts.get(0).getType());
        assertEquals("Tom's book", ts.get(0).getLexeme(), "'' 应转义为字面单引号");
    }

    @Test
    void decimalConstantSupported() {
        // 小数常量整体识别：3.14 为一个 CONST Token
        List<Token> ts = tokenize("3.14");
        assertEquals(2, ts.size());
        assertEquals(Token.Type.CONST, ts.get(0).getType());
        assertEquals("3.14", ts.get(0).getLexeme());
    }

    @Test
    void equalityOperatorRecognized() {
        // == 运算符（与 = 并存）整体识别
        List<Token> ts = tokenize("a == b");
        List<String> ops = new ArrayList<>();
        for (Token t : ts) {
            if (t.getType() == Token.Type.OPERATOR) {
                ops.add(t.getLexeme());
            }
        }
        assertEquals(List.of("=="), ops);
    }

    @Test
    void fullKeywordSetRecognized() {
        // 关键字表全量识别（逐个关键字均为 KEYWORD）
        List<Token> ts = tokenize("SELECT FROM WHERE CREATE TABLE INSERT INTO VALUES DELETE AND");
        for (int i = 0; i < 10; i++) {
            assertEquals(Token.Type.KEYWORD, ts.get(i).getType(), "第 " + i + " 个应为关键字");
        }
    }

    @Test
    void multipleStatementsInOneInput() {
        // 一次输入可含多条 SQL 语句：分号与 SELECT 数量均为 2
        List<Token> ts = tokenize("SELECT a FROM t; SELECT b FROM u;");
        long semicolons = ts.stream().filter(t -> ";".equals(t.getLexeme())).count();
        long selects = ts.stream().filter(t -> "SELECT".equals(t.getLexeme())).count();
        assertEquals(2, selects, "应支持一次输入包含多条 SQL 语句");
        assertEquals(2, semicolons);
    }

    @Test
    void coursewareExampleWithLineCol() {
        // 复刻课件第 10 页示例，逐 Token 验证类型、词素与行列号
        List<Token> ts = tokenize("-- query\nSELECT name\nFROM student\nWHERE age >= 18\n  AND name != 'Tom';");

        record Expect(Token.Type type, String lexeme, int line, int col) {}
        List<Expect> expected = List.of(
                new Expect(Token.Type.KEYWORD, "SELECT", 2, 1),
                new Expect(Token.Type.IDENTIFIER, "name", 2, 8),
                new Expect(Token.Type.KEYWORD, "FROM", 3, 1),
                new Expect(Token.Type.IDENTIFIER, "student", 3, 6),
                new Expect(Token.Type.KEYWORD, "WHERE", 4, 1),
                new Expect(Token.Type.IDENTIFIER, "age", 4, 7),
                new Expect(Token.Type.OPERATOR, ">=", 4, 11),
                new Expect(Token.Type.CONST, "18", 4, 14),
                new Expect(Token.Type.KEYWORD, "AND", 5, 3),
                new Expect(Token.Type.IDENTIFIER, "name", 5, 7),
                new Expect(Token.Type.OPERATOR, "!=", 5, 12),
                new Expect(Token.Type.CONST, "Tom", 5, 15),
                new Expect(Token.Type.DELIMITER, ";", 5, 20),
                new Expect(Token.Type.EOF, "", 5, 21));

        assertEquals(expected.size(), ts.size());
        for (int i = 0; i < expected.size(); i++) {
            Expect e = expected.get(i);
            Token a = ts.get(i);
            assertEquals(e.type(), a.getType(), "第 " + i + " 个 Token 类型不符");
            assertEquals(e.lexeme(), a.getLexeme(), "第 " + i + " 个 Token 词素不符");
            assertEquals(e.line(), a.getLine(), "第 " + i + " 个 Token 行号不符");
            assertEquals(e.col(), a.getCol(), "第 " + i + " 个 Token 列号不符");
        }
    }
}
