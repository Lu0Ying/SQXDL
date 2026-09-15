# Semantic 模块（语义分析与计划生成）

## 模块职责

对 Parser 产生的 AST 进行语义校验，并生成逻辑执行计划树（PlanNode），
供 Executor 执行。本模块是 Parser 与 Executor 之间的桥梁。

## 文件结构

```
semantic/
├── src/main/java/com/sqxdl/semantic/
│   ├── CatalogImpl.java          # 数据字典：表/列元数据管理
│   ├── TableMetadataProvider.java # 存储引擎元数据接口（抽象层）
│   ├── SemanticAnalyzer.java     # 语义分析器：表/列/类型校验
│   ├── PlanGenerator.java        # 计划生成器：AST → PlanNode + 优化 + JSON
│   ├── PlanNode.java             # 计划节点定义：14 种节点类型
│   └── AggregateFunction.java    # 聚合函数解析工具
├── src/test/java/com/sqxdl/semantic/
│   ├── SemanticAnalyzerTest.java
│   ├── PlanGeneratorTest.java
│   └── CatalogImplTest.java
├── PARSER_REQUIREMENTS.md        # Parser 同步需求文档
└── EXECUTOR_REQUIREMENTS.md      # Executor 对接需求文档
```

---

## 一、支持的 SQL 语句

| 语句 | 语法示例 | 语义校验 | 计划节点 |
|---|---|---|---|
| SELECT | `SELECT col1, col2 FROM table WHERE cond` | 表/列存在性、类型兼容 | Project → ... → SeqScan |
| SELECT * | `SELECT * FROM table` | 展开为全部列 | Project → SeqScan |
| JOIN | `SELECT ... FROM t1 JOIN t2 ON t1.id = t2.id` | ON 条件列校验、歧义检测 | Project → ... → Join |
| GROUP BY | `SELECT grade, COUNT(*) FROM t GROUP BY grade` | 分组列校验 | Project → GroupBy → ... |
| ORDER BY | `SELECT * FROM t ORDER BY col ASC` | 排序列校验 | Project → OrderBy → ... |
| 聚合函数 | `SELECT SUM(age), AVG(score) FROM t` | 参数列类型校验 | Project → GroupBy → SeqScan |
| INSERT | `INSERT INTO t (col1) VALUES (1)` | 列存在性、值类型兼容 | InsertPlan |
| UPDATE | `UPDATE t SET col = val WHERE cond` | 列/值类型、WHERE 条件 | UpdatePlan |
| DELETE | `DELETE FROM t WHERE cond` | 表存在性、WHERE 条件 | DeletePlan |
| CREATE TABLE | `CREATE TABLE t (id INT, name VARCHAR)` | 表名不重复 | CreateTablePlan |
| SHOW TABLES | `SHOW TABLES` | 无 | ShowTablesPlan |
| SHOW TABLE | `SHOW TABLE t` / `DESC t` | 表存在性 | DescribeTablePlan |
| DROP TABLE | `DROP TABLE t` | 表存在性 | DropTablePlan |

---

## 二、核心组件

### 2.1 CatalogImpl（数据字典）

内存中维护表名 → 列信息的映射。

**数据类型**：`INT`、`DOUBLE`、`VARCHAR`、`BOOLEAN`

**主要接口**：

| 方法 | 说明 |
|---|---|
| `createTableWithTypes(name, columnInfos)` | 建表（带类型） |
| `dropTable(name)` | 删表 |
| `tableExists(name)` | 表是否存在 |
| `getColumns(name)` | 获取列名列表 |
| `getColumnType(table, column)` | 获取列类型 |
| `columnExists(table, column)` | 列是否存在 |
| `syncFromStorage(provider)` | 从存储引擎全量同步元数据 |
| `syncTableFromStorage(provider, table)` | 单表增量同步 |

### 2.2 TableMetadataProvider（元数据接口）

抽象「从存储引擎获取表结构信息」的能力，由 Executor 模块实现。

```java
public interface TableMetadataProvider {
    List<String> getTableNames();                        // 对应 storage showTables
    List<CatalogImpl.ColumnInfo> getTableColumns(String); // 对应 storage describeTable
}
```

### 2.3 SemanticAnalyzer（语义分析器）

遍历 AST，借助 CatalogImpl 校验语义合法性。

**校验内容**：

1. **表存在性**：所有语句涉及的表名必须已在 Catalog 中注册
2. **列存在性**：SELECT/WHERE/INSERT/UPDATE/GROUP BY/ORDER BY 中的列
3. **列名歧义检测**：多表查询中，未限定表名的列若在多表中存在则报歧义
4. **点限定标识符**：`table.column` 语法，拆分后校验表与列
5. **类型兼容性**：
   - INT 与 DOUBLE 互相兼容（比较运算）
   - 算术运算两侧必须为数值
   - 逻辑运算（AND/OR）两侧必须为布尔
   - INSERT/UPDATE 值类型与列类型兼容
6. **聚合函数参数校验**：
   - SUM/AVG：参数列必须为数值类型（INT/DOUBLE）
   - MIN/MAX：参数列可为任意类型
   - COUNT：参数可为 `*` 或任意类型列
7. **SELECT * 展开**：展开为所有表的全部列（多表按表顺序拼接）

### 2.4 PlanGenerator（计划生成器）

将 AST 转换为 PlanNode 树，并进行多层 RBO 优化。

**主要接口**：

| 方法 | 说明 |
|---|---|
| `generate(ast)` | 生成优化后的计划树（生成即优化） |
| `build(ast)` | 生成未经条件优化的原始计划树（DEBUG 对比） |
| `optimize(plan)` | 对计划树做条件优化 |
| `setAnalyzer(analyzer)` | 注入语义分析器（复用 SELECT * 展开结果） |
| `toJson(plan)` | 序列化为 storage_core 物理计划 JSON |
| `formatPlan(plan)` | 树形格式化输出（调试用） |

### 2.5 PlanNode（计划节点）

14 种节点类型：

| 节点 | 对应 SQL | 关键字段 |
|---|---|---|
| SeqScanPlan | FROM | tableName, columns（列裁剪） |
| FilterPlan | WHERE | condition, child |
| ProjectPlan | SELECT | columns, child |
| JoinPlan | JOIN | children[], onConditions[], algorithms[] |
| GroupByPlan | GROUP BY | groupByColumns[], aggregates[], child |
| OrderByPlan | ORDER BY | orderByItems[]{column, direction}, child |
| InsertPlan | INSERT | tableName, columns[], values[] |
| UpdatePlan | UPDATE | tableName, assignments{}, condition |
| DeletePlan | DELETE | tableName, condition |
| CreateTablePlan | CREATE TABLE | tableName, columns[], columnDefs[] |
| ShowTablesPlan | SHOW TABLES | — |
| DescribeTablePlan | SHOW TABLE | tableName |
| DropTablePlan | DROP TABLE | tableName |

**JoinAlgorithm 枚举**：`NESTED_LOOP`（非等值/笛卡尔积）、`HASH`（等值连接）、`MERGE`（暂未使用）

### 2.6 AggregateFunction（聚合函数解析）

| 方法 | 说明 |
|---|---|
| `parse("SUM(age)")` | 解析为 name=SUM, argument=age |
| `isAggregate(column)` | 判断是否为聚合函数调用 |
| `resultType(argType)` | 返回聚合结果类型 |
| `isArgTypeValid(argType)` | 校验参数类型合法性 |
| `isCountStar()` | 是否为 COUNT(*) |

**支持的函数**：

| 函数 | 参数 | 结果类型 | 参数类型要求 |
|---|---|---|---|
| COUNT | `*` 或列名 | INT | 任意 |
| SUM | 列名 | 同参数类型 | 数值（INT/DOUBLE） |
| AVG | 列名 | DOUBLE | 数值（INT/DOUBLE） |
| MIN | 列名 | 同参数类型 | 任意 |
| MAX | 列名 | 同参数类型 | 任意 |

---

## 三、计划树结构

### 标准查询计划树层级

```
ProjectPlan          ← SELECT 列清单
  └─ OrderByPlan     ← ORDER BY（可选）
       └─ GroupByPlan ← GROUP BY / 聚合（可选）
            └─ FilterPlan ← WHERE 跨表谓词（可选）
                 └─ JoinPlan  ← JOIN（多表时）
                      ├─ SeqScanPlan(table1, columns)
                      ├─ FilterPlan(单表谓词) → SeqScanPlan(table2)
                      └─ ...
```

### 优化后的计划树示例

```sql
SELECT student.name, SUM(score.score)
FROM student JOIN score ON student.id = score.sid
WHERE student.age > 18 AND score.value > 60
GROUP BY student.name
ORDER BY student.name ASC
```

```
ProjectPlan(columns=[student.name, SUM(score.score)])
  └─ OrderByPlan(items=[{student.name, ASC}])
       └─ GroupByPlan(columns=[student.name], aggregates=[SUM(score.score)])
            └─ FilterPlan(condition=score.value > 60)  ← 跨表谓词
                 └─ JoinPlan(algorithms=[NESTED_LOOP, HASH])
                      ├─ SeqScanPlan(student, columns=[id, name, age])
                      ├─ FilterPlan(age > 18) → SeqScanPlan(score, columns=[sid, score])
                      └─ ...
```

---

## 四、优化功能

### 4.1 条件优化（表达式层面）

| 优化 | 示例 | 说明 |
|---|---|---|
| 常量折叠 | `2+3 → 5`、`1=1 → TRUE` | 两边都是字面量时直接计算 |
| 逻辑化简 | `cond AND TRUE → cond` | 利用恒等律/零元/单位元 |
| 算术恒等 | `x+0 → x`、`x*1 → x` | 零元/单位元消除 |
| NOT 化简 | `NOT TRUE → FALSE`、`NOT NOT x → x` | 一元表达式常量折叠与双否定消除 |
| 恒真过滤 | `WHERE TRUE` → 跳过 Filter | 恒真条件整个 Filter 节点移除 |
| 恒假保留 | `WHERE FALSE` → 保留 Filter(FALSE) | 让执行器返回空集 |

### 4.2 结构优化（计划树层面）

| 优化 | 说明 |
|---|---|
| **谓词下推** | WHERE 拆分为合取项，单表谓词下推到 SeqScan 之上，减少 JOIN 输入 |
| **列裁剪/投影下推** | 计算 SELECT/WHERE/JOIN ON/GROUP BY/ORDER BY 引用的列，SeqScan 只读必要列 |
| **RBO JOIN 顺序** | 有谓词的表优先、避免笛卡尔积、同等条件保持原序 |
| **JOIN 算法选择** | 等值连接 → HASH，非等值/笛卡尔积 → NESTED_LOOP |

### 4.3 优化开关

```java
PlanGenerator generator = new PlanGenerator(catalog);

// 生成即优化（默认）
PlanNode plan = generator.generate(ast);

// DEBUG 模式：分别展示优化前后
PlanNode raw = generator.build(ast);      // 未经条件优化的原始计划
PlanNode optimized = generator.optimize(raw); // 条件优化后的计划
```

> `build()` 只关闭条件优化（常量折叠/逻辑化简），结构优化（谓词下推/列裁剪/JOIN 顺序）仍会进行。

---

## 五、JSON 序列化

`PlanGenerator.toJson(plan)` 将计划树序列化为 storage_core 物理计划 JSON。

示例：

```json
{
  "op": "project",
  "columns": ["name"],
  "child": {
    "op": "filter",
    "condition": {
      "type": "binary",
      "op": ">",
      "left": {"type": "column", "name": "age"},
      "right": {"type": "literal", "value": 18}
    },
    "child": {
      "op": "scan",
      "table": "student",
      "columns": ["name", "age"]
    }
  }
}
```

支持的 JSON 节点：`scan`、`filter`、`project`、`join`、`groupBy`、`orderBy`、`insert`、`update`、`delete`、`createTable`、`showTables`、`describeTable`、`deleteTable`

---

## 六、与其他模块的依赖关系

```
Parser (A组)
  ↓ ASTNode
Semantic (B组) ← 本模块
  ↓ PlanNode
Executor (C组)
  ↓ physic plan JSON
Storage (独立程序)
```

- **依赖 Parser**：接收 ASTNode 进行分析
- **被 Executor 依赖**：提供 PlanNode、PlanGenerator、CatalogImpl、SemanticAnalyzer
- **通过 TableMetadataProvider 对接 Storage**：由 Executor 实现该接口，从 storage 获取表结构

---

## 七、对外部模块的依赖

### Parser 需提供的 ASTNode 类型

| AST 节点 | 用途 |
|---|---|
| SelectStmt | SELECT 语句（含 joins/groupBy/orderBy） |
| InsertStmt | INSERT 语句 |
| UpdateStmt | UPDATE 语句 |
| DeleteStmt | DELETE 语句 |
| CreateTableStmt | CREATE TABLE 语句 |
| ShowStmt | SHOW TABLES / SHOW TABLE |
| DropTableStmt | DROP TABLE 语句 |
| BinaryExpr | 二元运算（比较/逻辑/算术） |
| UnaryExpr | 一元运算（NOT） |
| IdentifierExpr | 列引用 |
| LiteralExpr | 字面量（NUMBER/STRING/BOOLEAN） |

### Parser 待扩展（见 PARSER_REQUIREMENTS.md）

- `parseSelectItem` 需支持 `SUM(col)`、`AVG(col)`、`MIN(col)`、`MAX(col)` 归一化为字符串

### Executor 待对接（见 EXECUTOR_REQUIREMENTS.md）

- `applyGrouping` 需接收 aggregates 参数并计算 SUM/AVG/MIN/MAX
- `hasAggregate` 需改用 `AggregateFunction.isAggregate()` 替代硬编码 COUNT(*)
