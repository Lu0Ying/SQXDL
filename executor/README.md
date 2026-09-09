# executor 执行模块（D 组）

执行入口与执行器：提供 CLI 交互终端与 Swing 图形界面两种入口，把语义模块生成的计划交给存储核心（`storage_core.exe`）执行，并在存储核心不可用时回退内置示例数据，保证 Demo 脱离 C++ 程序也能完整演示。

## 文件结构

```
executor/
├── pom.xml                          # Maven 模块配置
└── src/main/java/com/sqxdl/
    ├── executor/
    │   ├── Main.java                # CLI 入口（REPL）
    │   ├── Executor.java            # 计划执行 + 结果输出
    │   ├── SqlEngine.java           # CLI/GUI 共用执行引擎
    │   └── storage/
    │       ├── StorageClient.java   # 存储核心进程调用
    │       ├── StorageResult.java   # 统一结果封装
    │       ├── PhysicalPlanJson.java# 计划 → JSON 序列化
    │       └── Json.java            # 手写 JSON 解析器
    └── swing/
        └── SwingDemo.java           # Swing 图形界面
```

## 整体数据流

```
Main (CLI) ──────────────┐
                         ├─→ SqlEngine ─→ Lexer/Parser → 语义分析 → 计划生成
SwingDemo (GUI) ─────────┘                     │
                                     ┌─────────┴─────────┐
                                     │ LOCAL: 内置数据模拟 │
                                     │ AUTO: StorageClient │
                                     └─────────┬─────────┘
                                        JSON → stdin → storage_core.exe
                                        stdout JSON → StorageResult
```

## 各文件说明

### 入口层

- **Main.java** — CLI 交互终端（REPL）。`sqxdl>` 提示符循环读入 SQL，`exit` 退出。健壮性设计：`hasNextLine()` 先探测输入流结束（Ctrl+Z/Ctrl+D）避免异常；每轮 SQL 包在 try-catch 中，出错只打印并继续。`Catalog`/`SemanticAnalyzer`/`PlanGenerator` 在循环外创建复用，建表元数据跨语句可见。
- **SwingDemo.java** — 图形界面。顶部连接/断开按钮，左侧表列表 + 右侧数据表格，底部 SQL 输入区 + 日志区。所有执行委托 `SqlEngine`（LOCAL 模式），回车直接执行（Shift+Enter 换行），执行前日志回显 `sqxdl> SQL`，与 CLI 体验一致。

### 执行层

- **SqlEngine.java** — CLI 与 GUI 共用的执行门面。完整流水线：SQL 文本 → Lexer/Parser（A 组）→ 语义分析（B 组）→ 计划生成 → 执行。两种模式：
  - `AUTO`：优先真实存储核心，失败（`STORAGE_UNAVAILABLE`/`STORAGE_TIMEOUT`）回退模拟；
  - `LOCAL`：始终用内置示例数据模拟（存储核心无持久化，数据无法跨语句保留，界面演示用它）。
  - 模拟执行支持 SELECT（投影 + WHERE 过滤）、INSERT、UPDATE、DELETE、CREATE TABLE；条件求值 `evalExpr` 递归处理二元表达式（AND/OR 短路、算术、数值/字符串自适应比较）。示例表按 `ColumnInfo` 带类型注册进 Catalog，让 `age > 20` 走真实类型校验。
- **Executor.java** — CLI 结果展示。按结果类型输出：数据集画成按列宽对齐的文本表格、行数或错误。只做展示，不感知存储协议。

### 存储桥接层（storage/）

- **StorageClient.java** — 启动 `storage_core.exe` 子进程：计划 JSON 走 stdin（Windows 命令行参数中双引号会被 CRT 剥离，跨语言传 JSON 必须走 stdin），结果从 stdout 读一行 JSON。10 秒超时强杀；exe 缺失、启动失败、超时、非法返回全部封装为带错误码的 ERROR 结果，调用方永不崩溃。解析时取 stdout 最后一个非空行，兼容核心在结果前输出状态行。exe 路径可用 `-Dsqxdl.storage.exe=<路径>` 覆盖。
- **PhysicalPlanJson.java** — 把计划树按存储核心约定格式转 JSON：查询计划为 `project → filter → scan` 三层嵌套，写操作为单对象；条件表达式递归写出（binary/column/literal），UPDATE/DELETE 无 WHERE 时省略 condition 字段。
- **Json.java** — 递归下降 JSON 解析器（对象 → LinkedHashMap、数字按小数点/指数区分 Long/Double）与字符串转义（`quote`）。手写实现避免第三方依赖，保证四人协作离线可构建。
- **StorageResult.java** — 统一结果封装，三种类型：`RESULTSET`（列名 + 行数据）、`ROWCOUNT`（受影响行数）、`ERROR`（错误码 + 信息）；提供静态工厂与 `parse()`（反序列化存储核心返回的 JSON）。

## 运行方式

在项目根目录：

```bash
mvn -pl executor -am package    # 编译 executor 及其依赖模块

# CLI 交互终端
java -cp "executor/target/classes;semantic/target/classes;parser/target/classes" com.sqxdl.executor.Main

# Swing 图形界面
java -cp "executor/target/classes;semantic/target/classes;parser/target/classes" com.sqxdl.swing.SwingDemo
```

Windows 分隔符为 `;`，Linux/macOS 为 `:`。

## 设计原则

上层（Main/SwingDemo）不感知存储细节，中间（SqlEngine/Executor）不感知进程协议，底层（storage/）不感知 SQL 语义。因此 A 组 Parser 从占位实现换成真实现时，入口代码无需改动；B 组语义校验升级时，执行层自动受益。
