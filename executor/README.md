# Executor 执行模块（D 组）

系统的最上层：提供 CLI 交互终端与 Swing 图形界面两种入口，把语义模块生成的计划交给
存储核心（`storage_core.exe`）执行；存储核心不可用时自动回退内置示例数据模拟执行，
保证 Demo 脱离 C++ 程序也能完整演示。同时在 executor 模块内为 B 组的方案 B 提供
`TableMetadataProvider` 桥接实现，使数据字典能从存储核心同步真实表结构。

## 目录

- [1. 文件结构](#1-文件结构)
- [2. 整体数据流](#2-整体数据流)
- [3. 各文件说明](#3-各文件说明)
- [4. 支持的 SQL 输入](#4-支持的-sql-输入)
- [5. 数据如何存储](#5-数据如何存储)
- [6. 运行方式](#6-运行方式)
- [7. 设计原则](#7-设计原则)

---

## 1. 文件结构

```
executor/
├── pom.xml                                    # Maven 模块配置
└── src/main/java/com/sqxdl/
    ├── executor/
    │   ├── Main.java                          # CLI 入口（REPL）
    │   ├── Executor.java                      # 计划执行 + 结果输出
    │   ├── SqlEngine.java                     # CLI/GUI 共用执行引擎
    │   ├── CommandHistory.java                # 输入历史（GUI ↑↓ / CLI 管道模式 !N）
    │   └── storage/
    │       ├── StorageClient.java             # 存储核心服务式会话客户端
    │       ├── StorageTableMetadataProvider.java # 方案 B 桥接：目录同步
    │       ├── StorageResult.java             # 统一结果封装
    │       ├── PhysicalPlanJson.java          # 计划 → JSON 序列化
    │       └── Json.java                      # 手写 JSON 解析器
    └── swing/
        └── SwingDemo.java                     # Swing 图形界面
```

---

## 2. 整体数据流

```
Main (CLI) ──────────────┐
                         ├─→ SqlEngine ─→ Lexer/Parser → 语义分析 → 计划生成
SwingDemo (GUI) ─────────┘                     │
                                    ┌──────────┴──────────┐
                                    │ LOCAL: 内置数据模拟   │
                                    │ AUTO: StorageClient  │─失败自动回退─┘
                                    └──────────┬──────────┘
                                  JSON → stdin → storage_core.exe
                                  stdout JSON → StorageResult
```

启动时（AUTO 模式）额外执行**元数据同步**：`StorageTableMetadataProvider` 调用
`showTables` + `describeTable`，把存储核心里已有的表结构同步进数据字典；存储核心
为空库时自动预置示例数据（走完整流水线真实落库，重启后依然存在）。

---

## 3. 各文件说明

### 入口层

- **Main.java** — CLI 交互终端（REPL）。`sqxdl>` 提示符循环读入 SQL，`exit` 退出。
  输入按 TTY 自动分路径（共用同一条 `processInput` 处理逻辑）：
  **真终端**走 JLine —— ↑↓ 翻历史、Ctrl+R 搜索、行内编辑；**管道/重定向**回退
  Scanner —— 用 `history` / `!N` / `!!` 元命令等效替代。每轮 SQL 包在 try-catch 中，
  出错只打印错误并继续循环，REPL 永不崩溃。退出前（finally）调用 `engine.close()`
  让存储核心落盘。
- **CommandHistory.java** — CLI 与 GUI 共用的输入历史：连续重复去重、
  `!N`/`!!` 解析、带编号清单输出（GUI ↑↓ 翻阅与 CLI 管道模式都依赖它）。
- **SwingDemo.java** — 图形界面。顶部连接/断开按钮，左侧表列表 + 右侧数据表格，底部
  SQL 输入区 + 日志区。所有执行委托 `SqlEngine`（AUTO 模式，数据持久化），回车直接
  执行（Shift+Enter 换行），与 CLI 体验一致。输入框 ↑/↓ 翻阅输入历史（光标在
  首行/末行时触发，翻回最新位置恢复未执行的草稿，多行编辑不受影响）。
  CREATE TABLE 成功后左侧表列表实时刷新；
  断开连接与窗口关闭都会以协议 exit 结束存储服务（数据落盘）；左侧列表在启动时从
  存储核心同步真实表清单。

### 执行层

- **SqlEngine.java** — CLI 与 GUI 共用的执行门面。完整流水线：SQL 文本 → Lexer/Parser
  （A 组）→ 语义分析（B 组）→ 计划生成（含常量折叠）→ 执行。两种模式：
  - `AUTO`（CLI 与 GUI 默认）：走真实存储核心；启动时做元数据同步（空库自动预置
    示例数据）；执行失败（`STORAGE_UNAVAILABLE`/`STORAGE_TIMEOUT`）自动回退模拟并
    记录原因（`getFallbackReason()`）；
  - `LOCAL`（仅显式选择或回退时）：始终用内置示例数据模拟，数据保存在 JVM 内。
  - 元数据一致性：CREATE TABLE 成功后按列定义带类型登记进数据字典；DROP TABLE 在
    执行成功（或回退模拟）后同步清理数据字典与模拟数据。
  - 模拟执行支持 SELECT（投影 + WHERE 过滤）、INSERT、UPDATE、DELETE、
    CREATE TABLE、SHOW TABLES、DROP TABLE；条件求值 `evalExpr` 递归处理 NOT 与
    二元表达式（AND/OR 短路、算术、数值/字符串自适应比较）。
- **Executor.java** — CLI 结果展示（`render`）。数据集画成按列宽对齐的文本表格、
  行数或错误。只做展示，不感知存储协议与执行细节。

### 存储桥接层（storage/）

- **StorageClient.java** — 服务式会话调用 `storage_core.exe`：进程常驻主循环
  （读一行计划 → 输出一行结果），建表目录与数据跨语句保持。计划 JSON 走 stdin
  （Windows 命令行参数中的双引号会被 CRT 剥离，跨语言传 JSON 必须走 stdin），结果
  从 stdout 读一行 JSON。单次执行 10 秒超时；进程退出或非法返回时自动重建会话。
  **会话关闭采用协议 exit 优雅退出**（2 秒未退出才强杀）——存储核心只有在收到
  `exit` 正常结束时才把行数据落盘，强杀会丢数据。exe 缺失、启动失败、超时全部
  封装为带错误码的 ERROR 结果，调用方永不崩溃。exe 路径可用
  `-Dsqxdl.storage.exe=<路径>` 覆盖。
- **StorageTableMetadataProvider.java** — 实现 B 组的 `semantic.TableMetadataProvider`
  接口：`getTableNames()` ↔ `showTables`，`getTableColumns()` ↔ `describeTable`
  （兼容带类型/不带类型两种返回格式），供数据字典同步真实表结构。
- **PhysicalPlanJson.java** — 把计划树按存储核心协议转 JSON：查询计划为
  `project → filter → scan` 嵌套树，写操作为单对象；建表列输出带类型的对象数组
  `[{"name":..,"type":..}]`；删表 op 为 `deleteTable`；条件递归写出
  （binary/column/literal），运算符归一化（`==`→`=`、`&&`→`AND`、`||`→`OR`），
  UPDATE/DELETE 无 WHERE 时省略 condition 字段。
- **Json.java** — 递归下降 JSON 解析器（对象 → LinkedHashMap、数字按小数点/指数
  区分 Long/Double）与字符串转义（`quote`）。手写实现避免第三方依赖。
- **StorageResult.java** — 统一结果封装：`RESULTSET`（列名 + 行数据）、`ROWCOUNT`
  （受影响行数）、`ERROR`（错误码 + 信息）；提供静态工厂与 `parse()`。

---

## 4. 支持的 SQL 输入

**每条语句必须以分号 `;` 结尾**，缺失时返回 `SYNTAX_ERROR`。分号判定会先剥离
注释（`--` 行注释与 `/* ... */` 块注释）：分号后可以跟注释，注释里出现的分号不算数。

```sql
CREATE TABLE student (id INT, name VARCHAR, gpa DOUBLE);   -- 建表（INT/VARCHAR/DOUBLE）
INSERT INTO student VALUES (1, 'Alice', 92.5);             -- 插入
SELECT * FROM student;                                     -- 全表查询
SELECT name, gpa FROM student WHERE gpa > 90;              -- 投影 + 条件
SELECT * FROM student WHERE NOT age > 20 AND id < 5;       -- 逻辑组合（&&/|| 等价）
SELECT * FROM student WHERE gpa + 1 > 90;                  -- 条件内算术*
UPDATE student SET gpa = 95.0 WHERE id = 1;                -- 更新（可省 WHERE，SET 值须为常量）
DELETE FROM student WHERE id = 2;                          -- 删除（可省 WHERE）
SELECT name, gpa FROM student WHERE gpa > 90 ORDER BY gpa DESC;   -- 排序（多键可逗号续写）
SELECT grade, COUNT(*) FROM student GROUP BY grade;        -- 分组计数（每组输出一行）
SELECT COUNT(*) FROM student;                              -- 全表计数（无 GROUP BY）
SELECT name, title FROM student JOIN course ON id = cid;   -- 内连接（可链式多表）
SHOW TABLES;                                               -- 列出全部表
SHOW TABLE student;                                        -- 查看表结构（列名 + 类型）
DROP TABLE student;                                        -- 删表（同时清理目录与数据）
```

- `exit` 退出（不区分大小写），空行跳过；
- 拼写相近的关键字给出纠错提示（如 `SELEC` → `SELECT`）；
- 执行前自动做语义校验：表/列存在性、INSERT 值个数、值类型与列类型兼容性
  （INT 与 DOUBLE 数值互通，字符串不能进数值列）。

\* 条件内算术是否可用取决于存储核心版本：部分版本的存储核心未实现条件表达式中的
算术运算（返回 `INVALID_PLAN`），Java 侧序列化与模拟层均支持。

---

## 5. 数据如何存储

**AUTO 模式（真实存储核心）** —— 数据由 C++ 核心持久化到 `\SQXDL\data\`
（当前盘符根目录，自动创建），schema 与行数据分离：

| 文件 | 内容 | 写盘时机 |
|---|---|---|
| `catalog.json` | 表结构（表名 → 列定义） | createTable / deleteTable 立即写 |
| `storage.db` | 行数据（按 4KB 页组织） | 进程收到协议 exit 正常退出时刷盘 |

因此同一次运行内写完即可查；**正常退出 REPL 后数据跨进程持久存在**（协议 exit
保证行数据落盘）。删除 `C:\SQXDL\data` 目录即可重置数据库。

**LOCAL 模式（回退/演示）** —— 数据保存在 JVM 内存（`SqlEngine` 内置示例表 +
会话内新建的表），不落盘，程序退出即消失，仅用于脱机演示。

---

## 6. 运行方式

在项目根目录：

```bash
mvn -pl executor -am package    # 编译 executor 及其依赖模块（自动下载 JLine 依赖）

# CLI 交互终端（AUTO 模式，真实存储核心；真终端下支持 ↑↓ 历史）
mvn -pl executor exec:java -Dexec.mainClass=com.sqxdl.executor.Main

# Swing 图形界面（AUTO 模式，数据持久化于存储核心）
mvn -pl executor exec:java -Dexec.mainClass=com.sqxdl.swing.SwingDemo
```

Windows 分隔符为 `;`，Linux/macOS 为 `:`。也可直接在 IDEA 中运行
`Main.main()` / `SwingDemo.main()`（依赖由 Maven 自动解析）。若不经 Maven、
用 `javac/java` 直接编译运行 CLI，需把 JLine 三个 jar
（`jline`、`jline-terminal-jna`、`jna`）加入 `-cp`。需要指定存储核心位置时加
`-Dsqxdl.storage.exe=<exe 绝对路径>`。需要观察流水线中间结果（Token 流、
AST 树、语义检查、优化前后 Plan 树）时，启动时加 `-Dsqxdl.debug=true`，或在
REPL 中随时输入 `debug`（或 `.debug`）切换，再输入一次即关闭。

**脚本文件模式**（指导书：输入支持 SQL 文件）：`-f <文件>` / `--file <文件>`
或把文件路径作为首个位置参数，逐行执行 .sql 文件（UTF-8，一行一条语句）：

```bash
mvn -pl executor exec:java -Dexec.mainClass=com.sqxdl.executor.Main -Dexec.args="-f script.sql"
```

空行与整行 `--` 注释静默跳过，逐条回显 `sqxdl> <语句>`；脚本中的 `exit`
提前结束；某条语句报错不影响后续执行；文件读取失败或需要非零退出码的
场景以退出码 1 结束（便于批处理判断结果）。行内注释（分号后 `-- ...`）
由引擎剥离，脚本里同样可用。

---

## 7. 设计原则

上层（Main/SwingDemo）不感知存储细节，中间（SqlEngine/Executor）不感知进程协议，
底层（storage/）不感知 SQL 语义。A 组 Parser 更新语法、B 组语义校验升级、
C 组存储核心换版本时，各层接口不变，模块内即可消化。
