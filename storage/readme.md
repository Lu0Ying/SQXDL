# 存储核心（storage_core）输入输出规范

存储核心是一个独立的可执行程序，以**服务式**运行：启动后持续监测标准输入（stdin），
每读取一行 physic plan（物理执行计划）JSON 立即执行，并向标准输出（stdout）打印
一行返回结果的 JSON，直到收到退出指令。

## 1. 输入格式

### 1.1 调用命令与运行机制

```bash
storage_core.exe
```

- 输入：通过**标准输入（stdin）**逐行传入，**每行一个完整的 physic plan JSON**。
- 存储 core 启动后进入主循环：读取一行 → 执行 → 向 stdout 打印一行结果 → 继续读取。
- **退出指令**：读取到单独一行的 `exit`，或 plan `{"op": "exit"}` 时，进程正常退出
  （退出码 0），该行不产生任何输出。
- stdin 关闭（EOF）时同样正常退出。
- 空行（仅含空白字符）被忽略，不产生输出。
- 某一行 JSON 解析失败或 op 非法时，输出一行 `INVALID_PLAN` 错误并**继续**处理后续
  行，进程不会退出。

一次会话的输入输出示意（`>` 为输入，`<` 为输出）：

```text
> {"op":"createTable","table":"student","columns":["id","name"]}
< {"success":true,"type":"rowcount","rowsAffected":0}
> {"op":"showTables"}
< {"success":true,"type":"resultset","columns":["table"],"rows":[["student"]]}
> exit
（进程退出，无输出）
```

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
| `showTables` | 列出当前所有表（对应 SHOW TABLES） | 数据集（单列 `table`） |
| `deleteTable` | 删表（对应 DROP TABLE） | 行数（0） |

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

**列出所有表（SHOW TABLES）**

```json
{
  "op": "showTables"
}
```

> `showTables` 为单个对象（无 `child`、无 `table`），返回结果为数据集，
> 固定单列 `table`，每行为一个表名。

**删表（DROP TABLE student）**

```json
{
  "op": "deleteTable",
  "table": "student"
}
```

> `deleteTable` 为单个对象（无 `child`），表不存在时返回错误
> `TABLE_NOT_FOUND`。

> `update` / `delete` 的 `condition` 可省略，省略表示作用于全表所有行。

## 2. 输出格式

服务式运行下，每输入一行 physic plan，存储核心即向**标准输出（stdout）**打印
**一行** JSON 结果（一行进、一行出），统一外层结构为：

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

### 2.2 SHOW TABLES：返回表名数据集

```json
{
  "success": true,
  "type": "resultset",
  "columns": ["table"],
  "rows": [
    ["student"],
    ["course"]
  ]
}
```

- `columns` 固定为 `["table"]`。
- `rows`：每个元素为单元素数组，即一个表名；无表时为空数组。

### 2.3 INSERT / UPDATE / DELETE：返回行数

```json
{
  "success": true,
  "type": "rowcount",
  "rowsAffected": 2
}
```

- `rowsAffected`：受影响的行数（`insert` 为 1，`update`/`delete` 为匹配行数，
  `createTable` / `deleteTable` 为 0）。

### 2.4 出错：返回错误信息

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

> 服务式运行下，单行出错仅输出错误 JSON，进程继续运行；进程整体正常退出时
> 退出码为 0。

## 4. Java 调用示例（Hint）

Java 程序通过 `ProcessBuilder` 启动 `storage_core.exe`，用子进程的 stdin/stdout
按「一行进、一行出」的协议交互即可：

```java
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/** storage_core 服务客户端（一个实例对应一个存储核心进程） */
public class StorageClient implements AutoCloseable {

    private final Process process;
    private final BufferedWriter writer;  // 写入子进程 stdin
    private final BufferedReader reader;  // 读取子进程 stdout

    public StorageClient() {
        try {
            // 注意：ProcessBuilder 的可执行文件路径是相对 JVM 自身工作目录解析的，
            // 与 directory() 无关，因此必须使用绝对路径
            process = new ProcessBuilder("D:/Projects/SQXDL/SQXDL/storage/storage_core.exe")
                    .directory(new java.io.File("D:/Projects/SQXDL/SQXDL/storage"))
                    .start();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("无法启动 storage_core.exe", e);
        }
        // 编码固定 UTF-8，避免 Windows 默认 GBK 导致中文乱码
        writer = new BufferedWriter(new OutputStreamWriter(
                process.getOutputStream(), StandardCharsets.UTF_8));
        reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8));
    }

    /** 发送一行 physic plan JSON，阻塞返回一行结果 JSON */
    public synchronized String execute(String planJson) {
        try {
            writer.write(planJson);
            writer.newLine();   // 必须换行：服务按行读取
            writer.flush();     // 必须flush：否则服务收不到
            return reader.readLine();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("与 storage_core 通信失败", e);
        }
    }

    /** 发送 exit 正常结束服务进程 */
    @Override
    public synchronized void close() {
        try {
            writer.write("exit");
            writer.newLine();
            writer.flush();
            process.waitFor();  // 等待进程退出（退出码 0）
        } catch (Exception ignored) {
        } finally {
            process.destroy();
        }
    }
}
```

使用示例：

```java
try (StorageClient client = new StorageClient()) {
    String result = client.execute("{\"op\":\"showTables\"}");
    System.out.println(result);
    // {"columns":["table"],"rows":[],"success":true,"type":"resultset"}
    // 可用 Jackson/Gson 解析后判断 success 字段与 error.code
}
```

注意事项：

- **同步请求-响应**：每次 `execute()` 写一行后立即 `readLine()`。服务严格
  一行进一行出，这样最简单且安全；若改为「连续写多行再统一读」，当写入量
  超过管道缓冲区时会造成双方互相等待的死锁，必须用单独线程持续读取 stdout。
- **编码统一 UTF-8**：plan 中的中文（如表名、字符串值）依赖编码一致，
  不要使用平台默认字符集。
- **换行即协议**：每行必须以换行符结尾（`newLine()` 或 `\n`），空行会被服务忽略。
- **结束务必发 `exit`**：否则需依赖关闭 stdin（`writer.close()` 触发 EOF）退出，
  显式发送 `exit` 最可靠。
- 返回结果固定一行 JSON，`success=false` 时读取 `error.code` 做程序化处理。
