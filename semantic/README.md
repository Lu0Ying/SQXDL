# Semantic 模块（语义分析与执行计划优化）

SQXDL 数据库系统编译器的中间层，位于 Parser（语法分析）与 Executor（执行器）之间。
负责对 AST 做语义合法性检查、生成逻辑执行计划、做条件优化，并将计划序列化为
存储引擎可接收的物理计划 JSON。

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 实现功能](#2-实现功能)
- [3. 源文件清单](#3-源文件清单)
- [4. 接口说明](#4-接口说明)
  - [4.1 CatalogImpl](#41-catalogimpl)
  - [4.2 TableMetadataProvider](#42-tablemetadataprovider)
  - [4.3 SemanticAnalyzer](#43-semanticanalyzer)
  - [4.4 PlanGenerator](#44-plangenerator)
  - [4.5 PlanNode](#45-plannode)
- [5. 执行流程](#5-执行流程)
- [6. 测试用例](#6-测试用例)

---

## 1. 模块定位

```
SQL 语句
  │
  ▼
Parser ──AST──▶ Semantic 模块 ──PlanNode──▶ Executor ──JSON──▶ storage_core.exe
                  │                              │
                  ├─ CatalogImpl（数据字典）
                  ├─ SemanticAnalyzer（语义检查）
                  ├─ PlanGenerator（计划生成 + 优化 + JSON 序列化）
                  └─ PlanNode（计划节点定义）
```

Semantic 模块只依赖 Parser 模块（`com.sqxdl.parser`），不直接依赖 Executor。
与存储引擎的同步通过依赖倒置实现：本模块定义 `TableMetadataProvider` 接口，
由 Executor 提供基于 `StorageClient` 的具体实现。

---

## 2. 实现功能

### 2.1 数据字典（CatalogImpl）

- 建表登记：`createTable`（默认 VARCHAR）/ `createTableWithTypes`（带类型）
- 元数据查询：表存在性、列存在性、列清单、列类型
- 删表：`dropTable`
- **与存储引擎同步（方案 B）**：
  - `syncFromStorage`：启动时全量同步（showTables + describeTable）
  - `syncTableFromStorage`：建表后增量更新单张表

### 2.2 语义分析（SemanticAnalyzer）

- **表存在性检查**：SELECT/INSERT/UPDATE/DELETE 引用的表必须存在
- **列存在性检查**：SELECT 列清单、WHERE 条件、INSERT/UPDATE 的列引用必须存在
- **类型兼容性检查**：
  - 比较运算两侧类型必须兼容（INT vs INT、VARCHAR vs VARCHAR）
  - 算术运算两侧必须都是 INT
  - 逻辑运算（AND/OR）两侧必须是 BOOLEAN
- **SELECT * 展开**：在语义分析阶段展开为完整列清单，供 PlanGenerator 复用
- **错误定位**：所有错误通过 `SqxdlException` 抛出，带行号/列号

### 2.3 执行计划生成与优化（PlanGenerator）

- **逻辑计划生成**：AST → PlanNode 树
  - SELECT：`Project → Filter(可选) → SeqScan`
  - INSERT/UPDATE/DELETE/CREATE TABLE/SHOW TABLES/DROP TABLE：单节点
- **常量折叠**：两边都是字面量时直接计算
  - 算术：`2+3 → 5`，除以零不折叠（留给运行时）
  - 比较：`1=1 → TRUE`，`'abc'='abc' → TRUE`
  - 逻辑：`TRUE AND FALSE → FALSE`
- **表达式化简**：
  - 逻辑恒等律：`cond AND TRUE → cond`，`cond OR FALSE → cond`
  - 算术单位元：`x+0 → x`，`x*1 → x`，`x-0 → x`，`x/1 → x`
- **恒真/恒假处理**：
  - 恒真条件 → 跳过 Filter（`WHERE 1=1` → 无 Filter）
  - 恒假条件 → 保留 `Filter(FALSE)`（执行器返回空集）
- **物理计划 JSON 序列化**：PlanNode → 单行 JSON（符合 storage_core 协议）

### 2.4 计划树可视化（PlanNode.formatPlan）

以缩进树形格式输出计划树，便于调试：

```
ProjectPlan
  columns: [id, name]
  FilterPlan
    condition: (age > 18)
    SeqScanPlan
      table: student
```

---

## 3. 源文件清单

```
semantic/
├── src/main/java/com/sqxdl/semantic/
│   ├── CatalogImpl.java            # 数据字典
│   ├── TableMetadataProvider.java   # 存储引擎元数据抽象接口
│   ├── SemanticAnalyzer.java        # 语义分析器
│   ├── PlanGenerator.java           # 计划生成器（含优化 + JSON 序列化）
│   └── PlanNode.java                # 计划节点定义 + 可视化
├── src/test/java/com/sqxdl/semantic/
│   ├── CatalogImplTest.java         # 数据字典测试（29 个）
│   ├── SemanticAnalyzerTest.java     # 语义分析测试（33 个）
│   └── PlanGeneratorTest.java        # 计划生成测试（65 个）
└── pom.xml
```

---

## 4. 接口说明

### 4.1 CatalogImpl

数据字典，维护表名 → 列信息清单的内存映射。

#### 类型定义

```java
public enum DataType { INT, VARCHAR, BOOLEAN }

public static class ColumnInfo {
    private final String name;
    private final DataType type;
    // getName(), getType()
}
```

#### 核心方法

| 方法 | 说明 |
|---|---|
| `createTable(String name, List<String> columns)` | 建表（默认 VARCHAR） |
| `createTableWithTypes(String name, List<ColumnInfo> columns)` | 建表（带类型） |
| `dropTable(String name)` | 删表 |
| `tableExists(String name)` | 表是否存在 |
| `getColumns(String name)` | 获取列名清单（表不存在返回 null） |
| `getColumnType(String table, String column)` | 获取列类型（不存在返回 null） |
| `columnExists(String table, String column)` | 列是否存在 |
| `getColumnInfos(String name)` | 获取完整列信息清单 |
| **`syncFromStorage(TableMetadataProvider provider)`** | **全量同步存储引擎元数据** |
| **`syncTableFromStorage(String name, TableMetadataProvider provider)`** | **增量同步单张表** |
| **`getAllTableNames()`** | **获取所有已登记表名** |

### 4.2 TableMetadataProvider

抽象「从存储引擎获取表结构」的接口，使 CatalogImpl 能与存储引擎同步
而不直接依赖 Executor 模块。

```java
public interface TableMetadataProvider {
    List<String> getTableNames();                         // 对应 showTables
    List<CatalogImpl.ColumnInfo> getTableColumns(String t); // 对应 describeTable
}
```

同步流程：
1. `getTableNames()` → 拿到所有表名
2. 对每张表 `getTableColumns()` → 拿到列名 + 类型
3. 注册到 CatalogImpl

Executor 模块提供实现类 `StorageTableMetadataProvider`（基于 `StorageClient`
调用 `showTables` + `describeTable`），也支持 Mock 实现做单元测试。

### 4.3 SemanticAnalyzer

```java
public SemanticAnalyzer(CatalogImpl catalog)

// 对 AST 做语义检查，失败时抛出 SqxdlException（带行/列号）
public void analyze(ASTNode ast)

// 获取 SELECT * 展开后的列清单（必须在 analyze() 之后调用）
public List<String> getExpandedColumns(ASTNode.SelectStmt stmt)
```

支持的语句类型：SELECT、INSERT、UPDATE、DELETE、CREATE TABLE、SHOW TABLES、DROP TABLE。

### 4.4 PlanGenerator

```java
public PlanGenerator(CatalogImpl catalog)

// 设置语义分析器（用于复用 SELECT * 展开结果，可选）
public void setAnalyzer(SemanticAnalyzer analyzer)

// AST → PlanNode 逻辑计划树（含条件优化）
public PlanNode generate(ASTNode ast)

// PlanNode → 物理计划 JSON（单行，符合 storage_core 协议）
public String toJson(PlanNode plan)
```

### 4.5 PlanNode

计划节点基类，以静态内部类定义 9 种节点：

| 节点 | 对应语句 | 结构 |
|---|---|---|
| `SeqScanPlan` | 全表扫描 | 叶子节点，含 tableName |
| `FilterPlan` | WHERE | 含 condition + child |
| `ProjectPlan` | SELECT 列清单 | 含 columns + child |
| `InsertPlan` | INSERT | 含 tableName + columns + values |
| `UpdatePlan` | UPDATE | 含 tableName + assignments + condition |
| `DeletePlan` | DELETE | 含 tableName + condition |
| `CreateTablePlan` | CREATE TABLE | 含 tableName + columns |
| `ShowTablesPlan` | SHOW TABLES | 无属性 |
| `DropTablePlan` | DROP TABLE | 含 tableName |

可视化方法：

```java
// 以缩进树形格式打印计划树
public static String formatPlan(PlanNode plan)
```

---

## 5. 执行流程

### 5.1 启动时同步 Catalog

```java
// Executor 端初始化
StorageClient client = new StorageClient();
StorageTableMetadataProvider provider = new StorageTableMetadataProvider(client);

CatalogImpl catalog = new CatalogImpl();
catalog.syncFromStorage(provider);  // 全量同步：showTables + describeTable

SemanticAnalyzer analyzer = new SemanticAnalyzer(catalog);
PlanGenerator generator = new PlanGenerator(catalog);
generator.setAnalyzer(analyzer);
```

### 5.2 SQL 语句处理

```java
// 1. Parser 解析 SQL → AST
ASTNode ast = parser.parse(sql);

// 2. 语义检查（表/列存在性、类型兼容性、SELECT * 展开）
analyzer.analyze(ast);

// 3. 生成执行计划（含常量折叠 + 表达式化简）
PlanNode plan = generator.generate(ast);

// 4. 序列化为物理计划 JSON
String json = generator.toJson(plan);

// 5. 调用存储引擎执行
StorageResult result = client.call(json);
```

### 5.3 建表后增量同步

```java
// CREATE TABLE 执行成功后，同步新表结构到 Catalog
catalog.syncTableFromStorage("new_table", provider);
```

---

## 6. 测试用例

共 **127 个**单元测试，全部通过。测试不依赖 Parser 和存储引擎，通过手动构造
AST 节点 + 内存 CatalogImpl 进行纯逻辑测试。

### 6.1 CatalogImplTest（29 个）

| 分类 | 测试项 | 数量 |
|---|---|---|
| 建表 | 成功建表、重复建表抛异常、多表共存 | 3 |
| 表存在性 | 不存在的表返回 false | 1 |
| 列查询 | 获取列清单、表不存在返回 null、防御性拷贝、输入修改不影响 | 4 |
| 带类型建表 | 成功、重复抛异常、默认 VARCHAR | 3 |
| 列存在性 | 存在返回 true、不存在返回 false | 2 |
| 列类型查询 | 表不存在返回 null、列不存在返回 null | 2 |
| 列信息查询 | 正确返回、不存在返回 null、防御性拷贝 | 3 |
| 删表 | 成功、不存在抛异常、不影响其他表 | 3 |
| **存储同步** | 全量同步、清空旧表、空存储清空、跳过空列表 | 4 |
| **增量同步** | 新增单表、覆盖旧表、空结果删表、getAllTableNames | 4 |

### 6.2 SemanticAnalyzerTest（33 个）

| 分类 | 测试项 | 数量 |
|---|---|---|
| SELECT 表存在性 | 存在成功、不存在抛异常 | 2 |
| SELECT 列存在性 | 存在成功、不存在抛异常 | 2 |
| SELECT * 展开 | 展开为全部列、非 * 返回原列 | 2 |
| WHERE 条件列检查 | 列存在成功、不存在抛异常（带定位） | 2 |
| 类型检查 | INT vs STRING 不兼容、INT vs INT 兼容、STRING vs STRING 兼容 | 3 |
| INSERT | 成功、值数不匹配、类型不兼容、带列清单、列不存在 | 5 |
| UPDATE | 成功、列不存在、类型不兼容 | 3 |
| DELETE | 成功、表不存在 | 2 |
| CREATE TABLE | 成功、已存在抛异常 | 2 |
| 嵌套表达式 | 算术嵌套成功、字符串算术抛异常 | 2 |
| AND/OR 类型检查 | 两边比较运算成功(AND/OR)、左侧非布尔、右侧非布尔、嵌套 AND/OR | 5 |
| SHOW TABLES | 成功 | 1 |
| DROP TABLE | 存在成功、不存在抛异常 | 2 |

### 6.3 PlanGeneratorTest（65 个）

| 分类 | 测试项 | 数量 |
|---|---|---|
| 基本计划生成 | 无 WHERE、有 WHERE、SELECT * 展开 | 3 |
| 常量折叠 | 恒真跳过 Filter、恒假保留 Filter、算术+比较、算术产数字、字符串比较、字符串不等、除零不折叠 | 7 |
| 逻辑化简 | AND TRUE、AND FALSE、OR TRUE、OR FALSE | 4 |
| 算术化简 | +0、*1、-0、/1 | 4 |
| 嵌套优化 | 双侧折叠、部分折叠 | 2 |
| UPDATE/DELETE 优化 | WHERE 恒真→null、WHERE 恒假→false、无 WHERE→null | 3 |
| 其他语句 | INSERT、CREATE TABLE | 2 |
| 与 SemanticAnalyzer 协作 | 使用展开列、自行展开、无展开时退化、带 WHERE 协作 | 4 |
| 计划树可视化 | SELECT 带/不带 WHERE、SELECT *、INSERT、UPDATE 带/不带条件、DELETE、CREATE TABLE、null | 9 |
| JSON 序列化 | SELECT 带/不带 WHERE、SELECT *、INSERT 带/不带列、UPDATE 带/不带条件、DELETE 带/不带条件、CREATE TABLE、布尔值、字符串值、嵌套表达式、单行、转义、与 storage 规范对比 | 16 |
| SHOW TABLES | 生成计划、JSON、可视化 | 3 |
| DROP TABLE | 生成计划、JSON、可视化 | 3 |
| AND/OR 优化 | AND TRUE 化简、OR FALSE 化简、AND FALSE 恒假、OR TRUE 恒真、JSON 序列化 | 5 |
