package com.sqxdl.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 词法分析器（A 组）。
 * 职责：扫描 SQL 源码文本，逐个产出 {@link Token}。
 *
 * <p>实现说明：
 * <ul>
 *   <li>构造时传入 SQL 文本，反复调用 {@link #nextToken()} 依次取词；</li>
 *   <li>识别五类 Token：KEYWORD / IDENTIFIER / CONST / OPERATOR / DELIMITER；</li>
 *   <li>跳过空白与注释（-- 行注释、块注释）并维护 line/col，源码读完返回 {@link Token.Type#EOF}（全组约定）；</li>
 *   <li>拼写纠错：单词不是关键字且与某关键字编辑距离为 1 时，自动纠正为关键字。</li>
 * </ul>
 */
public class Lexer {

    /** 关键字表（统一大写存放，识别时不区分大小写） */
    private static final Set<String> KEYWORDS = Set.of(
            "SELECT", "FROM", "WHERE", "CREATE", "TABLE", "TABLES",
            "INSERT", "INTO", "VALUES", "DELETE", "UPDATE", "SET",
            "AND", "OR", "NOT", "TRUE", "FALSE",
            "INT", "VARCHAR", "SHOW");

    /** 双字符运算符，需先于单字符判断 */
    private static final Set<String> TWO_CHAR_OPERATORS = Set.of("<=", ">=", "!=", "==", "&&", "||");

    private final String src;
    private int pos;
    private int line = 1;
    private int col = 1;

    /** 拼写纠错提示（按出现顺序收集），由调用方决定何时展示，避免 Lexer 直接打印 */
    private final List<String> spellWarnings = new ArrayList<>();

    public Lexer(String src) {
        this.src = src;
    }

    /**
     * 读取并返回下一个 Token；源码读完时返回 EOF 标记。
     *
     * @return 下一个词法记号
     */
    public Token nextToken() {
        skipWhitespaceAndComments();
        if (pos >= src.length()) {
            return new Token(Token.Type.EOF, "", line, col);
        }
        char c = src.charAt(pos);
        if (isLetter(c) || c == '_') {
            return readWord();
        }
        if (isDigit(c)) {
            return readNumber();
        }
        if (c == '\'') {
            return readString();
        }
        if (isOperatorStart(c)) {
            return readOperator();
        }
        if (isDelimiter(c)) {
            int startLine = line;
            int startCol = col;
            pos++;
            col++;
            return new Token(Token.Type.DELIMITER, String.valueOf(c), startLine, startCol);
        }
        throw new SqxdlException(line, col, "非法字符 '" + c + "'");
    }

    /**
     * 返回本次词法分析过程中收集到的拼写纠错提示（按出现顺序）。
     * 调用方（如 demo 或上层工具）自行决定是否打印，避免重复输出。
     *
     * @return 拼写提示列表；无纠错时为空列表
     */
    public List<String> getSpellWarnings() {
        return spellWarnings;
    }

    /**
     * 跳过空白与注释，换行使 line+1、col 归 1。
     * 支持行注释 "-- ..."（到行尾）与块注释 "/* ... *\/"（可跨行）。
     */
    private void skipWhitespaceAndComments() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == '\n') {
                line++;
                col = 1;
                pos++;
            } else if (c == ' ' || c == '\t' || c == '\r') {
                pos++;
                col++;
            } else if (c == '-' && pos + 1 < src.length() && src.charAt(pos + 1) == '-') {
                // 行注释：吞掉到行尾为止的所有字符（换行符留给下一轮循环统一处理）
                while (pos < src.length() && src.charAt(pos) != '\n') {
                    pos++;
                }
            } else if (c == '/' && pos + 1 < src.length() && src.charAt(pos + 1) == '*') {
                skipBlockComment();
            } else {
                break;
            }
        }
    }

    /** 跳过块注释 "/* ... *\/"，支持跨行；未闭合时报错并定位到注释起点 */
    private void skipBlockComment() {
        int startLine = line;
        int startCol = col;
        pos += 2;
        col += 2;
        while (pos < src.length()) {
            if (src.charAt(pos) == '*' && pos + 1 < src.length() && src.charAt(pos + 1) == '/') {
                pos += 2;
                col += 2;
                return;
            }
            if (src.charAt(pos) == '\n') {
                line++;
                col = 1;
            } else {
                col++;
            }
            pos++;
        }
        throw new SqxdlException(startLine, startCol, "未闭合的块注释");
    }

    /** 读单词：字母或下划线开头，后跟字母/数字/下划线；查关键字表决定 KEYWORD 或 IDENTIFIER */
    private Token readWord() {
        int start = pos;
        int startLine = line;
        int startCol = col;
        while (pos < src.length() && isWordChar(src.charAt(pos))) {
            pos++;
            col++;
        }
        String word = src.substring(start, pos);
        String upper = word.toUpperCase();
        if (KEYWORDS.contains(upper)) {
            return new Token(Token.Type.KEYWORD, word, startLine, startCol);
        }
        String corrected = correctSpelling(upper);
        if (corrected != null) {
            spellWarnings.add("[" + startLine + ":" + startCol + "] 拼写提示：将 '"
                    + word + "' 纠正为 '" + corrected + "'");
            return new Token(Token.Type.KEYWORD, corrected, startLine, startCol);
        }
        return new Token(Token.Type.IDENTIFIER, word, startLine, startCol);
    }

    /** 读数字常量：支持整数与小数（如 20、3.14） */
    private Token readNumber() {
        int start = pos;
        int startLine = line;
        int startCol = col;
        while (pos < src.length() && isDigit(src.charAt(pos))) {
            pos++;
            col++;
        }
        // 小数部分：仅在 '.' 后面紧跟数字时才消费，避免把 "3." 末尾的点误吞
        if (pos + 1 < src.length() && src.charAt(pos) == '.' && isDigit(src.charAt(pos + 1))) {
            pos++;
            col++;
            while (pos < src.length() && isDigit(src.charAt(pos))) {
                pos++;
                col++;
            }
        }
        // 数字后紧跟字母属于非法标识符，如 123abc
        if (pos < src.length() && isLetter(src.charAt(pos))) {
            throw new SqxdlException(line, col, "非法标识符：数字后不能紧跟字母");
        }
        return new Token(Token.Type.CONST, src.substring(start, pos), startLine, startCol);
    }

    /** 读字符串常量：以 ' 开头，到下一个 ' 结束；'' 转义为字面单引号；lexeme 不含引号 */
    private Token readString() {
        int startLine = line;
        int startCol = col;
        pos++;              // 跳过开头的 '
        col++;
        StringBuilder value = new StringBuilder();
        while (true) {
            if (pos >= src.length()) {
                throw new SqxdlException(startLine, startCol, "未闭合的字符串字面量");
            }
            char ch = src.charAt(pos);
            if (ch == '\'') {
                if (pos + 1 < src.length() && src.charAt(pos + 1) == '\'') {
                    // '' 转义：两个连续单引号表示字面意义上的一个单引号
                    value.append('\'');
                    pos += 2;
                    col += 2;
                } else {
                    pos++;  // 跳过结尾的 '
                    col++;
                    break;
                }
            } else {
                if (ch == '\n') {
                    line++;
                    col = 1;
                } else {
                    col++;
                }
                value.append(ch);
                pos++;
            }
        }
        return new Token(Token.Type.CONST, value.toString(), startLine, startCol);
    }

    /** 读运算符：优先匹配双字符运算符（如 <=、&&），否则按单字符处理 */
    private Token readOperator() {
        int start = pos;
        int startLine = line;
        int startCol = col;
        if (pos + 1 < src.length()) {
            String two = src.substring(pos, pos + 2);
            if (TWO_CHAR_OPERATORS.contains(two)) {
                pos += 2;
                col += 2;
                return new Token(Token.Type.OPERATOR, two, startLine, startCol);
            }
        }
        pos++;
        col++;
        return new Token(Token.Type.OPERATOR, src.substring(start, pos), startLine, startCol);
    }

    /** 拼写纠错：单词与某关键字编辑距离为 1 时返回该关键字，否则返回 null */
    private String correctSpelling(String upperWord) {
        for (String keyword : KEYWORDS) {
            if (editDistance(upperWord, keyword) == 1) {
                return keyword;
            }
        }
        return null;
    }

    /** 编辑距离（Levenshtein）：滚动数组版，O(m*n) 时间、O(n) 空间 */
    private static int editDistance(String a, String b) {
        int m = a.length();
        int n = b.length();
        int[] prev = new int[n + 1];
        int[] curr = new int[n + 1];
        for (int j = 0; j <= n; j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= m; i++) {
            curr[0] = i;
            for (int j = 1; j <= n; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[n];
    }

    private static boolean isLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isWordChar(char c) {
        return isLetter(c) || isDigit(c) || c == '_';
    }

    private static boolean isOperatorStart(char c) {
        return c == '+' || c == '-' || c == '*' || c == '/'
                || c == '=' || c == '<' || c == '>' || c == '!'
                || c == '&' || c == '|';
    }

    private static boolean isDelimiter(char c) {
        return c == ',' || c == ';' || c == '(' || c == ')';
    }
}
