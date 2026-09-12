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

    /** 上一次 parseAll() 收集到的语法错误（错误恢复时填充，单条 parse() 不记录） */
    private final List<SqxdlException> errors = new ArrayList<>();

    public Parser(Lexer lexer) {
        this.lexer = lexer;
        this.current = lexer.nextToken();
    }

    /** 返回最后一次 parseAll() 解析过程中的语法错误列表（按出现顺序） */
    public List<SqxdlException> getErrors() {
        return errors;
    }

    /**
     * 解析完整 SQL 语句，按首关键字分发到对应语句的解析方法。
     *
     * @return 语法树根节点（SelectStmt / InsertStmt / UpdateStmt / DeleteStmt / CreateTableStmt / ShowStmt）
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
                case "SHOW", "DESCRIBE", "DESC" -> {
                    return parseShow();
                }
                case "DROP" -> {
                    return parseDropTable();
                }
                default -> {
                }
            }
        }
        throw syntaxError(t, "SELECT | INSERT | UPDATE | DELETE | CREATE | SHOW | DROP");
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
            throw syntaxError(t, "';' | EOF | SELECT | INSERT | UPDATE | DELETE | CREATE | SHOW | DROP | DESCRIBE | DESC");
        }
    }

    /**
     * 解析多条 SQL 语句（分号分隔），直到输入结束。
     * 例："SELECT 1; SELECT 2" → 两条语句；空输入 → 空列表。
     *
     * <p>错误恢复：某条语句解析出错时，把异常记入 {@link #getErrors()}，
     * 然后丢弃到下一个 ';'（或 EOF）继续解析下一条，不会中断整体处理。
     *
     * @return 解析成功的语句 AST 节点列表；错误详情见 {@link #getErrors()}
     */
    public List<ASTNode> parseAll() {
        errors.clear();
        List<ASTNode> stmts = new ArrayList<>();
        while (peek().getType() != Token.Type.EOF) {
            // 跳过前导/连续分号（空语句）
            if (peek().getType() == Token.Type.DELIMITER && ";".equals(peek().getLexeme())) {
                advance();
                continue;
            }
            try {
                stmts.add(parse());
            } catch (SqxdlException e) {
                errors.add(e);
                skipToStatementBoundary();
            }
        }
        return stmts;
    }

    /** 跳到下一条语句边界：丢弃直到（并包括）下一个 ';'，若没有分号则到 EOF */
    private void skipToStatementBoundary() {
        while (peek().getType() != Token.Type.EOF
                && !(peek().getType() == Token.Type.DELIMITER && ";".equals(peek().getLexeme()))) {
            advance();
        }
        if (peek().getType() == Token.Type.DELIMITER && ";".equals(peek().getLexeme())) {
            advance();
        }
    }

    /**
     * 解析 SELECT 语句：
     * SELECT 列清单 FROM 表名 { JOIN 表名 ON 条件 }
     *   [WHERE 条件] [GROUP BY 列清单] [ORDER BY 排序项清单]
     * JOIN 条件与 WHERE 条件都经编译期优化（常量折叠 / 逻辑简化），且分离存储，
     * 语义层可据此做跨表谓词下推。
     *
     * @return SelectStmt 节点
     */
    private ASTNode parseSelect() {
        Token start = expectKeyword("SELECT");
        List<String> columns = parseSelectList();
        expectKeyword("FROM");
        Token table = expect(Token.Type.IDENTIFIER);
        // JOIN 子句（可链式）：t1 JOIN t2 ON 条件 [JOIN t3 ON 条件 ...]
        List<ASTNode.SelectStmt.JoinClause> joins = new ArrayList<>();
        while (peek().getType() == Token.Type.KEYWORD
                && peek().getLexeme().equalsIgnoreCase("JOIN")) {
            joins.add(parseJoinClause());
        }
        ASTNode whereCond = null;
        if (peek().getType() == Token.Type.KEYWORD && peek().getLexeme().equalsIgnoreCase("WHERE")) {
            whereCond = parseWhere();
        }
        List<String> groupBy = new ArrayList<>();
        if (peek().getType() == Token.Type.KEYWORD && peek().getLexeme().equalsIgnoreCase("GROUP")) {
            groupBy = parseGroupBy();
        }
        List<ASTNode.SelectStmt.OrderItem> orderBy = new ArrayList<>();
        if (peek().getType() == Token.Type.KEYWORD && peek().getLexeme().equalsIgnoreCase("ORDER")) {
            orderBy = parseOrderBy();
        }
        finishStatement();
        return new ASTNode.SelectStmt(start.getLine(), start.getCol(),
                table.getLexeme(), columns, whereCond, joins, groupBy, orderBy);
    }

    /**
     * 解析 JOIN 子句：JOIN 表名 ON 表达式。
     * ON 条件参与编译期优化（fold），与 WHERE 分离存储，便于语义层谓词下推。
     *
     * @return 连接子句（表名 + ON 条件）
     */
    private ASTNode.SelectStmt.JoinClause parseJoinClause() {
        expectKeyword("JOIN");
        Token table = expect(Token.Type.IDENTIFIER);
        expectKeyword("ON");
        ASTNode onCond = fold(parseExpr());
        return new ASTNode.SelectStmt.JoinClause(table.getLexeme(), onCond);
    }

    /**
     * 解析 GROUP BY 子句：GROUP BY 列名 {, 列名}。
     *
     * @return 分组列名列表
     */
    private List<String> parseGroupBy() {
        expectKeyword("GROUP");
        expectKeyword("BY");
        List<String> columns = new ArrayList<>();
        columns.add(expect(Token.Type.IDENTIFIER).getLexeme());
        while (peek().getType() == Token.Type.DELIMITER && ",".equals(peek().getLexeme())) {
            advance();
            columns.add(expect(Token.Type.IDENTIFIER).getLexeme());
        }
        return columns;
    }

    /**
     * 解析 ORDER BY 子句：ORDER BY 列名 [ASC | DESC] {, 列名 [ASC | DESC]}。
     * 未显式写方向时按 ASC 处理。
     *
     * @return 排序项列表（按书写顺序）
     */
    private List<ASTNode.SelectStmt.OrderItem> parseOrderBy() {
        expectKeyword("ORDER");
        expectKeyword("BY");
        List<ASTNode.SelectStmt.OrderItem> items = new ArrayList<>();
        items.add(parseOrderItem());
        while (peek().getType() == Token.Type.DELIMITER && ",".equals(peek().getLexeme())) {
            advance();
            items.add(parseOrderItem());
        }
        return items;
    }

    /** 解析单个排序项：列名 [ASC | DESC] */
    private ASTNode.SelectStmt.OrderItem parseOrderItem() {
        Token column = expect(Token.Type.IDENTIFIER);
        String direction = "ASC";
        if (peek().getType() == Token.Type.KEYWORD) {
            if (peek().getLexeme().equalsIgnoreCase("ASC")) {
                advance();
                direction = "ASC";
            } else if (peek().getLexeme().equalsIgnoreCase("DESC")) {
                advance();
                direction = "DESC";
            }
        }
        return new ASTNode.SelectStmt.OrderItem(column.getLexeme(), direction);
    }

    /**
     * 解析 SHOW / DESCRIBE / DESC 语句。
     * <ul>
     *   <li>SHOW TABLES 列出全部表；SHOW TABLE name 查看指定表结构；</li>
     *   <li>DESCRIBE name / DESC name 是 SHOW TABLE name 的 MySQL 风格等价写法，
     *       归一化为 target=TABLE 输出，语义层无需区分写法。</li>
     * </ul>
     *
     * @return ShowStmt 节点（tableName 仅在 target 为 TABLE 时有值）
     */
    private ASTNode parseShow() {
        Token start = advance(); // SHOW / DESCRIBE / DESC，已由分发层校验
        // DESCRIBE/DESC 归一化：DESCRIBE t 等价于 SHOW TABLE t
        if ("DESCRIBE".equalsIgnoreCase(start.getLexeme())
                || "DESC".equalsIgnoreCase(start.getLexeme())) {
            Token table = expect(Token.Type.IDENTIFIER);
            finishStatement();
            return new ASTNode.ShowStmt(start.getLine(), start.getCol(), "TABLE", table.getLexeme());
        }
        Token target = peek();
        if (target.getType() != Token.Type.KEYWORD
                || !("TABLE".equalsIgnoreCase(target.getLexeme())
                || "TABLES".equalsIgnoreCase(target.getLexeme()))) {
            throw syntaxError(target, "TABLE | TABLES");
        }
        advance();
        String tableName = null;
        if ("TABLE".equalsIgnoreCase(target.getLexeme())) {
            tableName = expect(Token.Type.IDENTIFIER).getLexeme();
        }
        finishStatement();
        return new ASTNode.ShowStmt(start.getLine(), start.getCol(),
                target.getLexeme().toUpperCase(), tableName);
    }

    /**
     * 解析 DROP TABLE 语句：DROP TABLE tableName。
     * 与 CREATE TABLE 对称，语义层据此删除表及其数据。
     *
     * @return DropTableStmt 节点
     */
    private ASTNode parseDropTable() {
        Token start = expectKeyword("DROP");
        expectKeyword("TABLE");
        Token table = expect(Token.Type.IDENTIFIER);
        finishStatement();
        return new ASTNode.DropTableStmt(start.getLine(), start.getCol(), table.getLexeme());
    }

    /**
     * 解析查询列清单：列名 (逗号 列名)*，或 SELECT * 时按约定存单元素 ["*"]。
     * 支持聚合函数 COUNT(*)：产出虚拟列名 "COUNT(*)"（大小写归一），
     * 语义层跳过其列校验，执行层在 GROUP BY 分组时计算行数。
     *
     * @return 列名列表（可含聚合项 "COUNT(*)"）
     */
    private List<String> parseSelectList() {
        List<String> columns = new ArrayList<>();
        if (peek().getType() == Token.Type.OPERATOR && "*".equals(peek().getLexeme())) {
            advance();
            columns.add("*");
        } else {
            columns.add(parseSelectItem());
            while (peek().getType() == Token.Type.DELIMITER && ",".equals(peek().getLexeme())) {
                advance();
                columns.add(parseSelectItem());
            }
        }
        return columns;
    }

    /**
     * 解析单个投影项：普通列名 或 聚合函数 COUNT(*)。
     * COUNT 按 IDENTIFIER 词法产出（非关键字），后跟 "(" "*" ")" 即聚合调用；
     * 其余函数名或不带 * 参数的写法均按语法错误拒绝。
     *
     * @return 列名或虚拟聚合列名 "COUNT(*)"
     */
    private String parseSelectItem() {
        Token item = expect(Token.Type.IDENTIFIER);
        if (peek().getType() == Token.Type.DELIMITER && "(".equals(peek().getLexeme())) {
            if (!"COUNT".equalsIgnoreCase(item.getLexeme())) {
                throw syntaxError(item, "COUNT(*)（暂仅支持 COUNT 聚合）");
            }
            advance(); // (
            expectOperator("*");
            expectDelimiter(")");
            return "COUNT(*)";
        }
        return item.getLexeme();
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
     * 解析 CREATE TABLE 语句：CREATE TABLE tableName '(' column_def { ',' column_def } ')'
     *
     * @return CreateTableStmt 节点
     */
    private ASTNode parseCreateTable() {
        Token start = expectKeyword("CREATE");
        expectKeyword("TABLE");
        Token table = expect(Token.Type.IDENTIFIER);
        expectDelimiter("(");
        List<ASTNode.CreateTableStmt.ColumnDef> columns = new ArrayList<>();
        columns.add(parseColumnDef());
        while (peek().getType() == Token.Type.DELIMITER && ",".equals(peek().getLexeme())) {
            advance();
            columns.add(parseColumnDef());
        }
        expectDelimiter(")");
        finishStatement();
        return new ASTNode.CreateTableStmt(start.getLine(), start.getCol(),
                table.getLexeme(), columns);
    }

    /** 解析列定义：IDENTIFIER type（type -> INT | VARCHAR | DOUBLE），类型统一存大写 */
    private ASTNode.CreateTableStmt.ColumnDef parseColumnDef() {
        Token name = expect(Token.Type.IDENTIFIER);
        Token type = expectType();
        return new ASTNode.CreateTableStmt.ColumnDef(name.getLexeme(), type.getLexeme().toUpperCase());
    }

    /** 断言当前 Token 为列类型关键字 INT/VARCHAR/DOUBLE，符合则消费并返回 */
    private Token expectType() {
        if (current.getType() == Token.Type.KEYWORD
                && ("INT".equalsIgnoreCase(current.getLexeme())
                || "VARCHAR".equalsIgnoreCase(current.getLexeme())
                || "DOUBLE".equalsIgnoreCase(current.getLexeme()))) {
            return advance();
        }
        throw syntaxError(current, "INT | VARCHAR | DOUBLE");
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
        ASTNode left = parseNot();
        while (isAnd()) {
            Token op = advance();
            ASTNode right = parseNot();
            left = new ASTNode.BinaryExpr(op.getLine(), op.getCol(), normalize(op), left, right);
        }
        return left;
    }

    /**
     * NOT 一元运算层：not_expr -> NOT not_expr | comparison。
     * 按文法，NOT 的优先级低于比较运算、高于 AND/OR；
     * 例如 "NOT age &gt; 18" 解析为 NOT(age &gt; 18)，"NOT a = 1 AND b = 2" 解析为 (NOT a=1) AND (b=2)。
     */
    private ASTNode parseNot() {
        if (peek().getType() == Token.Type.KEYWORD && "NOT".equalsIgnoreCase(peek().getLexeme())) {
            Token op = advance();
            ASTNode operand = parseNot();
            return new ASTNode.UnaryExpr(op.getLine(), op.getCol(), "NOT", operand);
        }
        return parseComparison();
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
     * 原子操作数：'(' expression ')' → 括号内表达式；标识符 → IdentifierExpr；字面量 → LiteralExpr。
     *
     * @return 叶子或括号子表达式节点
     */
    private ASTNode parseOperand() {
        Token t = peek();
        if (t.getType() == Token.Type.DELIMITER && "(".equals(t.getLexeme())) {
            advance();
            ASTNode inner = parseExpr();
            expectDelimiter(")");
            return inner;
        }
        if (t.getType() == Token.Type.IDENTIFIER) {
            advance();
            return new ASTNode.IdentifierExpr(t.getLine(), t.getCol(), t.getLexeme());
        }
        ASTNode.LiteralExpr lit = literalFromToken(t);
        if (lit != null) {
            advance();
            return lit;
        }
        throw syntaxError(t, "IDENTIFIER | CONST | '(' | ')' | NOT");
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
        if (node instanceof ASTNode.UnaryExpr u) {
            // NOT 一元运算：递归折叠其操作数（如 NOT (x = 1 + 2) 内的 1+2 → 3）
            return new ASTNode.UnaryExpr(u.getLine(), u.getCol(), u.getOp(), fold(u.getOperand()));
        }
        return node; // LiteralExpr / IdentifierExpr 原样返回
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
     * 构造统一格式的语法错误：第N行第M列: unexpected token 'X'，期望: 终结符列表。
     * 格式对齐课程要求：必须给出行列号、具体的意外符号与当前状态下期望的终结符。
     */
    private SqxdlException syntaxError(Token t, String expected) {
        // EOF 词素为空，统一显示为 "EOF"，避免 "unexpected token ''"
        String shown = t.getType() == Token.Type.EOF ? "EOF" : t.getLexeme();
        return new SqxdlException(t.getLine(), t.getCol(),
                "第" + t.getLine() + "行第" + t.getCol() + "列: unexpected token '"
                        + shown + "'，期望: " + expected);
    }

    /** Token 类型的展示名（用于期望列表） */
    private static String expectedName(Token.Type type) {
        return switch (type) {
            case IDENTIFIER -> "IDENTIFIER";
            case CONST -> "CONST";
            case KEYWORD -> "关键字";
            case OPERATOR -> "运算符";
            case DELIMITER -> "分隔符";
            case EOF -> "EOF";
        };
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
            throw syntaxError(current, expectedName(type));
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
            throw syntaxError(current, keyword);
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
            throw syntaxError(current, "'" + delim + "'");
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
            throw syntaxError(current, "'" + op + "'");
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
            throw syntaxError(t, "CONST | TRUE | FALSE");
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

