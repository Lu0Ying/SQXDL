# 存储核心（storage_core）输入输出规范

存储核心是一个独立的可执行程序，由 Semantic Analyzer 通过命令行参数传入
**physic plan（物理执行计划）**，执行完毕后向标准输出打印返回结果的 JSON。

## 1. 输入格式

### 1.1 调用命令

```bash
storage_core.exe "<physic_plan_json>"
```

- 参数：**一个**字符串，即 physic plan 构成的 JSON，位于 `argv[1]`。
- 该 JSON 由 Semantic Analyzer 在语义分析后生成，作为唯一的输入传给存储核心。

### 1.2 physic plan JSON 结构

物理计划是一个 JSON 对象，通过 `op` 字段标识操作类型；查询类计划以**树**形式组织
（父节点通过 `child` 引用子节点），写操作类计划为**单个**对象。

支持的 `op` 类型：

| op | 含义 | 返回结果 |
|---|---|---|
| `scan` | 全表扫描（叶子节点） | 数据集 |
| `filter` | 按条件过滤（对应 WHERE） | 数据集 |
| `project` | 投影指定列（对应 SELECT 列清单） | 数据集 |
| `insert` | 插入一行 | 行数 |
| `update` | 更新满足条件的行 | 行数 |
| `delete` | 删除满足条件的行 | 行数 |
| `createTable` | 建表 | 行数（0） |

条件表达式（`condition` 字段）通过 `type` 区分节点类型：

| type | 含义 | 字段 |
|---|---|---|
| `column` | 列引用 | `name`：列名 |
| `literal` | 字面量 | `value`：值（使用 JSON 原生类型，数字/字符串/布尔） |
| `binary` | 二元运算 | `op`：运算符，`left` / `right`：左右操作数 |

### 1.3 各操作示例

**查询（SELECT id, name FROM student WHERE id > 1）**

```json
{
  "op": "project",
  "columns": ["id", "name"],
  "child": {
    "op": "filter",
    "condition": {
      "type": "binary",
      "op": ">",
      "left": { "type": "column", "name": "id" },
      "right": { "type": "literal", "value": 1 }
    },
    "child": {
      "op": "scan",
      "table": "student"
    }
  }
}
```

> 说明：`SELECT *` 需由 Semantic Analyzer 在生成计划前展开为表的完整列清单，
> 存储核心不接收 `*`。

**插入（INSERT INTO student VALUES (1, 'Alice')）**

```json
{
  "op": "insert",
  "table": "student",
  "columns": ["id", "name"],
  "values": [1, "Alice"]
}
```

> `columns` 可省略，省略时按建表顺序对应 `values`。

**更新（UPDATE student SET name = 'Bob' WHERE id = 1）**

```json
{
  "op": "update",
  "table": "student",
  "set": { "name": "Bob" },
  "condition": {
    "type": "binary",
    "op": "=",
    "left": { "type": "column", "name": "id" },
    "right": { "type": "literal", "value": 1 }
  }
}
```

**删除（DELETE FROM student WHERE id = 1）**

```json
{
  "op": "delete",
  "table": "student",
  "condition": {
    "type": "binary",
    "op": "=",
    "left": { "type": "column", "name": "id" },
    "right": { "type": "literal", "value": 1 }
  }
}
```

**建表（CREATE TABLE student (id, name)）**

```json
{
  "op": "createTable",
  "table": "student",
  "columns": ["id", "name"]
}
```

> `update` / `delete` 的 `condition` 可省略，省略表示作用于全表所有行。

## 2. 输出格式

执行完毕后，存储核心向**标准输出（stdout）**打印**一行** JSON 结果，
统一外层结构为：

```json
{ "success": true, ... }   // 成功
{ "success": false, ... }  // 失败
```

通过 `type` 字段区分结果种类，`type` 与 `op` 对应（查询类为 `resultset`，
写操作类为 `rowcount`）。

### 2.1 SELECT：返回数据集

```json
{
  "success": true,
  "type": "resultset",
  "columns": ["id", "name"],
  "rows": [
    [1, "Alice"],
    [2, "Bob"]
  ]
}
```

- `columns`：结果列名列表，与 `project` 的 `columns` 一致。
- `rows`：二维数组，每个元素为一行，字段顺序与 `columns` 一致。

### 2.2 INSERT / UPDATE / DELETE：返回行数

```json
{
  "success": true,
  "type": "rowcount",
  "rowsAffected": 2
}
```

- `rowsAffected`：受影响的行数（`insert` 为 1，`update`/`delete` 为匹配行数，
  `createTable` 为 0）。

### 2.3 出错：返回错误信息

```json
{
  "success": false,
  "type": "error",
  "error": {
    "code": "TABLE_NOT_FOUND",
    "message": "表 student 不存在"
  }
}
```

- `error.code`：错误码（见下表），供调用方（Executor）程序化判断。
- `error.message`：面向用户的错误描述。

## 3. 错误码

| code | 含义 |
|---|---|
| `INVALID_PLAN` | 物理计划 JSON 无法解析或结构非法 |
| `TABLE_NOT_FOUND` | 引用的表不存在 |
| `TABLE_ALREADY_EXISTS` | 建表时表已存在 |
| `COLUMN_NOT_FOUND` | 引用的列不存在 |
| `TYPE_MISMATCH` | 类型不兼容（如整数与字符串比较） |
| `INTERNAL_ERROR` | 存储引擎内部错误 |

> 出错时进程以非零退出码结束；成功时以 0 结束。
