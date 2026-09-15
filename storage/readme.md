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
> {"op":"createTable","table":"student","columns":[{"name":"id","type":"INT"},{"name":"name","type":"VARCHAR"}]}
< {"success":true,"type":"rowcount","rowsAffected":0}
> {"op":"showTables"}
< {"success":true,"type":"resultset","columns":["table"],"rows":[["student"]]}
> exit
（进程退出，无输出）
```

### 1.2 physic plan JSON 结构

物理计划是一个 JSON 对象，通过 `op` 字段标识操作类型；查询类计划以**树**形式组织
（一元节点通过 `child` 引用子节点，二元连接节点通过 `left` / `right` 引用左右子树），
写操作类计划为**单个**对象。

支持的 `op` 类型：

| op | 含义 | 返回结果 |
|---|---|---|
| `scan` | 全表扫描（叶子节点） | 数据集 |
| `filter` | 按条件过滤（对应 WHERE） | 数据集 |
| `project` | 投影指定列（对应 SELECT 列清单） | 数据集 |
| `join` | 连接两个数据集（对应 JOIN） | 数据集 |
| `insert` | 插入一行 | 行数 |
| `update` | 更新满足条件的行 | 行数 |
| `delete` | 删除满足条件的行 | 行数 |
| `createTable` | 建表 | 行数（0） |
| `showTables` | 列出当前所有表（对应 SHOW TABLES） | 数据集（单列 `table`） |
| `deleteTable` | 删表（对应 DROP TABLE） | 行数（0） |
| `describeTable` | 查看表结构（对应 DESCRIBE / DESC） | 数据集（列 `column` + `type`） |
| `exit` | 退出服务（进程结束，不产生输出） | 无 |

条件表达式（`condition` 字段）通过 `type` 区分节点类型：

| type | 含义 | 字段 |
|---|---|---|
| `column` | 列引用 | `name`：列名 |
| `literal` | 字面量 | `value`：值（使用 JSON 原生类型，数字/字符串/布尔/null） |
| `binary` | 二元运算 | `op`：运算符，`left` / `right`：左右操作数 |

> `binary` 支持的运算符：比较 `=`、`!=`、`<>`、`<`、`<=`、`>`、`>=`；
> 逻辑 `AND`、`OR`（`AND` / `OR` 大小写不敏感）；算术 `+`、`-`、`*`、`/`
> （两操作数须为数字，双目均为整数则结果为整数，含浮点则结果为浮点；除数为 0 结果为 null）。

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

**连接（SELECT student.name, score.score FROM student JOIN score ON student.id = score.student_id）**

```json
{
  "op": "join",
  "type": "inner",
  "left": { "op": "scan", "table": "student" },
  "right": { "op": "scan", "table": "score" },
  "condition": {
    "type": "binary",
    "op": "=",
    "left": { "type": "column", "name": "id" },
    "right": { "type": "column", "name": "student_id" }
  }
}
```

> `join` 为二元节点：`left` / `right` 为左右子计划（可为任意查询子树），
> `type` 取 `inner`（缺省）、`left`、`right`、`cross`；
> 除 `cross` 外 `condition` 必填，`cross` 不得携带 `condition`。
> 两侧结果列重名时自动加来源前缀消歧（前缀取节点上的 `alias`，
> 缺省为扫描的表名，如 `student.id`），`condition` 与上层 `project` 即按结果列名引用；
> 外连接未匹配一侧的字段以 `null` 填充。

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

> `update` / `delete` 的 `condition` 可省略，省略表示作用于全表所有行。

**建表（CREATE TABLE student (id INT, name VARCHAR)）**

```json
{
  "op": "createTable",
  "table": "student",
  "columns": [
    { "name": "id", "type": "INT" },
    { "name": "name", "type": "VARCHAR" }
  ]
}
```

> `columns` 为列定义数组，每个元素为对象 `{ "name": 列名, "type": 类型 }`；
> 类型取值 `INT`、`DOUBLE`、`VARCHAR`、`BOOLEAN`（大小写不敏感）。

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

**查看表结构（DESCRIBE student）**

```json
{
  "op": "describeTable",
  "table": "student"
}
```

> `describeTable` 为单个对象（无 `child`），返回该表按建表顺序排列的列名
> 与类型数据集；表不存在时返回错误 `TABLE_NOT_FOUND`。

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

- `columns`：结果列名列表（`project` 取其 `columns`；`scan` 为表的全部列名，
  `filter` 与 child 一致，`join` 见下方命名规则）。
- `rows`：二维数组，每个元素为一行，字段顺序与 `columns` 一致。

> **JOIN 结果列命名**：连接结果同属 `resultset`，`columns` 按下述规则生成——
> 左右两侧重名的列加来源前缀 `别名.列名`（别名取节点 `alias`，缺省为扫描表名），
> 不重名的列保持原列名；`condition` 与上层 `project` 均通过该结果列名引用。
> 若加前缀后仍重名（例如未设 `alias` 的自连接）返回 `INVALID_PLAN`。

### 2.2 SHOW TABLES：返回表名数据集

```json
{
  "success": true,
  "type": "resultset",
  "columns": ["table"],
  "rows": [
    ["course"],
    ["student"]
  ]
}
```

- `columns` 固定为 `["table"]`。
- `rows`：每个元素为单元素数组，即一个表名，按表名字典序排列；无表时为空数组。

### 2.3 DESCRIBE TABLE：返回表结构数据集

```json
{
  "success": true,
  "type": "resultset",
  "columns": ["column", "type"],
  "rows": [
    ["id", "INT"],
    ["name", "VARCHAR"],
    ["age", "INT"]
  ]
}
```

- `columns` 固定为 `["column", "type"]`。
- `rows`：每个元素为双元素数组 `[列名, 类型]`，按建表时的列顺序排列；
  类型取值与建表时一致，包括 `INT`、`DOUBLE`、`VARCHAR`、`BOOLEAN`。
  表不存在时返回 `TABLE_NOT_FOUND` 错误。

### 2.4 INSERT / UPDATE / DELETE：返回行数

```json
{
  "success": true,
  "type": "rowcount",
  "rowsAffected": 2
}
```

- `rowsAffected`：受影响的行数（`insert` 为 1，`update`/`delete` 为匹配行数，
  `createTable` / `deleteTable` 为 0）。

### 2.5 出错：返回错误信息

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

## 4. 数据持久化

存储核心默认把数据落在本机固定目录 `/SQXDL/data/`（无需额外配置；Windows 上
解析为当前盘符根目录下的 `\SQXDL\data\`），目录不存在时自动创建：

| 文件 | 内容 | 写回时机 |
|---|---|---|
| `catalog.json` | 表结构（表名 -> 列定义） | `createTable` / `deleteTable` |
| `storage.db` | 表内行数据（按 4KB 堆页组织，行与页对齐） | `insert` / `update` / `delete` / `deleteTable` |

- 表结构与行数据**分离存放**：`catalog.json` 只含 schema，不含任何行数据。
- 行数据按「slotted 堆页 + 页链」组织：每行是该页内的一个元组，写入只追加到链尾页，
  单行插入/更新/删除只改写 1~2 个页，**不会重写整张表**；页缓存（最近使用的 64 页）由缓冲池管理，
  缺页时按需从磁盘读入，因此查询过程**不会把整库行数据读进内存**。
- 行数据页由缓冲池在 LRU 淘汰或进程正常退出时写回磁盘；表 -> 数据页链的目录发生变化时立即落盘。
- 进程启动后首次执行任一操作时会自动从上述文件恢复表结构与行数据，
  因此**重启进程后数据依然存在**；同一目录下多次运行等价于操作同一个库。
- `deleteTable` 会回收该表占用的全部数据页并更新目录。
- 存储目录不可访问、文件损坏等异常返回 `INTERNAL_ERROR`。
- 单行序列化后长度上限为 4084 字节，超长行返回 `INTERNAL_ERROR`。
- 注意：行数据文件格式已升级（旧版为「整表字节块」格式）；若用旧版 `storage.db` 启动，
  会返回 `INTERNAL_ERROR`（提示 `legacy row layout, please rebuild the database`），需重建库。

## 5. Java 调用示例（Hint）

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
            // 与 directory() 无关，因此必须使用绝对路径；以下路径按实际部署位置调整
            process = new ProcessBuilder("D:/Projects/SQXDL/storage/storage_core.exe")
                    .directory(new java.io.File("D:/Projects/SQXDL/storage"))
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
