# 编译期优化机制说明（B 组 semantic 必读）

> 本文面向 **semantic（B 组）**：说明 parser 在 AST 构建期已完成的编译期优化——
> 常量折叠与逻辑简化。你拿到的 `WHERE` / `JOIN ON` 条件树**已经是优化后的结果**，
> **不需要也不应重复实现**这两类优化。

## 1. 一句话结论

Parser 在构建表达式 AST 时（后序遍历）立即执行：

- **常量折叠**：`age = 1 + 2 * 3` → AST 中直接是 `age = 3 * 3` 再折叠为 `age = 7`（字面量替换）
- **逻辑简化**：`age > 18 AND true` → `age > 18`

semantic 看到的树里不存在 `1 + 2` 这类可折叠常量表达式，也不存在 `AND true` 这类可化简恒等结构。

## 2. 优化规则明细

### 2.1 常量折叠（`Parser.fold` → `foldArithmetic`）

| 规则 | 说明 |
|---|---|
| 触发条件 | 运算符为 `+ - * /`，且左右操作数都是 `LiteralExpr(Kind=NUMBER)` |
| 结果 | 计算出数值，替换为新的 `LiteralExpr(Kind=NUMBER)` |
| 整数格式 | 结果无小数部分时输出整数字符串（`1+2` → `"3"`），否则保留小数（`1.5+2` → `"3.5"`） |
| 除零 | **编译期报错** `SqxdlException`，消息含定位：`第N行第M列: 除零错误：常量表达式除以 0` |
| 递归 | 从叶子向上折叠；括号、NOT 操作数、AND/OR 的子树均参与（`NOT (x = 1+2)` 内 `1+2` → `3`） |

### 2.2 逻辑简化（`simplifyLogical`，布尔恒等律）

| 规则 | 示例 |
|---|---|
| `x AND true` → `x` | `age > 18 AND true` → `age > 18` |
| `x AND false` → `false` | `age > 18 AND false` → `FALSE` |
| `x OR false` → `x` | `age < 60 OR false` → `age < 60` |
| `x OR true` → `true` | `age < 60 OR true` → `TRUE` |

- 布尔字面量识别：`TRUE` / `FALSE` 关键字，AST 中为 `LiteralExpr(Kind=BOOLEAN)`，`value` 恒为大写 `"TRUE"` / `"FALSE"`。
- 化简结果可能再次触发折叠（`TRUE AND TRUE` → 化简为 `TRUE`）；含列引用的表达式（`IdentifierExpr`）不参与化简。
- `NOT` 只递归折叠操作数，**不做**德摩根等复杂恒等变换。

### 2.3 生效位置

- `WHERE` 条件（`parseWhere`）与 `JOIN ON` 条件（`parseJoinClause`）入口统一调用 `fold(parseExpr())`。
- `SELECT` 列清单**不参与**（`SELECT 1 + 2` 是列名解析，直接报错，见 README）。
- 常量表达式 `1 + 1` 只出现在 WHERE/ON 时才有折叠意义。

## 3. 对 semantic 的影响与依赖

1. **不要重复实现折叠/化简**。树中已不存在可折叠常量表达式与可化简恒等结构。
2. **原始表达式不可恢复**。`age = 1 + 2` 的 AST 里只有 `age = 3`，这是"优化前置"的有意设计；若你的功能（如错误提示）需要原始常量表达式，请在 parser 输出后另行获取，不要假设 AST 保留原样。
3. **除零是编译期错误**。`WHERE 1/0 = 1` 在解析阶段即抛 `SqxdlException`，semantic 不会收到含除零的常量表达式树。
4. **布尔常量判定**：判恒真/恒假直接检查 `LiteralExpr(Kind=BOOLEAN)` 的 `value`（`"TRUE"`/`"FALSE"`），与字符串 `'true'` 不混淆（后者是 `Kind=STRING`）。
5. **谓词下推的输入**：`ON` 与 `WHERE` 分离存储（`SelectStmt.joins` vs `SelectStmt.whereCond`）且**各自已折叠/化简**。你跨表下推时基于的是已化简条件，若条件为常量 `TRUE`/`FALSE` 可直接决定是否保留该谓词。
6. **验证参考**：折叠/化简的正确性已由 `ParserTest`（常量折叠、逻辑简化用例）、`EdgeCaseTest`（`AND true`、`OR true`、除零边界）覆盖；`SqlPrinterTest` 验证还原文本（`SELECT * FROM t WHERE (age = 3)`）；`DiffTest` 用 H2 实测还原 SQL 语义等价（`WHERE id = 1 + 1` 折叠后 `WHERE (id = 2)` 在 H2 中执行结果正确）。

## 4. 快速验证

```java
ASTNode stmt = new Parser(new Lexer("SELECT * FROM t WHERE age = 1 + 2")).parse();
// stmt.whereCond 为 BinaryExpr("=", IdentifierExpr("age"), LiteralExpr("3", NUMBER))
```

## 5. 本模块与优化的关系

- 优化实现位于 `Parser.fold / foldArithmetic / simplifyLogical`，是语法分析期的组成部分，属 A 组职责；
- 若 B/C/D 组后续需要"原始未折叠表达式"或"优化统计报告"，请与 A 组对接口，**不要自行改动 parser 的优化行为**。
