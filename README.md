# SQXDL

数据库管理系统实训项目（SQXDL），Java 实现，包含词法/语法分析、语义分析与执行计划、页式存储与执行器四个模块。

## 环境要求

- JDK 17+
- Maven 3.6+

## 项目结构

Maven 多模块项目，根工程为聚合 POM（`packaging=pom`），各模块独立 jar：

```
SQXDL/
├── pom.xml             # 父 POM：统一管理版本、依赖与插件
├── run-cli.bat         # CLI 一键启动脚本（Windows，带 JLine 完整体验）
├── 演示脚本.sql         # 答辩演示脚本（建表/增删查改/错误处理/扩展特性）
├── parser/             # 词法/语法分析：Token、ASTNode、Lexer、Parser
├── storage/            # 存储层：C++ 存储核心（storage_core.exe）与源码
├── semantic/           # 语义分析与计划：SemanticAnalyzer、PlanGenerator、CatalogImpl（依赖 parser）
└── executor/           # 执行入口：CLI（Main）、GUI（SwingDemo）、存储桥接（依赖 semantic、storage）
```

模块依赖方向：`parser`、`storage` → `semantic` → `executor`。

## 快速开始

```bash
mvn -pl executor -am package        # 编译 executor 及其依赖模块（自动下载 JLine 依赖）
```

| 入口 | 启动方式 | 说明 |
|---|---|---|
| GUI（推荐演示） | IDEA 运行 `SwingDemo.main()`，或 `mvn -pl executor exec:java -Dexec.mainClass=com.sqxdl.swing.SwingDemo` | 左侧表列表 + 右侧数据表格；Enter 执行、Shift+Enter 换行、↑↓ 翻历史 |
| CLI（真终端） | 项目根目录运行 `run-cli.bat` | JLine 完整体验：`sqxdl>` 提示符、↑↓ 历史、Ctrl+R 搜索 |
| CLI（IDEA Run） | IDEA 运行 `Main.main()` | 自动走 Scanner 管道路径（Run 窗口无 TTY） |
| 脚本模式 | `mvn -pl executor exec:java -Dexec.mainClass=com.sqxdl.executor.Main -Dexec.args="-f 演示脚本.sql"` | 整跑 .sql 文件，逐条回显，任一条报错不中断 |

- **数据持久化**：AUTO 模式下数据由存储核心持久化到 `C:\SQXDL\data\`（catalog.json + storage.db），正常退出（`exit` / 关窗）后数据跨进程保留；删除该目录即可重置数据库。
- **演示流程**：按 `演示脚本.sql` 中的分段顺序执行（建议 GUI 逐段粘贴），脚本头部注释含答辩讲解词提示与前置准备说明。
- **性能基准**：`java com.sqxdl.executor.PerfTest` 自动建 `perf_log` 大表（1000 行宽行）测量批量插入与各类查询的端到端耗时，详见 executor/README.md。

## SQL 支持

除指导书要求的建表 / 插入 / 条件查询 / 更新 / 删除 / SHOW 外，可选扩展已全部落地：

- `ORDER BY`（多键、ASC/DESC）、`GROUP BY` + `COUNT(*)`、`JOIN ... ON`（可链式多表）
- 拼写纠错提示（`SELEC` → `SELECT`）、注释（`--` 行注释与块注释）、`debug` 元命令查看流水线中间产物

完整语法与限制见 [executor/README.md](executor/README.md)。

## 常用命令

在项目根目录执行，会自动按依赖顺序构建所有模块：

```bash
mvn compile    # 编译全部模块
mvn test       # 运行全部模块的测试
mvn package    # 打包全部模块
mvn -pl executor -am package    # 只构建 executor 及其依赖模块
```

## 分工

| 模块 | 负责人 | 内容 |
|---|---|---|
| parser | A（苑博祥） | Lexer、Parser、Token、ASTNode |
| semantic | B（廖煜杰） | SemanticAnalyzer、PlanGenerator、CatalogImpl |
| storage | C（吴韶峰） | Page、BufferPool、Catalog、存储核心 |
| executor | D（叶子衿） | Main、Executor、SwingDemo、存储桥接 |

各模块间通过 `Token`、`ASTNode`、`PlanNode`、`Catalog` 四个接口契约协作，修改契约需全组同步。
