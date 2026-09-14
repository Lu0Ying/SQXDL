# SQXDL :: Parser（A 组）

词法 / 语法分析模块，负责把 SQL 文本解析为语法树（AST）。包含 6 个类：

| 类 | 职责 |
|---|---|
| `Lexer` | 词法分析：字符流 → Token 流（关键字/标识符/常量/运算符/分隔符） |
| `Parser` | 语法分析：Token 流 → AST（递归下降），含编译期优化 |
| `Token` | 词法单元，携带行列号（接口契约） |
| `ASTNode` | 语法树节点，全部携带行列号（接口契约） |
| `SqxdlException` | 统一异常类型：携带行列号与消息，供全组捕获打印 |
| `ParserDemo` | 交互式演示入口（自测用）：输入 SQL 打印 Token 流与 AST 树 |

> 本模块的输出（AST）是交给 B 组（semantic）的输入。**修改 `Token` / `ASTNode` 契约需全组同步。**

## 依赖

- JDK 17，Maven 3.6+
- 无外部依赖（JUnit 5 仅测试用）

## 快速上手（B 组用法）

1. 先发布到本地仓库，供其他模块引用：

```bash
mvn -pl parser install
```

2. 在其他模块中引入依赖（semantic / executor 通过传递依赖自动获得，无需显式声明）：

```xml
<dependency>
    <groupId>com.sqxdl</groupId>
    <artifactId>parser</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

3. 解析一条语句：

```java
Lexer lexer = new Lexer("SELECT id FROM student WHERE age >= 18;");
Parser parser = new Parser(lexer);
ASTNode root = parser.parse();                       // 单条语句
// 或解析多条（分号分隔）：
List<ASTNode> stmts = new Parser(new Lexer("SELECT 1; SELECT 2;")).parseAll();
```

## 交互式演示（自测用）

`ParserDemo` 提供交互式入口：逐行输入 SQL，同时打印 **Token 流**（类别 / 原文 / 行列 / KEYWORD 标注）与 **AST 树**（语句字段 + 表达式递归展开），空行退出。适合快速验证词法、优先级、常量折叠与 SHOW 等新语法。

```bash
mvn -pl parser compile
java -cp parser/target/classes com.sqxdl.parser.ParserDemo
```

或直接在 IDE 中运行 `ParserDemo` 的 `main` 方法。输出示例：

```
SQL> SHOW TABLE student;
[1] KEYWORD    'SHOW'  @1:1  [KEYWORD]
[2] KEYWORD    'TABLE' @1:6  [KEYWORD]
[3] IDENTIFIER 'student' @1:12
[4] DELIMITER  ';'  @1:19
--- AST ---
ShowStmt
  target : TABLE
  table  : student
```

> 该工具仅作 A 组自测与组间演示，不参与解析核心逻辑。

## 规范 SQL 还原（Pretty Printer）

`SqlPrinter.print(astNode)` 把 AST 递归还原成规范 SQL 文本（表达式统一加括号，与 AST 结构严格对应）：

```java
ASTNode stmt = new Parser(new Lexer("select * from t where age = 1 + 2")).parse();
String sql = SqlPrinter.print(stmt);   // SELECT * FROM t WHERE (age = 3)
```

三点用途：
1. **演示**：输入乱 SQL → 输出规范化 SQL，直观展示解析与编译期优化结果（常量折叠/逻辑简化在还原文本中直接可见）；
2. **验证**：round-trip（parse → print → parse）两次还原结果一致，可校验 AST 结构完整性；
3. **差分验证**：还原出的 SQL 可直接交给 H2 等工业级引擎执行，用成熟引擎交叉验证解析正确性（见测试段 DiffTest）。

## 支持的 SQL 语法

| 语句 | 语法 |
|---|---|
| SELECT | `SELECT 列清单 FROM 表名 [WHERE 条件]`，列清单支持 `*` |
| INSERT | `INSERT INTO 表名 [(列清单)] VALUES (值清单)`，省略列清单 = 按建表顺序 |
| UPDATE | `UPDATE 表名 SET 列 = 值 [, 列 = 值]* [WHERE 条件]` |
| DELETE | `DELETE FROM 表名 [WHERE 条件]` |
| CREATE TABLE | `CREATE TABLE 表名 (列名 INT\|VARCHAR [, 列名 INT\|VARCHAR]*)` |
| SHOW | `SHOW TABLES`（列出全部表）\| `SHOW TABLE 表名`（查看指定表结构） |
| DROP TABLE | `DROP TABLE 表名`（删除指定表及其数据，与 CREATE TABLE 对称） |

WHERE 条件表达式按优先级解析（低 → 高）：
`OR (||)` < `AND (&&)` < `NOT` < 比较（`=` `<` `>` `<=` `>=` `!=` `==`）< `+` `-` < `*` `/` < 原子（含 `( 表达式 )`）。
逻辑与/或支持关键字（`AND`/`OR`）与符号（`&&`/`||`）两种写法，节点中统一规范化为 `AND`/`OR` 或保持 `&&`/`||` 原样。
按文法 `not_expr -> NOT not_expr | comparison`，`NOT a = 1` 解析为 `NOT(a = 1)`；括号可改变结合结构，
如 `a = 1 AND (b = 2 OR c = 3)`。文法细节见 [grammar.md](grammar.md)。

其他约定：

- 关键字不区分大小写；字符串字面量 `'Tom'` 存不带引号的 `Tom`，转义 `''` 表示单引号。
- 支持 `-- 行注释` 与 `/* 块注释 */`。
- 多条语句之间分号可省略；语句结束要求为 `;`、EOF 或下一条语句开头。
- 常见拼写错误（编辑距离 = 1）自动纠正，如 `selec` → `SELECT`、`form` → `FROM`。纠错提示由 Lexer 收集（不直接打印），调用方通过 `lexer.getSpellWarnings()` 取回，格式如 `[1:1] 拼写提示：将 'selec' 纠正为 'SELECT'`。**长度小于 2 的标识符不参与纠错**（保护单字母列名 `a`/`b`/`c` 不被短关键字 `BY`/`ON` 误判）。
- JOIN 的 ON 条件支持**点限定列名**（`t1.id`、`stu.name`）：词法层把 `表名.列名` 整体识别为一个 IDENTIFIER，语义层按 `.` 拆分表名与列名。

## 输出契约（AST 节点一览）

所有节点都继承 `ASTNode`，自带 `getLine()` / `getCol()`（指向语句或运算符起始位置）。

| 节点 | 关键字段 / getter | 说明 |
|---|---|---|
| `SelectStmt` | `tableName`、`selectList`、`whereCond` | `whereCond` 无 WHERE 时为 `null` |
| `InsertStmt` | `tableName`、`columns`、`values` | `columns` 空 = 按建表顺序；`values` 为 `LiteralExpr` 列表 |
| `UpdateStmt` | `tableName`、`assignments`、`whereCond` | `assignments` 为 `Map<String, LiteralExpr>`，保持 SET 书写顺序 |
| `DeleteStmt` | `tableName`、`whereCond` | 同上，`whereCond` 可为 `null` |
| `CreateTableStmt` | `tableName`、`columns` | `columns` 为 `List<ColumnDef>`（`ColumnDef` 含 `name` 与 `type`） |
| `ShowStmt` | `target`、`tableName` | `target` ∈ `TABLES` / `TABLE`；`target=TABLE` 时 `tableName` 为表名，否则为 `null`。`DESCRIBE t` / `DESC t` 归一化为 `target=TABLE` |
| `DropTableStmt` | `tableName` | 语义层据此删除指定表及其数据 |
| `BinaryExpr` | `op`、`left`、`right` | 表达式节点，`left`/`right` 可为嵌套表达式 |
| `UnaryExpr` | `op`、`operand` | 一元运算（`NOT`），`operand` 为被作用表达式 |
| `IdentifierExpr` | `name` | 标识符（列名）引用，WHERE 中的列名一律用此节点 |
| `LiteralExpr` | `value`、`kind` | `kind` ∈ `NUMBER` / `STRING` / `BOOLEAN` |

所有节点继承 `ASTNode` 基类，除 `getLine()` / `getCol()` 外还提供预留的 `getType()` / `setType()`，
供语义分析阶段补充节点类型（类型注解扩展点）。

### 关键约定（B 组需知）

1. **`SELECT *`**：`selectList` 存单元素 `["*"]`，**展开成真实列名由 B 组在语义分析阶段完成**（需查 Catalog）。
2. **`LiteralExpr.kind` 区分类型**：`WHERE id = 1` 的右操作数是 `kind=NUMBER`，`WHERE id = '1'` 是 `kind=STRING`；`true`/`false` 是 `kind=BOOLEAN`，统一存大写 `TRUE`/`FALSE`。
3. **`IdentifierExpr` 与 `LiteralExpr`** 是表达式的两种叶子，语义分析据此区分"列"与"常量"。
4. **`UnaryExpr`（NOT）与括号**：`NOT a = 1` 表示 `NOT(a = 1)`（NOT 作用于整个比较结果）；括号不产生额外节点，`( 表达式 )` 直接返回内部表达式节点。
5. **`CreateTableStmt` 列带类型**：`CREATE TABLE t (id INT)` 的 `columns` 是 `ColumnDef(name, type)` 列表；语义层如需列名请取 `ColumnDef::getName`，`ColumnDef::getType` 提供列类型。
6. **常量已折叠**：Parser 已把 `age = 1+2` 化简为 `age = 3`、`cond AND true` 化简为 `cond`（见下文），B 组看到的 WHERE 树是优化后的结果。
7. **`ShowStmt` 两种形态**：`SHOW TABLES` → `target=TABLES`、`tableName=null`（语义层查全部表清单）；`SHOW TABLE t` → `target=TABLE`、`tableName=t`（语义层查指定表结构）。`target` 统一存大写。
8. **`DropTableStmt`**：仅含 `tableName`，语义层删除表及其数据；无 IF EXISTS 支持，表不存在由语义层报错。

## 错误处理

所有错误统一抛出 `SqxdlException(line, col, message)`（继承 `RuntimeException`）。
语法类错误消息格式对齐课程要求：**第N行第M列: unexpected token 'X'，期望: 终结符列表**，例如：

```
第3行第19列: unexpected token ';'，期望: IDENTIFIER | CONST | '(' | ')' | NOT
```

单语句 `parse()` 遇错直接抛出；多语句 `parseAll()` 具备**错误恢复**：语法错误与词法错误（非法字符等）
都记入 `getErrors()`，跳过到下一个 `;`（或 EOF）后继续解析后续语句，坏语句不中断整体批处理。

```java
Parser p = new Parser(new Lexer("SELECT 1 2; SELECT id FROM t;"));
List<ASTNode> stmts = p.parseAll();   // stmts 只含第 2 条
for (SqxdlException e : p.getErrors()) {
    System.out.println(e.getMessage());   // 形如 第1行第8列: unexpected token '1'，期望: IDENTIFIER
}
```

语义类错误（表/列不存在、类型不兼容）请 B 组同样抛 `SqxdlException`，供 D 组统一捕获打印。
> `SqxdlException` 定义在 parser 包内，semantic / executor 经传递依赖可直接使用；**storage 模块若需抛出，请在 storage 的 pom 中显式依赖 parser**。

## 编译期优化（WHERE 条件）

Parser 在构建 WHERE 表达式树后立即做两层优化（后序遍历）：

| 优化 | 规则 | 示例 |
|---|---|---|
| 常量折叠 | 算术运算且两侧都是数字字面量，直接算出结果；除零报错 | `age = 1 + 2 * 3` → `age = 7` |
| 逻辑简化 | `x AND true → x`，`x AND false → false`，`x OR false → x`，`x OR true → true` | `age > 18 AND true` → `age > 18` |

动机：WHERE 条件在 executor 中逐行求值，编译期能省的运算不在运行期重复。折叠后若 WHERE 恒真/恒假，executor 可直接跳过全表过滤。

## 测试

```bash
mvn -pl parser test
```

`LexerTest`（23 例）覆盖五类 Token、注释、转义、行列号、拼写纠错与非法输入；`ParserTest`（77 例）覆盖语句解析（含 SHOW/DESCRIBE/DESC、DROP TABLE、JOIN、GROUP BY、ORDER BY）、表达式优先级（含课程示例 `a = 1 OR b = 2 AND c = 3`）、NOT/括号、列类型、常量折叠、逻辑简化、错误格式（`unexpected token` + 期望终结符列表）、错误恢复与多语句输入；`EdgeCaseTest`（22 例）集中覆盖词法、拼写纠错、表达式、SHOW/DROP 边界与错误恢复临界场景；`FuzzTest`（4 组 jqwik 属性测试，每组随机数百组输入）验证 Lexer/Parser 对任意输入只抛统一异常 `SqxdlException`、错误恒带 `[行:列]` 定位前缀、合法模板 SQL 不崩溃；`SqlPrinterTest`（8 例）验证规范 SQL 还原文本与 round-trip 稳定性；`DiffTest`（4 例）用 H2 内存库执行还原出的 SQL 做差分验证（CRUD 全流程、JOIN/GROUP BY/ORDER BY、常量折叠、转义字符串）。共 138 例。

> 测试依赖：`net.jqwik:jqwik:1.8.5`（模糊测试）、`com.h2database:h2:2.2.224`（差分验证），均 test scope，见 `parser/pom.xml`。模糊测试期间抓出并修复的真实缺陷：非法字符抛错未推进位置（错误恢复死循环风险）、`parseAll` 错误恢复未覆盖词法错误——现均已修复，词法/语法错误统一参与错误恢复。
