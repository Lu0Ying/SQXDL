# SQXDL :: SQL 子集文法定义（A 组）

本文档是与 `Parser` 实现**严格一致**的语法规范，也是语义分析（B 组）与执行计划生成（C 组）理解 AST 的依据。
Parser 采用**递归下降**方案实现，每个非终结符对应一个解析函数，运算符优先级通过函数分层体现，见第 4 节。

---

## 1. 终结符约定

| 终结符 | 含义 | 词法示例 |
|---|---|---|
| `IDENTIFIER` | 标识符（表名 / 列名） | `student`、`id`、`my_table` |
| `CONST` | 常量（数字 / 字符串字面量） | `18`、`'Tom'` |
| `INT` / `VARCHAR` | 列类型关键字 | `INT`、`varchar` |
| 关键字 | `SELECT` `FROM` `WHERE` `CREATE` `TABLE` `TABLES` `INSERT` `INTO` `VALUES` `DELETE` `UPDATE` `SET` `AND` `OR` `NOT` `TRUE` `FALSE` `SHOW` | 不区分大小写 |
| 运算符 | `=` `<` `>` `<=` `>=` `!=` `==` `+` `-` `*` `/` `&&` `\|\|` | `>=`、`&&` |
| 分隔符 | `(` `)` `,` `;` | |
| `EOF` | 输入结束 | |

补充约定：

- 关键字不区分大小写；标识符与字符串字面量保留原始大小写。
- `AND` / `OR` 与符号 `&&` / `\|\|` 等价（双语法），AST 中关键字统一存大写。
- `TRUE` / `FALSE` 是布尔字面量，等价于 `CONST`（AST 中 `LiteralExpr.Kind.BOOLEAN`）。
- 语句之间的 `;` **可省略**（宽容约定），实现允许：结尾分号、连续分号、以及以 `;` / EOF / 下一条语句关键字收尾，均视为语句结束。

---

## 2. 完整文法（BNF）

```
statement      -> create_stmt | insert_stmt | select_stmt | update_stmt | delete_stmt | show_stmt

create_stmt    -> CREATE TABLE IDENTIFIER '(' column_def { ',' column_def } ')' ';'
column_def     -> IDENTIFIER type
type           -> INT | VARCHAR

insert_stmt    -> INSERT INTO IDENTIFIER [ '(' id_list ')' ] VALUES '(' value_list ')' ';'
id_list        -> IDENTIFIER { ',' IDENTIFIER }
value_list     -> literal { ',' literal }

select_stmt    -> SELECT select_list FROM IDENTIFIER where_opt ';'
select_list    -> '*' | IDENTIFIER { ',' IDENTIFIER }
where_opt      -> WHERE expression | ε

update_stmt    -> UPDATE IDENTIFIER SET assignment { ',' assignment } where_opt ';'
assignment     -> IDENTIFIER '=' literal

delete_stmt    -> DELETE FROM IDENTIFIER where_opt ';'

show_stmt      -> SHOW ( TABLES | TABLE IDENTIFIER ) ';'

expression     -> or_expr
or_expr        -> and_expr { OR and_expr }
and_expr       -> not_expr { AND not_expr }
not_expr       -> NOT not_expr | comparison
comparison     -> additive [ comp_op additive ]
additive       -> multiplicative { ( '+' | '-' ) multiplicative }
multiplicative -> primary { ( '*' | '/' ) primary }
primary        -> IDENTIFIER | literal | '(' expression ')'
comp_op        -> '=' | '<' | '>' | '<=' | '>=' | '!=' | '=='
literal        -> CONST | TRUE | FALSE
```

> 与课程示例文法的关系：`select_stmt`、`select_list`、`where_opt`、`delete_stmt`、`create_stmt`、`column_def`、`type`、`insert_stmt` 与课程文法逐条对应。
> `expression → or_expr`、`or_expr → and_expr { OR and_expr }`、`and_expr → not_expr { AND not_expr }`、`not_expr → NOT not_expr | comparison`、`comparison → primary [ comp_op primary ]`、`primary → IDENTIFIER | CONST | '(' expression ')'` 亦与课程文法一致。
> 超集部分：`comparison` 的操作数改为 `additive`（`* /` 优先级高于 `+ -`，支持常量折叠 `1+2 → 3`）；`update_stmt`、`show_stmt` 为课程文法之外的能力扩展。

### 运算符优先级（低 → 高）

```
OR ( || )   <   AND ( && )   <   NOT   <   比较运算   <   + -   <   * /   <   原子（含括号）
```

优先级通过文法分层体现：`or_expr → and_expr { OR ... }`、`and_expr → not_expr { AND ... }`、
`not_expr → NOT not_expr | comparison`、`comparison → additive [ comp_op additive ]`。

按 `not_expr → NOT not_expr | comparison`，**NOT 的优先级高于 AND/OR、低于比较运算**：
`NOT a = 1` 解析为 `NOT(a = 1)`（NOT 作用于整个比较结果），`NOT a = 1 AND b = 2` 解析为 `(NOT a=1) AND (b=2)`。
括号可任意改变结合结构：`a = 1 AND (b = 2 OR c = 3)`。

课程示例验证：`a = 1 OR b = 2 AND c = 3` 应解析为 `a = 1 OR (b = 2 AND c = 3)`（AND 高于 OR），测试用例 `whereTeacherPrecedenceExample` 覆盖。

---

## 3. 左递归与左公因子

文法中所有 `{ ... }`（克林闭包）均已展开为**左递归形式**（如 `or_expr -> or_expr OR and_expr | and_expr`），
这正是递归下降需要消除的左递归形态；实现按课本标准改写成迭代形式：

```
or_expr -> and_expr { OR and_expr }
```

即先解析一个 `and_expr`，再循环判断是否遇到 `OR`。文法本身不含左公因子冲突
（各语句以互不相同的首关键字区分：`CREATE` / `INSERT` / `SELECT` / `UPDATE` / `DELETE` / `SHOW`；
`primary` 的 `IDENTIFIER | literal | '(' expression ')'` 首符号集互不相交），故无需提取左公因子。

---

## 4. 实现方案：递归下降（替代表驱动 LL(1)）

课程要求理解 LL(1) 文法、消除左递归、FIRST/FOLLOW 集、预测分析表，但**仅作为实现手段**。
本项目采用**递归下降**方案，理由：

1. 该文法规模小、无左公因子冲突，递归下降代码直观且与文法一一对应，便于维护；
2. 每个非终结符对应一个解析函数（`parseCreateTable`、`parseSelect`、`parseInsert`、`parseUpdate`、
   `parseDelete`、`parseShow`、`parseWhere`、`parseExpr`、`parseOr`、`parseAnd`、`parseNot`、`parseComparison`、
   `parseAdditive`、`parseMultiplicative`、`parseOperand`、`parseSelectList`、`parseColumnDef` 等）；
3. 单 Token 向前看（`peek()` / `advance()`）即可完成所有分支决策，等价于利用各非终结符的 **FIRST 集** 判断；
4. 具备错误恢复能力：`parseAll()` 捕获 `SqxdlException` 后跳过到下一个 `;`（或 EOF）继续解析。

### 4.1 预测分析（LL(1)）视角下的 FIRST 集

表驱动 LL(1) 需要完整 FIRST/FOLLOW 与预测分析表；递归下降**不构造预测分析表**，
但每个解析函数的分支判断依赖 FIRST 集，这里列出主要非终结符的 FIRST 集（含 `ε` 处理）：

| 非终结符 | FIRST 集 | 分支依据（对应解析函数） |
|---|---|---|
| `statement` | `CREATE` `INSERT` `SELECT` `UPDATE` `DELETE` `SHOW` | `parse()` 按首关键字 switch |
| `create_stmt` | `CREATE` | |
| `insert_stmt` | `INSERT` | |
| `select_stmt` | `SELECT` | |
| `update_stmt` | `UPDATE` | |
| `delete_stmt` | `DELETE` | |
| `show_stmt` | `SHOW` | `parseShow` 后按 `TABLE`/`TABLES` 分支 |
| `column_def` | `IDENTIFIER` | `parseColumnDef` |
| `type` | `INT` `VARCHAR` | `expectType` |
| `id_list` / `select_list` | `*` `IDENTIFIER` | `parseSelectList` |
| `where_opt` | `WHERE` ∪ **{ε}** | `parseWhere` 前先 `peek()` 判断 |
| `expression` / `or_expr` | `IDENTIFIER` `CONST` `(` `NOT` | |
| `and_expr` | 同上 | |
| `not_expr` | `NOT` `IDENTIFIER` `CONST` `(` | `parseNot` 先判断 `NOT` |
| `comparison` / `additive` / `multiplicative` | `IDENTIFIER` `CONST` `(` | |
| `primary` | `IDENTIFIER` `CONST` `(` | `parseOperand` 三分支 |

> FOLLOW 集与预测分析表仅在表驱动方案中需要。递归下降中 FOLLOW 的用途被"语句收尾检查"
> （`finishStatement`：要求 `;` / EOF / 下一条语句关键字）替代，等效于按 FOLLOW(`statement`) 判断。
> 该文法不含二义性，故也无需处理 LL(1) 冲突。

---

## 5. 语法错误格式（与实现一致）

所有语法错误统一抛出 `SqxdlException(line, col, message)`，消息格式：

```
第{line}行第{col}列: unexpected token '{lexeme}'，期望: {终结符列表}
```

示例（课程要求）：对

```
SELECT name
FROM student
WHERE age > 18 AND;
```

在第 3 行第 19 列报告 `unexpected token ';'`，期望终结符列表 `IDENTIFIER | CONST | '(' | ')' | NOT`。
测试用例 `errorMessageTeacherExample` 逐项断言（第 3 行、第 19 列、消息内容）。

各上下文期望列表：

| 错误位置 | 期望终结符 |
|---|---|
| 表达式操作数（`parseOperand`） | `IDENTIFIER | CONST | '(' | ')' | NOT` |
| 语句分发（`parse`） | `SELECT | INSERT | UPDATE | DELETE | CREATE | SHOW` |
| 语句收尾（`finishStatement`） | `';' | EOF | SELECT | INSERT | UPDATE | DELETE | CREATE | SHOW` |
| SHOW 目标（`parseShow`） | `TABLE | TABLES` |
| 列类型（`expectType`） | `INT | VARCHAR` |
| 其他关键字 / 分隔符 / 运算符 | 对应终结符本身 |

单语句 `parse()` 出错直接抛出；多语句 `parseAll()` **错误恢复**：记录错误到 `getErrors()`，
丢弃到下一个 `;` 后继续解析后续语句。

---

## 6. AST 节点定义（输出契约）

所有节点继承 `ASTNode`，携带 `line` / `col`（源码位置）与预留 `type` 字段（语义阶段补充，如 `INT`/`VARCHAR`）。

| AST 节点 | 产生式 | 关键字段 |
|---|---|---|
| `SelectStmt` | `select_stmt` | `tableName`、`selectList`（`SELECT *` 存 `["*"]`）、`whereCond` |
| `InsertStmt` | `insert_stmt` | `tableName`、`columns`、`values`（`LiteralExpr` 列表） |
| `UpdateStmt` | `update_stmt` | `tableName`、`assignments`（`Map<String, LiteralExpr>`，保序）、`whereCond` |
| `DeleteStmt` | `delete_stmt` | `tableName`、`whereCond` |
| `CreateTableStmt` | `create_stmt` | `tableName`、`columns`（`List<ColumnDef>`） |
| `CreateTableStmt.ColumnDef` | `column_def` | `name`、`type`（`INT`/`VARCHAR`） |
| `BinaryExpr` | `comparison`/`and_expr`/`or_expr`/`additive`/`multiplicative` | `op`、`left`、`right` |
| `UnaryExpr` | `not_expr` | `op`（`NOT`）、`operand` |
| `IdentifierExpr` | `primary -> IDENTIFIER` | `name` |
| `LiteralExpr` | `literal` | `value`、`kind`（`NUMBER`/`STRING`/`BOOLEAN`） |

### 6.1 树结构示例（可扩展性说明）

`a = 1 OR b = 2 AND c = 3` 的 AST（与测试 `whereTeacherPrecedenceExample` 一致）：

```
BinaryExpr(OR)
├── BinaryExpr(=)   [a, 1]
└── BinaryExpr(AND)
    ├── BinaryExpr(=)   [b, 2]
    └── BinaryExpr(=)   [c, 3]
```

`NOT a = 1 AND b = 2` 的 AST：

```
BinaryExpr(AND)
├── UnaryExpr(NOT)
│   └── BinaryExpr(=)   [a, 1]
└── BinaryExpr(=)   [b, 2]
```

### 6.2 编译期优化（WHERE 条件）

Parser 在构建 WHERE 树后立即做后序遍历优化（常量折叠 + 逻辑简化）：

- 常量折叠：`age = 1 + 2 * 3` → `age = 7`；
- 逻辑简化：`x AND true → x`、`x AND false → false`、`x OR false → x`、`x OR true → true`。

即 B 组看到的 WHERE 树是**预优化后**的结果。
