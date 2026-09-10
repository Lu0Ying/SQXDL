package com.sqxdl.parser;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parser 单元测试，按功能分为以下分组：
 * <ul>
 *   <li>SELECT 语句：列清单、*、多列、大小写、位置信息；</li>
 *   <li>多语句输入（parseAll）与错误恢复；</li>
 *   <li>WHERE 表达式：比较运算、逻辑 AND/OR（关键字与 &amp;&amp;、|| 双语法）、算术优先级；</li>
 *   <li>课程要求：优先级示例（a = 1 OR b = 2 AND c = 3）、NOT 一元运算、括号、错误格式；</li>
 *   <li>编译期优化：常量折叠与逻辑简化；</li>
 *   <li>INSERT / UPDATE / DELETE / CREATE TABLE / SHOW / DROP TABLE 语句。</li>
 * </ul>
 * 共 61 个用例。运行：mvn -pl parser test
 */
class ParserTest {

    /** 单语句解析：出错时抛出 SqxdlException */
    private ASTNode parse(String sql) {
        return new Parser(new Lexer(sql)).parse();
    }

    /** 多语句解析：出错时记入 getErrors() 并恢复继续 */
    private List<ASTNode> parseAll(String sql) {
        return new Parser(new Lexer(sql)).parseAll();
    }

    @Test
    void basicSelect() {
        // 最简 SELECT：单列、单表、无 WHERE；验证表名、列清单与 whereCond=null
        ASTNode.SelectStmt stmt = (ASTNode.SelectStmt) parse("SELECT id FROM t;");
        assertEquals("t", stmt.getTableName());
        assertEquals(List.of("id"), stmt.getSelectList());
        assertNull(stmt.getWhereCond(), "无 WHERE 子句时 whereCond 应为 null");
    }

    @Test
    void selectMultipleColumns() {
        // 多列清单：列名按书写顺序存入 selectList
        ASTNode.SelectStmt stmt = (ASTNode.SelectStmt) parse("SELECT id, name, age FROM student");
        assertEquals("student", stmt.getTableName());
        assertEquals(List.of("id", "name", "age"), stmt.getSelectList());
    }

    @Test
    void selectStarStoredAsAsterisk() {
        // SELECT * 的存储约定：selectList 存单元素 ["*"]，展开成真实列名由语义阶段完成
        ASTNode.SelectStmt stmt = (ASTNode.SelectStmt) parse("SELECT * FROM t;");
        assertEquals(List.of("*"), stmt.getSelectList(), "SELECT * 按约定存单元素 [\"*\"]");
    }

    @Test
    void keywordCaseInsensitive() {
        // 关键字不区分大小写：小写 select/from 与首字母大写等价
        ASTNode.SelectStmt stmt = (ASTNode.SelectStmt) parse("select id from t");
        assertEquals("t", stmt.getTableName());
        assertEquals(List.of("id"), stmt.getSelectList());
    }

    @Test
    void missingColumnListThrows() {
        // SELECT 后必须跟列清单：遇到关键字 FROM 说明缺列清单，报 unexpected token 并给出期望终结符
        SqxdlException e = assertThrows(SqxdlException.class, () -> parse("SELECT FROM t;"));
        assertTrue(e.getMessage().contains("unexpected token 'FROM'"), "缺列清单应报 unexpected token");
        assertTrue(e.getMessage().contains("IDENTIFIER"), "应给出期望标识符");
    }

    @Test
    void missingTableNameThrows() {
        // SELECT id FROM 后缺表名：应报 unexpected token，期望 IDENTIFIER
        SqxdlException e = assertThrows(SqxdlException.class, () -> parse("SELECT id FROM;"));
        assertTrue(e.getMessage().contains("IDENTIFIER"));
    }

    @Test
    void trailingContentAfterSemicolonThrows() {
        // 分号后的 garbage 既非分号也非下一条语句：单条 parse() 应报错（parseAll 则会错误恢复）
        SqxdlException e = assertThrows(SqxdlException.class,
                () -> parse("SELECT id FROM t; garbage"));
        assertTrue(e.getMessage().contains("unexpected token 'garbage'"), "应报 unexpected token");
    }

    // ========== 多语句输入与错误恢复（parseAll） ==========

    @Test
    void multipleStatementsParsedInOrder() {
        // parseAll 按顺序解析多条语句，并保持各语句的节点类型
        List<ASTNode> stmts = parseAll("SELECT id FROM t; INSERT INTO s VALUES (1); DELETE FROM u;");
        assertEquals(3, stmts.size());
        assertTrue(stmts.get(0) instanceof ASTNode.SelectStmt);
        assertTrue(stmts.get(1) instanceof ASTNode.InsertStmt);
        assertTrue(stmts.get(2) instanceof ASTNode.DeleteStmt);
    }

    @Test
    void multipleStatementsWithoutTrailingSemicolon() {
        // 最后一条语句的分号可省略（宽容约定）
        List<ASTNode> stmts = parseAll("SELECT id FROM t; SELECT name FROM t");
        assertEquals(2, stmts.size());
    }

    @Test
    void emptyInputReturnsNoStatements() {
        // 空输入与纯分号输入都不产生语句（分号仅作分隔符）
        assertTrue(parseAll("").isEmpty());
        assertTrue(parseAll(";;;").isEmpty(), "只有分号视为空输入");
    }

    @Test
    void mixedCreateSelectUpdate() {
        // CREATE / INSERT / UPDATE 混合输入，验证语句分发层按首关键字正确分发
        List<ASTNode> stmts = parseAll(
                "CREATE TABLE t (a INT, b VARCHAR); INSERT INTO t VALUES (1, 2); UPDATE t SET a = 3;");
        assertEquals(3, stmts.size());
        assertTrue(stmts.get(0) instanceof ASTNode.CreateTableStmt);
        assertTrue(stmts.get(2) instanceof ASTNode.UpdateStmt);
    }

    @Test
    void statementTrailingJunkRecoveredInParseAll() {
        // parseAll 具备错误恢复：'SELECT id FROM t 42' 中的 42 既非分号也非语句，
        // 应记录错误并跳过，而不是中断整体解析
        Parser p = new Parser(new Lexer("SELECT id FROM t 42"));
        List<ASTNode> stmts = p.parseAll();
        assertTrue(stmts.isEmpty(), "损坏的语句不应产出 AST");
        assertEquals(1, p.getErrors().size(), "应记录 1 条语法错误");
    }

    @Test
    void parseAllCollectsSyntaxErrors() {
        // parseAll 把语法错误收集到 getErrors()，供调用方统一查看，不中断整体解析
        Parser p = new Parser(new Lexer("SELECT id FROM t 42"));
        p.parseAll();
        assertEquals(1, p.getErrors().size(), "应收集到 1 条语法错误");
        assertTrue(p.getErrors().get(0).getMessage().contains("unexpected token '42'"));
    }

    @Test
    void parseAllErrorRecoverySkipsBadStatement() {
        // 坏语句被跳过，后续语句正常解析
        Parser p = new Parser(new Lexer("BAD STUFF; SELECT id FROM t;"));
        List<ASTNode> stmts = p.parseAll();
        assertEquals(1, stmts.size());
        assertTrue(stmts.get(0) instanceof ASTNode.SelectStmt);
        assertEquals(1, p.getErrors().size(), "应记录坏语句的错误");
    }

    @Test
    void parseAllErrorRecoveryContinuesAfterBrokenWhere() {
        // WHERE 表达式出错后跳过该语句，继续解析下一条
        Parser p = new Parser(new Lexer("SELECT id FROM t WHERE ; SELECT name FROM u;"));
        List<ASTNode> stmts = p.parseAll();
        assertEquals(1, stmts.size());
        assertEquals("u", ((ASTNode.SelectStmt) stmts.get(0)).getTableName());
        assertEquals(1, p.getErrors().size());
    }

    @Test
    void missingSelectKeywordThrows() {
        // 以标识符开头不是合法语句，由 parse() 分发层拦截，列出期望的语句关键字
        SqxdlException e = assertThrows(SqxdlException.class, () -> parse("id FROM t;"));
        assertTrue(e.getMessage().contains("unexpected token 'id'"));
        assertTrue(e.getMessage().contains("SELECT | INSERT | UPDATE | DELETE | CREATE"), "应列出期望的语句关键字");
    }

    @Test
    void nodeCarriesPosition() {
        // 语句节点携带源码位置：行号/列号应指向 SELECT 关键字
        ASTNode.SelectStmt stmt = (ASTNode.SelectStmt) parse("SELECT id FROM t");
        assertEquals(1, stmt.getLine());
        assertEquals(1, stmt.getCol(), "SelectStmt 位置应指向 SELECT 关键字");
    }

    // ========== WHERE 与表达式（比较 / 逻辑 / 算术优先级） ==========

    @Test
    void whereSimpleComparison() {
        // 单比较：age >= 18 → BinaryExpr(">=", age, 18)，左操作数为列、右操作数为常量
        ASTNode.SelectStmt stmt = (ASTNode.SelectStmt) parse("SELECT id FROM t WHERE age >= 18");
        ASTNode.BinaryExpr cond = (ASTNode.BinaryExpr) stmt.getWhereCond();
        assertEquals(">=", cond.getOp());
        assertEquals("age", ((ASTNode.IdentifierExpr) cond.getLeft()).getName());
        assertEquals("18", ((ASTNode.LiteralExpr) cond.getRight()).getValue());
    }

    @Test
    void whereLeftIsColumnRightIsColumn() {
        // 列对列比较：a = b，左右操作数均为 IdentifierExpr
        ASTNode.SelectStmt stmt = (ASTNode.SelectStmt) parse("SELECT * FROM t WHERE a = b");
        ASTNode.BinaryExpr cond = (ASTNode.BinaryExpr) stmt.getWhereCond();
        assertEquals("a", ((ASTNode.IdentifierExpr) cond.getLeft()).getName());
        assertEquals("b", ((ASTNode.IdentifierExpr) cond.getRight()).getName());
    }

    @Test
    void whereStringLiteralKind() {
        // 字符串字面量：kind=STRING，词素不含引号
        ASTNode.SelectStmt stmt = (ASTNode.SelectStmt) parse("SELECT * FROM t WHERE name = 'Tom'");
        ASTNode.BinaryExpr cond = (ASTNode.BinaryExpr) stmt.getWhereCond();
        ASTNode.LiteralExpr right = (ASTNode.LiteralExpr) cond.getRight();
        assertEquals(ASTNode.LiteralExpr.Kind.STRING, right.getKind());
        assertEquals("Tom", right.getValue());
    }

    @Test
    void whereArithmeticAssociatesLeft() {
        // 优先级：age = (1 + 2*x) 而不是 (age = 1) + 2*x；含列 x 的部分保持树形
        ASTNode.SelectStmt stmt = (ASTNode.SelectStmt) parse("SELECT * FROM t WHERE age = 1 + 2 * x");
        ASTNode.BinaryExpr cond = (ASTNode.BinaryExpr) stmt.getWhereCond();
        assertEquals("=", cond.getOp());
        ASTNode.BinaryExpr right = (ASTNode.BinaryExpr) cond.getRight();
        assertEquals("+", right.getOp());
        assertEquals("1", ((ASTNode.LiteralExpr) right.getLeft()).getValue());
        ASTNode.BinaryExpr mul = (ASTNode.BinaryExpr) right.getRight();
        assertEquals("*", mul.getOp(), "* 优先级高于 +");
        assertEquals("2", ((ASTNode.LiteralExpr) mul.getLeft()).getValue());
        assertEquals("x", ((ASTNode.IdentifierExpr) mul.getRight()).getName());
    }

    @Test
    void whereLogicalAnd() {
        // AND 关键字连接两个比较：运算符统一规范化为大写
        ASTNode.SelectStmt stmt = (ASTNode.SelectStmt) parse("SELECT * FROM t WHERE age >= 18 AND name != 'Tom'");
        ASTNode.BinaryExpr cond = (ASTNode.BinaryExpr) stmt.getWhereCond();
        assertEquals("AND", cond.getOp(), "AND 关键字应规范化为大写");
    }

    @Test
    void whereSymbolicAndOrBothSupported() {
        // && 符号与 || 符号写法与关键字等价，且 || 优先级低于 &&
        ASTNode.SelectStmt stmt = (ASTNode.SelectStmt) parse("SELECT * FROM t WHERE a = 1 && b = 2 || c = 3");
        ASTNode.BinaryExpr or = (ASTNode.BinaryExpr) stmt.getWhereCond();
        assertEquals("||", or.getOp(), "|| 优先级低于 &&");
        assertEquals("&&", ((ASTNode.BinaryExpr) or.getLeft()).getOp());
    }

    @Test
    void whereMissingRightOperandThrows() {
        // WHERE age > 后缺右操作数：在 EOF 处报 unexpected token，并给出表达式操作数的期望终结符
        SqxdlException e = assertThrows(SqxdlException.class, () -> parse("SELECT * FROM t WHERE age >"));
        assertTrue(e.getMessage().contains("unexpected token 'EOF'"), "缺右操作数应报错");
        assertTrue(e.getMessage().contains("IDENTIFIER | CONST | '(' | ')' | NOT"), "应给出期望终结符列表");
    }

    // ========== 课程要求：优先级 / NOT / 括号 / 错误格式 ==========

    @Test
    void whereTeacherPrecedenceExample() {
        // 课程要求示例：a = 1 OR b = 2 AND c = 3 应解析为 a = 1 OR (b = 2 AND c = 3)，
        // 即 AND 优先级高于 OR，结合性体现在树的嵌套中
        ASTNode.SelectStmt stmt = (ASTNode.SelectStmt) parse("SELECT * FROM t WHERE a = 1 OR b = 2 AND c = 3");
        ASTNode.BinaryExpr or = (ASTNode.BinaryExpr) stmt.getWhereCond();
        assertEquals("OR", or.getOp(), "根节点应为 OR（优先级最低）");
        assertEquals("=", ((ASTNode.BinaryExpr) or.getLeft()).getOp(), "OR 左操作数应为 a = 1");
        ASTNode.BinaryExpr and = (ASTNode.BinaryExpr) or.getRight();
        assertEquals("AND", and.getOp(), "AND 优先级高于 OR，右操作数应为 b=2 AND c=3");
        assertEquals("=", ((ASTNode.BinaryExpr) and.getLeft()).getOp());
        assertEquals("=", ((ASTNode.BinaryExpr) and.getRight()).getOp());
    }

    @Test
    void whereNotBindsToComparison() {
        // 按文法 not_expr -> NOT not_expr | comparison：NOT age > 18 → NOT(age > 18)，
        // NOT 作用于整个比较表达式（优先级低于比较运算、高于 AND/OR）
        ASTNode.SelectStmt stmt = (ASTNode.SelectStmt) parse("SELECT * FROM t WHERE NOT age > 18");
        ASTNode.UnaryExpr not = (ASTNode.UnaryExpr) stmt.getWhereCond();
        assertEquals("NOT", not.getOp());
        ASTNode.BinaryExpr comp = (ASTNode.BinaryExpr) not.getOperand();
        assertEquals(">", comp.getOp(), "NOT 的操作数应为整个比较表达式");
    }

    @Test
    void whereNotAndPrecedence() {
        // NOT a = 1 AND b = 2 → (NOT a=1) AND (b=2)：NOT 优先级高于 AND
        ASTNode.SelectStmt stmt = (ASTNode.SelectStmt) parse("SELECT * FROM t WHERE NOT a = 1 AND b = 2");
        ASTNode.BinaryExpr and = (ASTNode.BinaryExpr) stmt.getWhereCond();
        assertEquals("AND", and.getOp());
        assertEquals("NOT", ((ASTNode.UnaryExpr) and.getLeft()).getOp());
        assertEquals("=", ((ASTNode.BinaryExpr) and.getRight()).getOp());
    }

    @Test
    void whereNotChain() {
        // 连续 NOT 右递归：NOT NOT a = 1 → NOT(NOT(a = 1))
        ASTNode.SelectStmt stmt = (ASTNode.SelectStmt) parse("SELECT * FROM t WHERE NOT NOT a = 1");
        ASTNode.UnaryExpr outer = (ASTNode.UnaryExpr) stmt.getWhereCond();
        ASTNode.UnaryExpr inner = (ASTNode.UnaryExpr) outer.getOperand();
        assertEquals("NOT", inner.getOp());
        assertEquals("=", ((ASTNode.BinaryExpr) inner.getOperand()).getOp());
    }

    @Test
    void whereParenthesizedOverridesPrecedence() {
        // 括号提高 OR 的优先级：a = 1 AND (b = 2 OR c = 3)，括号不产生额外节点
        ASTNode.SelectStmt stmt = (ASTNode.SelectStmt) parse("SELECT * FROM t WHERE a = 1 AND (b = 2 OR c = 3)");
        ASTNode.BinaryExpr and = (ASTNode.BinaryExpr) stmt.getWhereCond();
        assertEquals("AND", and.getOp());
        ASTNode.BinaryExpr or = (ASTNode.BinaryExpr) and.getRight();
        assertEquals("OR", or.getOp(), "括号内的 OR 应与外层的 AND 直接结合");
    }

    @Test
    void whereParenthesizedGroupAtRoot() {
        // (a = 1 OR b = 2) AND c = 3：括号改变结合结构，OR 组整体作为 AND 左操作数
        ASTNode.SelectStmt stmt = (ASTNode.SelectStmt) parse("SELECT * FROM t WHERE (a = 1 OR b = 2) AND c = 3");
        ASTNode.BinaryExpr and = (ASTNode.BinaryExpr) stmt.getWhereCond();
        assertEquals("AND", and.getOp());
        assertEquals("OR", ((ASTNode.BinaryExpr) and.getLeft()).getOp());
    }

    @Test
    void whereParenthesizedExpressionConstantFolds() {
        // 括号内同样参与常量折叠：x = (1 + 2) → x = 3
        ASTNode.SelectStmt stmt = (ASTNode.SelectStmt) parse("SELECT * FROM t WHERE x = (1 + 2)");
        ASTNode.BinaryExpr cond = (ASTNode.BinaryExpr) stmt.getWhereCond();
        assertEquals("3", ((ASTNode.LiteralExpr) cond.getRight()).getValue());
    }

    @Test
    void whereUnterminatedParenThrows() {
        // 括号未闭合：在 EOF 处报 unexpected token，期望分隔符 ')'
        SqxdlException e = assertThrows(SqxdlException.class,
                () -> parse("SELECT * FROM t WHERE (a = 1"));
        assertTrue(e.getMessage().contains("unexpected token 'EOF'"), "缺右括号应报 unexpected token");
        assertTrue(e.getMessage().contains("')'"), "应给出期望分隔符 ')'");
    }

    @Test
    void errorMessageTeacherExample() {
        // 课程示例：SELECT name FROM student WHERE age > 18 AND;
        // 应在第 3 行第 19 列报 unexpected token ';'，并给出期望终结符列表
        String sql = "SELECT name\nFROM student\nWHERE age > 18 AND;";
        SqxdlException e = assertThrows(SqxdlException.class, () -> parse(sql));
        assertEquals(3, e.getLine());
        assertEquals(19, e.getCol());
        assertTrue(e.getMessage().contains("unexpected token ';'"));
        assertTrue(e.getMessage().contains("IDENTIFIER | CONST | '(' | ')' | NOT"), "应给出期望终结符列表");
    }

    // ========== 编译期优化：常量折叠与逻辑简化 ==========

    @Test
    void constantFoldArithmetic() {
        // age = 1+2 → age = 3：常量表达式在 AST 构建时即被折叠
        ASTNode.SelectStmt stmt = (ASTNode.SelectStmt) parse("SELECT * FROM t WHERE age = 1 + 2");
        ASTNode.BinaryExpr cond = (ASTNode.BinaryExpr) stmt.getWhereCond();
        assertEquals("=", cond.getOp());
        ASTNode.LiteralExpr right = (ASTNode.LiteralExpr) cond.getRight();
        assertEquals(ASTNode.LiteralExpr.Kind.NUMBER, right.getKind());
        assertEquals("3", right.getValue());
    }

    @Test
    void constantFoldNestedExpression() {
        // 2*3 在内部先折叠：(1 + 2*3) → (1 + 6) → 7，折叠按优先级自底向上
        ASTNode.SelectStmt stmt = (ASTNode.SelectStmt) parse("SELECT * FROM t WHERE x = 1 + 2 * 3");
        ASTNode.BinaryExpr cond = (ASTNode.BinaryExpr) stmt.getWhereCond();
        assertEquals("7", ((ASTNode.LiteralExpr) cond.getRight()).getValue());
    }

    @Test
    void constantFoldDecimal() {
        // 小数常量同样参与折叠：1.5 + 2 → 3.5
        ASTNode.SelectStmt stmt = (ASTNode.SelectStmt) parse("SELECT * FROM t WHERE x = 1.5 + 2");
        ASTNode.BinaryExpr cond = (ASTNode.BinaryExpr) stmt.getWhereCond();
        assertEquals("3.5", ((ASTNode.LiteralExpr) cond.getRight()).getValue());
    }

    @Test
    void constantFoldDivisionByZeroThrows() {
        // 除零在折叠阶段即报运行时异常（属语义层面错误，非语法错误）
        SqxdlException e = assertThrows(SqxdlException.class,
                () -> parse("SELECT * FROM t WHERE x = 1 / 0"));
        assertTrue(e.getMessage().contains("除零错误"));
    }

    @Test
    void logicalSimplifyAndTrue() {
        // cond AND true → cond（老师要求）：true 被消除，剩下 age > 18
        ASTNode.SelectStmt stmt = (ASTNode.SelectStmt) parse("SELECT * FROM t WHERE age > 18 AND true");
        ASTNode.BinaryExpr cond = (ASTNode.BinaryExpr) stmt.getWhereCond();
        assertEquals(">", cond.getOp(), "true 应被消除，剩下 age > 18");
    }

    @Test
    void logicalSimplifyAndFalse() {
        // cond AND false → false：整棵条件树化简为布尔常量 FALSE
        ASTNode.SelectStmt stmt = (ASTNode.SelectStmt) parse("SELECT * FROM t WHERE age > 18 && false");
        ASTNode.LiteralExpr cond = (ASTNode.LiteralExpr) stmt.getWhereCond();
        assertEquals(ASTNode.LiteralExpr.Kind.BOOLEAN, cond.getKind());
        assertEquals("FALSE", cond.getValue());
    }

    @Test
    void logicalSimplifyOrFalse() {
        // cond OR false → cond：false 被消除，剩下 age > 18
        ASTNode.SelectStmt stmt = (ASTNode.SelectStmt) parse("SELECT * FROM t WHERE age > 18 OR false");
        ASTNode.BinaryExpr cond = (ASTNode.BinaryExpr) stmt.getWhereCond();
        assertEquals(">", cond.getOp());
    }

    @Test
    void logicalSimplifyOrTrue() {
        // cond OR true → true：恒真条件，整棵树化简为布尔常量 TRUE
        ASTNode.SelectStmt stmt = (ASTNode.SelectStmt) parse("SELECT * FROM t WHERE age > 18 || true");
        ASTNode.LiteralExpr cond = (ASTNode.LiteralExpr) stmt.getWhereCond();
        assertEquals("TRUE", cond.getValue());
    }

    @Test
    void booleanKeywordCaseInsensitive() {
        // true/false 不区分大小写：小写 true 在 AST 中统一存大写 TRUE
        ASTNode.SelectStmt stmt = (ASTNode.SelectStmt) parse("SELECT * FROM t WHERE active = true");
        ASTNode.BinaryExpr cond = (ASTNode.BinaryExpr) stmt.getWhereCond();
        ASTNode.LiteralExpr right = (ASTNode.LiteralExpr) cond.getRight();
        assertEquals(ASTNode.LiteralExpr.Kind.BOOLEAN, right.getKind());
        assertEquals("TRUE", right.getValue(), "小写 true 应规范化为大写");
    }

    // ========== INSERT / UPDATE / DELETE / CREATE TABLE 语句 ==========

    @Test
    void insertWithColumns() {
        // INSERT 指定列清单：列名与值一一对应，值类型区分 NUMBER / STRING
        ASTNode.InsertStmt stmt = (ASTNode.InsertStmt) parse(
                "INSERT INTO student (id, name) VALUES (1, 'Tom');");
        assertEquals("student", stmt.getTableName());
        assertEquals(List.of("id", "name"), stmt.getColumns());
        assertEquals(2, stmt.getValues().size());
        assertEquals(ASTNode.LiteralExpr.Kind.NUMBER, stmt.getValues().get(0).getKind());
        assertEquals(ASTNode.LiteralExpr.Kind.STRING, stmt.getValues().get(1).getKind());
        assertEquals("Tom", stmt.getValues().get(1).getValue());
    }

    @Test
    void insertWithoutColumns() {
        // INSERT 省略列清单：columns 为空列表，表示按建表顺序对应 VALUES
        ASTNode.InsertStmt stmt = (ASTNode.InsertStmt) parse(
                "INSERT INTO student VALUES (1, 'Tom')");
        assertEquals(0, stmt.getColumns().size(), "未指定列清单时应为空列表");
    }

    @Test
    void insertMissingValuesKeywordThrows() {
        // INSERT 的列清单后必须跟 VALUES：遇到 '(' 应报 unexpected token 并列出期望关键字
        SqxdlException e = assertThrows(SqxdlException.class,
                () -> parse("INSERT INTO t (a) (1)"));
        assertTrue(e.getMessage().contains("unexpected token '('"), "应报 unexpected token");
        assertTrue(e.getMessage().contains("VALUES"), "应给出期望关键字 VALUES");
    }

    @Test
    void updateSingleAssignment() {
        // UPDATE 单赋值 + WHERE：校验表名、赋值值与条件树
        ASTNode.UpdateStmt stmt = (ASTNode.UpdateStmt) parse("UPDATE student SET age = 20 WHERE id = 1;");
        assertEquals("student", stmt.getTableName());
        assertEquals(1, stmt.getAssignments().size());
        assertEquals("20", stmt.getAssignments().get("age").getValue());
        ASTNode.BinaryExpr where = (ASTNode.BinaryExpr) stmt.getWhereCond();
        assertEquals("id", ((ASTNode.IdentifierExpr) where.getLeft()).getName());
    }

    @Test
    void updateMultipleAssignments() {
        // UPDATE 多赋值：SET 子句支持逗号分隔的多个 列=值；无 WHERE 时 whereCond 为 null
        ASTNode.UpdateStmt stmt = (ASTNode.UpdateStmt) parse(
                "UPDATE student SET age = 20, name = 'Bob'");
        assertEquals(Map.of("age", 1, "name", 2).keySet(), stmt.getAssignments().keySet());
        assertNull(stmt.getWhereCond());
    }

    @Test
    void deleteWithWhere() {
        // DELETE 带 WHERE：校验表名与条件树（age < 18）
        ASTNode.DeleteStmt stmt = (ASTNode.DeleteStmt) parse("DELETE FROM student WHERE age < 18");
        assertEquals("student", stmt.getTableName());
        assertEquals("<", ((ASTNode.BinaryExpr) stmt.getWhereCond()).getOp());
    }

    @Test
    void deleteWithoutWhere() {
        // DELETE 不带 WHERE：whereCond 为 null（即删除全表）
        ASTNode.DeleteStmt stmt = (ASTNode.DeleteStmt) parse("DELETE FROM student");
        assertNull(stmt.getWhereCond());
    }

    @Test
    void createTable() {
        // CREATE TABLE：每列由 列名 + 类型（INT | VARCHAR）组成 ColumnDef
        ASTNode.CreateTableStmt stmt = (ASTNode.CreateTableStmt) parse(
                "CREATE TABLE student (id INT, name VARCHAR, age INT);");
        assertEquals("student", stmt.getTableName());
        List<ASTNode.CreateTableStmt.ColumnDef> cols = stmt.getColumns();
        assertEquals(3, cols.size());
        assertEquals("id", cols.get(0).getName());
        assertEquals("INT", cols.get(0).getType());
        assertEquals("name", cols.get(1).getName());
        assertEquals("VARCHAR", cols.get(1).getType());
        assertEquals("age", cols.get(2).getName());
        assertEquals("INT", cols.get(2).getType());
    }

    @Test
    void createTableTypeCaseInsensitive() {
        // 类型关键字不区分大小写：小写 int/varchar 统一存大写
        ASTNode.CreateTableStmt stmt = (ASTNode.CreateTableStmt) parse(
                "CREATE TABLE t (id int, name varchar)");
        assertEquals("INT", stmt.getColumns().get(0).getType(), "类型应统一存大写");
        assertEquals("VARCHAR", stmt.getColumns().get(1).getType());
    }

    @Test
    void createTableMissingColumnTypeThrows() {
        // column_def -> IDENTIFIER type，列后缺类型：报 unexpected token 并给出期望类型
        SqxdlException e = assertThrows(SqxdlException.class,
                () -> parse("CREATE TABLE t (id)"));
        assertTrue(e.getMessage().contains("unexpected token ')'"), "列后缺类型应报错");
        assertTrue(e.getMessage().contains("INT | VARCHAR"), "应给出期望类型");
    }

    @Test
    void createTableMissingClosingParenThrows() {
        // 列定义清单未闭合：在 EOF 处报 unexpected token，期望分隔符 ')'
        SqxdlException e = assertThrows(SqxdlException.class,
                () -> parse("CREATE TABLE t (id INT, name VARCHAR"));
        assertTrue(e.getMessage().contains("unexpected token 'EOF'"), "缺右括号应报 unexpected token");
        assertTrue(e.getMessage().contains("')'"), "应给出期望分隔符");
    }

    @Test
    void unknownStatementThrows() {
        // 不支持的语句关键字（如 ALTER）由语句分发层拦截，列出期望的语句关键字
        SqxdlException e = assertThrows(SqxdlException.class, () -> parse("ALTER TABLE t"));
        assertTrue(e.getMessage().contains("unexpected token 'ALTER'"));
        assertTrue(e.getMessage().contains("SELECT | INSERT | UPDATE | DELETE | CREATE | SHOW | DROP"));
    }

    // ========== SHOW 语句 ==========

    @Test
    void showTables() {
        // SHOW TABLES：target 为 TABLES，无表名；关键字不区分大小写
        ASTNode.ShowStmt stmt = (ASTNode.ShowStmt) parse("show tables;");
        assertEquals("TABLES", stmt.getTarget(), "target 应统一存大写");
        assertNull(stmt.getTableName(), "SHOW TABLES 不应有表名");
    }

    @Test
    void showTableWithName() {
        // SHOW TABLE name：target 为 TABLE，tableName 为指定表名
        ASTNode.ShowStmt stmt = (ASTNode.ShowStmt) parse("SHOW TABLE student");
        assertEquals("TABLE", stmt.getTarget());
        assertEquals("student", stmt.getTableName());
    }

    @Test
    void showMissingTargetThrows() {
        // SHOW 后必须跟 TABLE | TABLES：缺少目标时报 unexpected token 并给出期望
        SqxdlException e = assertThrows(SqxdlException.class, () -> parse("SHOW;"));
        assertTrue(e.getMessage().contains("unexpected token ';'"), "应报 unexpected token");
        assertTrue(e.getMessage().contains("TABLE | TABLES"), "应给出期望的 SHOW 目标");
    }

    @Test
    void showTableMissingNameThrows() {
        // SHOW TABLE 后必须跟表名：缺表名时报 unexpected token，期望 IDENTIFIER
        SqxdlException e = assertThrows(SqxdlException.class, () -> parse("SHOW TABLE;"));
        assertTrue(e.getMessage().contains("unexpected token ';'"), "应报 unexpected token");
        assertTrue(e.getMessage().contains("IDENTIFIER"), "应给出期望 IDENTIFIER");
    }

    @Test
    void describeTableNormalizedToTargetTable() {
        // DESCRIBE t 是 SHOW TABLE t 的等价写法，归一化为 target=TABLE
        ASTNode.ShowStmt stmt = (ASTNode.ShowStmt) parse("DESCRIBE student");
        assertEquals("TABLE", stmt.getTarget(), "DESCRIBE 应归一化为 target=TABLE");
        assertEquals("student", stmt.getTableName());
    }

    @Test
    void descAbbreviationSupported() {
        // DESC 是 DESCRIBE 的缩写，同样归一化为 target=TABLE
        ASTNode.ShowStmt stmt = (ASTNode.ShowStmt) parse("desc student");
        assertEquals("TABLE", stmt.getTarget(), "DESC 不区分大小写且归一化为 target=TABLE");
        assertEquals("student", stmt.getTableName());
    }

    @Test
    void describeMissingNameThrows() {
        // DESCRIBE 后必须跟表名：缺表名时报 unexpected token，期望 IDENTIFIER
        SqxdlException e = assertThrows(SqxdlException.class, () -> parse("DESCRIBE;"));
        assertTrue(e.getMessage().contains("unexpected token ';'"), "应报 unexpected token");
        assertTrue(e.getMessage().contains("IDENTIFIER"), "应给出期望 IDENTIFIER");
    }

    @Test
    void showTablesFollowedByDescribeInMultiStatements() {
        // 多语句：SHOW TABLES 与 DESCRIBE 连续解析，且两者都归一化/识别正确
        List<ASTNode> stmts = parseAll("SHOW TABLES; DESCRIBE student;");
        assertEquals(2, stmts.size());
        assertEquals("TABLES", ((ASTNode.ShowStmt) stmts.get(0)).getTarget());
        assertEquals("TABLE", ((ASTNode.ShowStmt) stmts.get(1)).getTarget());
        assertEquals("student", ((ASTNode.ShowStmt) stmts.get(1)).getTableName());
    }

    // ========== DROP TABLE 语句 ==========

    @Test
    void dropTable() {
        // DROP TABLE：校验表名；关键字不区分大小写
        ASTNode.DropTableStmt stmt = (ASTNode.DropTableStmt) parse("drop table student;");
        assertEquals("student", stmt.getTableName());
    }

    @Test
    void dropMissingTableKeywordThrows() {
        // DROP 后必须跟 TABLE：缺 TABLE 时报 unexpected token 并给出期望关键字
        SqxdlException e = assertThrows(SqxdlException.class, () -> parse("DROP student"));
        assertTrue(e.getMessage().contains("unexpected token 'student'"), "应报 unexpected token");
        assertTrue(e.getMessage().contains("TABLE"), "应给出期望关键字 TABLE");
    }

    @Test
    void dropMissingNameThrows() {
        // DROP TABLE 后必须跟表名：缺表名时报 unexpected token，期望 IDENTIFIER
        SqxdlException e = assertThrows(SqxdlException.class, () -> parse("DROP TABLE;"));
        assertTrue(e.getMessage().contains("unexpected token ';'"), "应报 unexpected token");
        assertTrue(e.getMessage().contains("IDENTIFIER"), "应给出期望 IDENTIFIER");
    }
}
