package com.sqxdl.parser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 语法分析器（A 组）。
 * 职责：消费 {@link Lexer} 产出的 Token 流，递归下降构建 {@link ASTNode} 语法树。
 *
 * <p>实现说明：
 * <ul>
 *   <li>持有 {@link Lexer} 并维护单 Token 向前看缓存 {@code current}；</li>
 *   <li>{@link #peek()} / {@link #advance()} / {@link #expect(Type)} 是递归下降的三个基础工具；</li>
 *   <li>语法错误统一由 {@link #expect(Type)} 抛出带行列号的 {@link SqxdlException}。</li>
 * </ul>
 */
public class Parser {

    /** 运算符优先级分层（低 → 高）：|| < && < 比较 < 加减 < 乘除 */
    private static final Set<String> COMPARE_OPS = Set.of("=", "<", ">", "<=", ">=", "!=", "==");
    private static final Set<String> ADD_OPS = Set.of("+", "-");
    private static final Set<String> MUL_OPS = Set.of("*", "/");

    private final Lexer lexer;

    /** 向前看缓存：当前待处理的 Token，初始为第一个 Token */
    private Token current;

    public Parser(Lexer lexer) {
        this.lexer = lexer;
        this.current = lexer.nextToken();
    }

    /**
     * 解析完整 SQL 语句，按首关键字分发到对应语句的解析方法。
     *
     * @return 语法树根节点（SelectStmt / InsertStmt / UpdateStmt / DeleteStmt / CreateTableStmt）
     */
    public ASTNode parse() {
        Token t = peek();
        if (t.getType() == Token.Type.KEYWORD) {
            switch (t.getLexeme().toUpperCase()) {
                case "SELECT" -> {
                    return parseSelect();
                }
                case "INSERT" -> {
                    return parseInsert();
                }
                case "UPDATE" -> {
                    return parseUpdate();
                }
                case "DELETE" -> {
                    return parseDelete();
                }
                case "CREATE" -> {
                    return parseCreateTable();
                }
                default -> {
                }
            }
        }
        throw new SqxdlException(t.getLine(), t.getCol(),
                "语法错误：无法识别的语句，以 '" + t.getLexeme() + "' 开头");
    }

    /**
     * 语句收尾：消费可选分号（容忍连续分号），然后要求到达 EOF 或下一条语句开头。
     * 单语句调用 parse() 时行为不变；多语句由 parseAll() 循环驱动。
     */
    private void finishStatement() {
        while (peek().getType() == Token.Type.DELIMITER && ";".equals(peek().getLexeme())) {
            advance();
        }
        Token t = peek();
        boolean nextStatement = t.getType() == Token.Type.KEYWORD;
        if (t.getType() != Token.Type.EOF && !nextStatement) {
            throw new SqxdlException(t.getLine(), t.getCol(),
                    "语法错误：期望分号或下一条语句，但遇到 '" + t.getLexeme() + "'");
        }
    }

    /**
     * 解析多条 SQL 语句（分号分隔），直到输入结束。
     * 例："SELECT 1; SELECT 2" → 两条语句；空输入 → 空列表。
     *
     * @return 全部语句的 AST 节点列表
     */
    public List<ASTNode> parseAll() {
        List<ASTNode> stmts = new ArrayList<>();
        while (peek().getType() != Token.Type.EOF) {
            // 跳过前导/连续分号（空语句）
            if (peek().getType() == Token.Type.DELIMITER && ";".equals(peek().getLexeme())) {
                advance();
                continue;
            }
            stmts.add(parse());
        }
        return stmts;
    }

    /**
     * 解析 SELECT 语句：SELECT 列清单 FROM 表名 [WHERE 条件] [;]
     *
     * @return SelectStmt 节点
     */
    private ASTNode parseSelect() {
        Token start = expectKeyword("SELECT");
        List<String> columns = parseSelectList();
        expectKeyword("FROM");
        Token table = expect(Token.Type.IDENTIFIER);
        ASTNode whereCond = null;
        if (peek().getType() == Token.Type.KEYWORD && peek().getLexeme().equalsIgnoreCase("WHERE")) {
            whereCond = parseWhere();
        }
        finishStatement();
        return new ASTNode.SelectStmt(start.getLine(), start.getCol(),
                table.getLexeme(), columns, whereCond);
    }

    /**
     * 解析查询列清单：列名 (逗号 列名)*，或 SELECT * 时按约定存单元素 ["*"]。
     *
     * @return 列名列表
     */
    private List<String> parseSelectList() {
        List<String> columns = new ArrayList<>();
        if (peek().getType() == Token.Type.OPERATOR && "*".equals(peek().getLexeme())) {
            advance();
            columns.add("*");
        } else {
            columns.add(expect(Token.Type.IDENTIFIER).getLexeme());
            while (peek().getType() == Token.Type.DELIMITER && ",".equals(peek().getLexeme())) {
                advance();
                columns.add(expect(Token.Type.IDENTIFIER).getLexeme());
            }
        }
        return columns;
    }

    /**
     * 解析 INSERT 语句：INSERT INTO tableName [(列清单)] VALUES (值清单)
     *
     * @return InsertStmt 节点
     */
    private ASTNode parseInsert() {
        Token start = expectKeyword("INSERT");
        expectKeyword("INTO");
        Token table = expect(Token.Type.IDENTIFIER);
        List<String> columns = new ArrayList<>();
        if (peek().getType() == Token.Type.DELIMITER && "(".equals(peek().getLexeme())) {
            advance();
            columns.add(expect(Token.Type.IDENTIFIER).getLexeme());
            while (peek().getType() == Token.Type.DELIMITER && ",".equals(peek().getLexeme())) {
                advance();
                columns.add(expect(Token.Type.IDENTIFIER).getLexeme());
            }
            expectDelimiter(")");
        }
        expectKeyword("VALUES");
        expectDelimiter("(");
        List<ASTNode.LiteralExpr> values = new ArrayList<>();
        values.add(expectLiteral());
        while (peek().getType() == Token.Type.DELIMITER && ",".equals(peek().getLexeme())) {
            advance();
            values.add(expectLiteral());
        }
        expectDelimiter(")");
        finishStatement();
        return new ASTNode.InsertStmt(start.getLine(), start.getCol(),
                table.getLexeme(), columns, values);
    }

    /**
     * 解析 UPDATE 语句：UPDATE tableName SET col = 值 (逗号 col = 值)* [WHERE 条件]
     *
     * @return UpdateStmt 节点
     */
    private ASTNode parseUpdate() {
        Token start = expectKeyword("UPDATE");
        Token table = expect(Token.Type.IDENTIFIER);
        expectKeyword("SET");
        Map<String, ASTNode.LiteralExpr> assignments = new LinkedHashMap<>();
        putAssignment(assignments, parseAssignment());
        while (peek().getType() == Token.Type.DELIMITER && ",".equals(peek().getLexeme())) {
            advance();
            putAssignment(assignments, parseAssignment());
        }
        ASTNode whereCond = null;
        if (peek().getType() == Token.Type.KEYWORD && peek().getLexeme().equalsIgnoreCase("WHERE")) {
            whereCond = parseWhere();
        }
        finishStatement();
        return new ASTNode.UpdateStmt(start.getLine(), start.getCol(),
                table.getLexeme(), assignments, whereCond);
    }

    /** 解析 SET 子句中的单个赋值：列名 = 字面量 */
    private Map.Entry<String, ASTNode.LiteralExpr> parseAssignment() {
        String col = expect(Token.Type.IDENTIFIER).getLexeme();
        expectOperator("=");
        return Map.entry(col, expectLiteral());
    }

    /** 将单个赋值写入 assignments 映射 */
    private void putAssignment(Map<String, ASTNode.LiteralExpr> assignments,
                               Map.Entry<String, ASTNode.LiteralExpr> entry) {
        assignments.put(entry.getKey(), entry.getValue());
    }

    /**
     * 解析 DELETE 语句：DELETE FROM tableName [WHERE 条件]
     *
     * @return DeleteStmt 节点
     */
    private ASTNode parseDelete() {
        Token start = expectKeyword("DELETE");
        expectKeyword("FROM");
        Token table = expect(Token.Type.IDENTIFIER);
        ASTNode whereCond = null;
        if (peek().getType() == Token.Type.KEYWORD && peek().getLexeme().equalsIgnoreCase("WHERE")) {
            whereCond = parseWhere();
        }
        finishStatement();
        return new ASTNode.DeleteStmt(start.getLine(), start.getCol(),
                table.getLexeme(), whereCond);
    }

    /**
     * 解析 CREATE TABLE 语句：CREATE TABLE tableName (列名清单)
     *
     * @return CreateTableStmt 节点
     */
    private ASTNode parseCreateTable() {
        Token start = expectKeyword("CREATE");
        expectKeyword("TABLE");
        Token table = expect(Token.Type.IDENTIFIER);
        expectDelimiter("(");
        List<String> columns = new ArrayList<>();
        columns.add(expect(Token.Type.IDENTIFIER).getLexeme());
        while (peek().getType() == Token.Type.DELIMITER && ",".equals(peek().getLexeme())) {
            advance();
            columns.add(expect(Token.Type.IDENTIFIER).getLexeme());
        }
        expectDelimiter(")");
        finishStatement();
        return new ASTNode.CreateTableStmt(start.getLine(), start.getCol(),
                table.getLexeme(), columns);
    }

    /**
     * 解析 WHERE 子句：WHERE 条件表达式，解析后立即做常量折叠与逻辑简化。
     *
     * @return 优化后的条件表达式节点
     */
    private ASTNode parseWhere() {
        expectKeyword("WHERE");
        return fold(parseExpr());
    }

    /**
     * 表达式入口，按优先级从低到高逐层下降：|| → &amp;&amp; → 比较 → 加减 → 乘除 → 原子。
     * 该分层使 "age = 1+2" 正确解析为 age = (1+2) 而不是 (age = 1)+2，
     * 为后续常量折叠提供正确的树形结构。
     *
     * @return 表达式节点
     */
    private ASTNode parseExpr() {
        return parseOr();
    }

    private ASTNode parseOr() {
        ASTNode left = parseAnd();
        while (isOr()) {
            Token op = advance();
            ASTNode right = parseAnd();
            left = new ASTNode.BinaryExpr(op.getLine(), op.getCol(), normalize(op), left, right);
        }
        return left;
    }

    private ASTNode parseAnd() {
        ASTNode left = parseComparison();
        while (isAnd()) {
            Token op = advance();
            ASTNode right = parseComparison();
            left = new ASTNode.BinaryExpr(op.getLine(), op.getCol(), normalize(op), left, right);
        }
        return left;
    }

    /** 是否遇到逻辑或：运算符 || 或关键字 OR（不区分大小写） */
    private boolean isOr() {
        Token t = peek();
        return (t.getType() == Token.Type.OPERATOR && "||".equals(t.getLexeme()))
                || (t.getType() == Token.Type.KEYWORD && "OR".equalsIgnoreCase(t.getLexeme()));
    }

    /** 是否遇到逻辑与：运算符 &amp;&amp; 或关键字 AND（不区分大小写） */
    private boolean isAnd() {
        Token t = peek();
        return (t.getType() == Token.Type.OPERATOR && "&&".equals(t.getLexeme()))
                || (t.getType() == Token.Type.KEYWORD && "AND".equalsIgnoreCase(t.getLexeme()));
    }

    /** 逻辑运算符词素规范化：关键字 AND/OR 统一存大写，&& / || 保持原样 */
    private String normalize(Token op) {
        if (op.getType() == Token.Type.KEYWORD) {
            return op.getLexeme().toUpperCase();
        }
        return op.getLexeme();
    }

    private ASTNode parseComparison() {
        ASTNode left = parseAdditive();
        if (peek().getType() == Token.Type.OPERATOR && COMPARE_OPS.contains(peek().getLexeme())) {
            Token op = advance();
            ASTNode right = parseAdditive();
            left = new ASTNode.BinaryExpr(op.getLine(), op.getCol(), op.getLexeme(), left, right);
        }
        return left;
    }

    private ASTNode parseAdditive() {
        ASTNode left = parseMultiplicative();
        while (peek().getType() == Token.Type.OPERATOR && ADD_OPS.contains(peek().getLexeme())) {
            Token op = advance();
            ASTNode right = parseMultiplicative();
            left = new ASTNode.BinaryExpr(op.getLine(), op.getCol(), op.getLexeme(), left, right);
        }
        return left;
    }

    private ASTNode parseMultiplicative() {
        ASTNode left = parseOperand();
        while (peek().getType() == Token.Type.OPERATOR && MUL_OPS.contains(peek().getLexeme())) {
            Token op = advance();
            ASTNode right = parseOperand();
            left = new ASTNode.BinaryExpr(op.getLine(), op.getCol(), op.getLexeme(), left, right);
        }
        return left;
    }

    /**
     * 原子操作数：列名 → ColumnRef；字面量 → LiteralExpr。
     *
     * @return 叶子表达式节点
     */
    private ASTNode parseOperand() {
        Token t = peek();
        if (t.getType() == Token.Type.IDENTIFIER) {
            advance();
            return new ASTNode.ColumnRef(t.getLine(), t.getCol(), t.getLexeme());
        }
        ASTNode.LiteralExpr lit = literalFromToken(t);
        if (lit != null) {
            advance();
            return lit;
        }
        throw new SqxdlException(t.getLine(), t.getCol(),
                "语法错误：期望列名或常量，但遇到 '" + t.getLexeme() + "'");
    }

    /**
     * 编译期优化：后序遍历表达式树，先对子树做折叠/简化，再处理当前节点。
     * <ul>
     *   <li>常量折叠：算术运算（+ - * /）且两侧都是数字字面量时直接算出结果；</li>
     *   <li>逻辑简化：按布尔恒等律化简 &amp;&amp; / ||。</li>
     * </ul>
     * 优化动机：WHERE 条件在 executor 中逐行求值，把每行重复计算的常量
     * 提前到编译期算完，运行期只做必须的工作。
     *
     * @param node 待优化节点
     * @return 优化后的节点
     */
    private ASTNode fold(ASTNode node) {
        if (node instanceof ASTNode.BinaryExpr b) {
            ASTNode left = fold(b.getLeft());
            ASTNode right = fold(b.getRight());
            ASTNode.BinaryExpr nb = new ASTNode.BinaryExpr(b.getLine(), b.getCol(), b.getOp(), left, right);
            // 常量折叠：算术运算且两侧都是数字字面量
            if (isArithOp(nb.getOp())
                    && left instanceof ASTNode.LiteralExpr l && l.getKind() == ASTNode.LiteralExpr.Kind.NUMBER
                    && right instanceof ASTNode.LiteralExpr r && r.getKind() == ASTNode.LiteralExpr.Kind.NUMBER) {
                return foldArithmetic(nb);
            }
            // 逻辑简化：AND/OR 遇布尔常量按恒等律化简
            ASTNode simplified = simplifyLogical(nb);
            if (simplified != nb) {
                return fold(simplified); // 化简结果可能再触发折叠（如 TRUE AND TRUE）
            }
            return nb;
        }
        return node; // LiteralExpr / ColumnRef 原样返回
    }

    private boolean isArithOp(String op) {
        return ADD_OPS.contains(op) || MUL_OPS.contains(op);
    }

    /** 常量折叠：计算两个数字字面量的算术结果，返回新的数字字面量 */
    private ASTNode foldArithmetic(ASTNode.BinaryExpr b) {
        double l = Double.parseDouble(((ASTNode.LiteralExpr) b.getLeft()).getValue());
        double r = Double.parseDouble(((ASTNode.LiteralExpr) b.getRight()).getValue());
        double result;
        switch (b.getOp()) {
            case "+" -> result = l + r;
            case "-" -> result = l - r;
            case "*" -> result = l * r;
            case "/" -> {
                if (r == 0) {
                    throw new SqxdlException(b.getLine(), b.getCol(), "除零错误：常量表达式除以 0");
                }
                result = l / r;
            }
            default -> throw new SqxdlException(b.getLine(), b.getCol(), "非法算术运算符 '" + b.getOp() + "'");
        }
        // 整数结果不带小数点（1+2 → "3"），非整数保留小数（1.5+2 → "3.5"）
        String value;
        if (result == Math.floor(result) && !Double.isInfinite(result)) {
            value = String.valueOf((long) result);
        } else {
            value = String.valueOf(result);
        }
        return new ASTNode.LiteralExpr(b.getLine(), b.getCol(), value, ASTNode.LiteralExpr.Kind.NUMBER);
    }

    /** 逻辑简化：按布尔恒等律化简 AND/OR 表达式 */
    private ASTNode simplifyLogical(ASTNode.BinaryExpr b) {
        String op = b.getOp();
        boolean isAnd = "&&".equals(op) || "AND".equals(op);
        boolean isOr = "||".equals(op) || "OR".equals(op);
        if (!isAnd && !isOr) {
            return b;
        }
        ASTNode.LiteralExpr l = asBooleanLiteral(b.getLeft());
        ASTNode.LiteralExpr r = asBooleanLiteral(b.getRight());
        boolean lT = l != null && "TRUE".equals(l.getValue());
        boolean lF = l != null && "FALSE".equals(l.getValue());
        boolean rT = r != null && "TRUE".equals(r.getValue());
        boolean rF = r != null && "FALSE".equals(r.getValue());

        if (isAnd) {
            // x AND true -> x；x AND false -> false
            if (lT) return b.getRight();
            if (rT) return b.getLeft();
            if (lF || rF) {
                return new ASTNode.LiteralExpr(b.getLine(), b.getCol(), "FALSE", ASTNode.LiteralExpr.Kind.BOOLEAN);
            }
        } else {
            // x OR false -> x；x OR true -> true
            if (lF) return b.getRight();
            if (rF) return b.getLeft();
            if (lT || rT) {
                return new ASTNode.LiteralExpr(b.getLine(), b.getCol(), "TRUE", ASTNode.LiteralExpr.Kind.BOOLEAN);
            }
        }
        return b;
    }

    /** 节点是否为布尔字面量，是则返回它，否则返回 null */
    private ASTNode.LiteralExpr asBooleanLiteral(ASTNode node) {
        if (node instanceof ASTNode.LiteralExpr lit && lit.getKind() == ASTNode.LiteralExpr.Kind.BOOLEAN) {
            return lit;
        }
        return null;
    }

    /**
     * 查看当前 Token（不消费）。
     *
     * @return 当前待处理的 Token
     */
    private Token peek() {
        return current;
    }

    /**
     * 消费当前 Token 并推进到下一个。
     *
     * @return 被消费的 Token
     */
    private Token advance() {
        Token t = current;
        current = lexer.nextToken();
        return t;
    }

    /**
     * 断言当前 Token 的类型，符合则消费并返回；不符合则抛出带行列号的语法异常。
     *
     * @param type 期望的 Token 类型
     * @return 被消费的 Token
     * @throws SqxdlException 当前 Token 类型与期望不符
     */
    private Token expect(Token.Type type) {
        if (current.getType() != type) {
            throw new SqxdlException(current.getLine(), current.getCol(),
                    "语法错误：期望 " + type + "，但遇到 '" + current.getLexeme() + "'");
        }
        return advance();
    }

    /**
     * 断言当前 Token 为指定关键字（不区分大小写），符合则消费并返回；否则抛异常。
     *
     * @param keyword 期望的关键字（如 "SELECT"）
     * @return 被消费的 Token
     * @throws SqxdlException 当前 Token 不是该关键字
     */
    private Token expectKeyword(String keyword) {
        if (current.getType() != Token.Type.KEYWORD
                || !current.getLexeme().equalsIgnoreCase(keyword)) {
            throw new SqxdlException(current.getLine(), current.getCol(),
                    "语法错误：期望关键字 " + keyword + "，但遇到 '" + current.getLexeme() + "'");
        }
        return advance();
    }

    /**
     * 断言当前 Token 为指定分隔符（如 "("、")"、","），符合则消费；否则抛异常。
     *
     * @param delim 期望的分隔符文本
     * @return 被消费的 Token
     */
    private Token expectDelimiter(String delim) {
        if (current.getType() != Token.Type.DELIMITER || !delim.equals(current.getLexeme())) {
            throw new SqxdlException(current.getLine(), current.getCol(),
                    "语法错误：期望 '" + delim + "'，但遇到 '" + current.getLexeme() + "'");
        }
        return advance();
    }

    /**
     * 断言当前 Token 为指定运算符（如 "="），符合则消费；否则抛异常。
     *
     * @param op 期望的运算符文本
     * @return 被消费的 Token
     */
    private Token expectOperator(String op) {
        if (current.getType() != Token.Type.OPERATOR || !op.equals(current.getLexeme())) {
            throw new SqxdlException(current.getLine(), current.getCol(),
                    "语法错误：期望运算符 '" + op + "'，但遇到 '" + current.getLexeme() + "'");
        }
        return advance();
    }

    /**
     * 断言当前 Token 为字面量（数字/字符串/布尔常量），符合则消费并返回。
     * 用于 INSERT 的 VALUES 与 UPDATE 的 SET 子句。
     *
     * @return 字面量节点
     */
    private ASTNode.LiteralExpr expectLiteral() {
        Token t = peek();
        ASTNode.LiteralExpr lit = literalFromToken(t);
        if (lit == null) {
            throw new SqxdlException(t.getLine(), t.getCol(),
                    "语法错误：期望字面量，但遇到 '" + t.getLexeme() + "'");
        }
        advance();
        return lit;
    }

    /**
     * 将 Token 转为字面量节点；非字面量 Token 返回 null。
     * CONST → 数字/字符串；TRUE/FALSE 关键字 → 布尔（统一存大写）。
     */
    private ASTNode.LiteralExpr literalFromToken(Token t) {
        if (t.getType() == Token.Type.CONST) {
            boolean isNumber = t.getLexeme().matches("\\d+(\\.\\d+)?");
            ASTNode.LiteralExpr.Kind kind = isNumber
                    ? ASTNode.LiteralExpr.Kind.NUMBER
                    : ASTNode.LiteralExpr.Kind.STRING;
            return new ASTNode.LiteralExpr(t.getLine(), t.getCol(), t.getLexeme(), kind);
        }
        if (t.getType() == Token.Type.KEYWORD
                && ("TRUE".equalsIgnoreCase(t.getLexeme()) || "FALSE".equalsIgnoreCase(t.getLexeme()))) {
            return new ASTNode.LiteralExpr(t.getLine(), t.getCol(),
                    t.getLexeme().toUpperCase(), ASTNode.LiteralExpr.Kind.BOOLEAN);
        }
        return null;
    }
}

