package com.sqxdl.parser;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 临界 / 边界用例测试（A 组自测补充）：
 * 覆盖正常功能测试之外的边界场景，按类别分为：
 * <ul>
 *   <li>词法边界：空语句、仅分号、缺列清单、字符串转义、小数、非法字符；</li>
 *   <li>拼写纠错：编辑距离 1 的自动纠正与警告收集；</li>
 *   <li>表达式边界：WHERE 中常量折叠、优先级、NOT/括号、布尔简化、除零、==；</li>
 *   <li>SHOW / DROP 边界：多余 token、缺失子句；</li>
 *   <li>错误恢复：多语句中某条出错不中断后续解析。</li>
 * </ul>
 * 运行：mvn -pl parser test
 */
class  EdgeCaseTest {

    /** 单语句解析：出错时抛出 SqxdlException */
    private ASTNode parse(String sql) {
        return new Parser(new Lexer(sql)).parse();
    }

    /** 多语句解析：出错时记入 getErrors() 并恢复继续 */
    private List<ASTNode> parseAll(String sql) {
        return new Parser(new Lexer(sql)).parseAll();
    }

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

    // ========== 词法边界 ==========

    @Test
    void semicolonsOnlyYieldNoStatements() {
        // 空输入 / 连续分号：全部按空语句跳过，不产生 AST，也不报错
        List<ASTNode> stmts = parseAll(";; ;");
        assertTrue(stmts.isEmpty(), "空语句应被跳过");
        assertTrue(new Parser(new Lexer("")).parseAll().isEmpty(), "空输入应为空列表");
    }

    @Test
    void selectMissingColumnListThrows() {
        // SELECT 后直接跟分号：列清单缺失，报 unexpected token 并期望 IDENTIFIER
        SqxdlException e = assertThrows(SqxdlException.class, () -> parse("SELECT ;"));
        assertTrue(e.getMessage().contains("unexpected token ';'"), "应报 unexpected token");
        assertTrue(e.getMessage().contains("IDENTIFIER"), "列清单处应期望 IDENTIFIER");
    }

    @Test
    void escapedQuoteInString() {
        // 字符串转义：'' 表示单引号，词法层应得到去引号后的 a'b
        List<Token> ts = tokenize("'a''b'");
        assertEquals(Token.Type.CONST, ts.get(0).getType());
        assertEquals("a'b", ts.get(0).getLexeme(), "'' 应转义为单引号");
    }

    @Test
    void floatNumberToken() {
        // 小数常量：3.14 应整体识别为一个 CONST Token
        List<Token> ts = tokenize("3.14");
        assertEquals(Token.Type.CONST, ts.get(0).getType());
        assertEquals("3.14", ts.get(0).getLexeme());
    }

    @Test
    void dotLeadingNumberThrows() {
        // 以 . 开头的小数不合法：. 不是运算符/分隔符/字母，词法层报非法字符
        assertThrows(SqxdlException.class, () -> tokenize(".5"), ".5 应报非法字符");
    }

    @Test
    void illegalCharThrows() {
        // 非法字符 @：词法层应报带行列号的异常
        SqxdlException e = assertThrows(SqxdlException.class, () -> tokenize("SELECT @ FROM t"));
        assertTrue(e.getMessage().contains("非法字符 '@'"), "应报非法字符 @");
    }

    // ========== 拼写纠错 ==========

    @Test
    void misspelledKeywordsCorrectedWithWarning() {
        // 编辑距离 1 的关键字自动纠正：selec→SELECT、fro→FROM；
        // 警告经 getSpellWarnings() 收集，且纠正后 lexeme 为纠正值（大写）
        Lexer lexer = new Lexer("selec * fro t");
        List<Token> ts = new ArrayList<>();
        Token t;
        do {
            t = lexer.nextToken();
            ts.add(t);
        } while (t.getType() != Token.Type.EOF);

        assertEquals(Token.Type.KEYWORD, ts.get(0).getType());
        assertEquals("SELECT", ts.get(0).getLexeme(), "纠正后的 lexeme 应为 SELECT");
        assertEquals(Token.Type.KEYWORD, ts.get(2).getType());
        assertEquals("FROM", ts.get(2).getLexeme(), "纠正后的 lexeme 应为 FROM");
        assertEquals(2, lexer.getSpellWarnings().size(), "应收集 2 条拼写提示");
    }

    @Test
    void tablesPluralCorrected() {
        // 关键字不区分大小写：TABLEs 的复数形式按 TABLES 关键字识别（lexeme 保留原始大小写）
        List<Token> ts = tokenize("SHOW TABLEs");
        assertEquals(Token.Type.KEYWORD, ts.get(1).getType());
        assertTrue("TABLES".equalsIgnoreCase(ts.get(1).getLexeme()), "应识别为 TABLES 关键字");
    }

    // ========== 表达式 / WHERE 边界 ==========

    @Test
    void whereArithFoldProducesConstant() {
        // WHERE 中常量折叠：age = 1 + 2 * 3 → 右操作数直接算成 7
        ASTNode.SelectStmt stmt = (ASTNode.SelectStmt) parse("SELECT * FROM t WHERE age = 1 + 2 * 3");
        ASTNode.BinaryExpr cond = (ASTNode.BinaryExpr) stmt.getWhereCond();
        ASTNode.LiteralExpr right = (ASTNode.LiteralExpr) cond.getRight();
        assertEquals(ASTNode.LiteralExpr.Kind.NUMBER, right.getKind());
        assertEquals("7", right.getValue(), "1+2*3 应折叠为 7");
    }

    @Test
    void whereAndBindsTighterThanOr() {
        // 优先级：a=1 AND b=2 OR c=3 根为 OR，左子树是 AND
        ASTNode.SelectStmt stmt = (ASTNode.SelectStmt) parse(
                "SELECT * FROM t WHERE a = 1 AND b = 2 OR c = 3");
        ASTNode.BinaryExpr root = (ASTNode.BinaryExpr) stmt.getWhereCond();
        assertEquals("OR", root.getOp(), "AND 优先级高于 OR，根应为 OR");
        assertEquals("AND", ((ASTNode.BinaryExpr) root.getLeft()).getOp());
    }

    @Test
    void whereNotUnary() {
        // NOT 一元运算：NOT a = 1 解析为 NOT(a = 1)，operand 是比较表达式
        ASTNode.SelectStmt stmt = (ASTNode.SelectStmt) parse("SELECT * FROM t WHERE NOT a = 1");
        ASTNode.UnaryExpr cond = (ASTNode.UnaryExpr) stmt.getWhereCond();
        assertEquals("NOT", cond.getOp());
        assertEquals("=", ((ASTNode.BinaryExpr) cond.getOperand()).getOp());
    }

    @Test
    void whereParenthesesOverridePrecedence() {
        // 括号改变结合：(a=1 OR b=2) AND c=3 根为 AND，左子树是 OR
        ASTNode.SelectStmt stmt = (ASTNode.SelectStmt) parse(
                "SELECT * FROM t WHERE (a = 1 OR b = 2) AND c = 3");
        ASTNode.BinaryExpr root = (ASTNode.BinaryExpr) stmt.getWhereCond();
        assertEquals("AND", root.getOp(), "括号优先，根应为 AND");
        assertEquals("OR", ((ASTNode.BinaryExpr) root.getLeft()).getOp());
    }

    @Test
    void whereAndTrueSimplified() {
        // 逻辑简化：age > 18 AND true → age > 18
        ASTNode.SelectStmt stmt = (ASTNode.SelectStmt) parse("SELECT * FROM t WHERE age > 18 AND true");
        ASTNode.BinaryExpr cond = (ASTNode.BinaryExpr) stmt.getWhereCond();
        assertEquals(">", cond.getOp(), "AND true 应化简掉");
    }

    @Test
    void whereOrTrueSimplifiedToTrue() {
        // 逻辑简化：cond OR true → 恒真 TRUE
        ASTNode.SelectStmt stmt = (ASTNode.SelectStmt) parse("SELECT * FROM t WHERE age > 18 OR true");
        ASTNode.LiteralExpr cond = (ASTNode.LiteralExpr) stmt.getWhereCond();
        assertEquals(ASTNode.LiteralExpr.Kind.BOOLEAN, cond.getKind());
        assertEquals("TRUE", cond.getValue());
    }

    @Test
    void whereDivideByZeroThrows() {
        // 常量折叠除零：1 / 0 在编译期抛出除零错误
        SqxdlException e = assertThrows(SqxdlException.class,
                () -> parse("SELECT * FROM t WHERE 1 / 0 = 1"));
        assertTrue(e.getMessage().contains("除零"), "除零应报 SqxdlException");
    }

    @Test
    void whereDoubleEqualsParsed() {
        // == 双等号：作为比较运算符原样保留在 AST 中
        ASTNode.SelectStmt stmt = (ASTNode.SelectStmt) parse("SELECT * FROM t WHERE a == 1");
        assertEquals("==", ((ASTNode.BinaryExpr) stmt.getWhereCond()).getOp());
    }

    // ========== SHOW / DROP 边界 ==========

    @Test
    void showTablesExtraTokenThrows() {
        // SHOW TABLES 是无参形式：多余的 a 应在语句收尾处报错
        SqxdlException e = assertThrows(SqxdlException.class, () -> parse("SHOW TABLES a;"));
        assertTrue(e.getMessage().contains("unexpected token 'a'"), "多余 token 应报错");
    }

    @Test
    void showTableMissingNameThrows() {
        // SHOW TABLE 后缺表名：报 unexpected token 并期望 IDENTIFIER
        SqxdlException e = assertThrows(SqxdlException.class, () -> parse("SHOW TABLE;"));
        assertTrue(e.getMessage().contains("unexpected token ';'"), "应报 unexpected token");
        assertTrue(e.getMessage().contains("IDENTIFIER"), "应期望 IDENTIFIER");
    }

    @Test
    void dropMissingTableKeywordThrows() {
        // DROP 后缺 TABLE：报 unexpected token 并期望关键字 TABLE
        SqxdlException e = assertThrows(SqxdlException.class, () -> parse("DROP t;"));
        assertTrue(e.getMessage().contains("unexpected token 't'"), "应报 unexpected token");
        assertTrue(e.getMessage().contains("TABLE"), "应期望关键字 TABLE");
    }

    @Test
    void dropTableMissingNameThrows() {
        // DROP TABLE 后缺表名：报 unexpected token 并期望 IDENTIFIER
        SqxdlException e = assertThrows(SqxdlException.class, () -> parse("DROP TABLE;"));
        assertTrue(e.getMessage().contains("unexpected token ';'"), "应报 unexpected token");
        assertTrue(e.getMessage().contains("IDENTIFIER"), "应期望 IDENTIFIER");
    }

    // ========== 错误恢复 / 多语句 ==========

    @Test
    void errorRecoverySkipsBadStatement() {
        // 第一条语句出错（WHERE 后无表达式），错误记入 getErrors()，
        // 但第二条 SELECT 仍被成功解析，不中断整体处理
        List<ASTNode> stmts = parseAll("SELECT * FROM t WHERE ; SELECT id FROM s;");
        assertEquals(1, stmts.size(), "应只解析出第二条语句");
        assertEquals("s", ((ASTNode.SelectStmt) stmts.get(0)).getTableName());

        Parser p = new Parser(new Lexer("SELECT * FROM t WHERE ; SELECT id FROM s;"));
        p.parseAll();
        assertEquals(1, p.getErrors().size(), "应记录 1 条语法错误");
    }

    @Test
    void createThenDropSequence() {
        // 多条语句连续：CREATE TABLE 与 DROP TABLE 依次解析，节点类型正确
        List<ASTNode> stmts = parseAll("CREATE TABLE t (id INT); DROP TABLE t;");
        assertEquals(2, stmts.size());
        assertTrue(stmts.get(0) instanceof ASTNode.CreateTableStmt, "第一条应为 CREATE TABLE");
        assertTrue(stmts.get(1) instanceof ASTNode.DropTableStmt, "第二条应为 DROP TABLE");
        assertNull(((ASTNode.DropTableStmt) stmts.get(1)).getType(), "预留 type 字段在语法阶段应为 null");
    }
}
