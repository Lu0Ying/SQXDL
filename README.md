# SQXDL

数据库管理系统实训项目（MiniSQL），Java 实现，包含词法/语法分析、语义分析与执行计划、页式存储与执行器四个模块。

## 环境要求

- JDK 17
- Maven 3.6+

## 项目结构

Maven 多模块项目，根工程为聚合 POM（`packaging=pom`），各模块独立 jar：

```
SQXDL/
├── pom.xml             # 父 POM：统一管理版本、依赖与插件
├── parser/             # 词法/语法分析：Token、ASTNode、Lexer、Parser
├── storage/            # 存储层：Page、BufferPool、Catalog 接口
├── semantic/           # 语义分析与计划：SemanticAnalyzer、PlanGenerator、CatalogImpl（依赖 parser、storage）
└── executor/           # 执行入口：Main、Executor（依赖 semantic）
```

模块依赖方向：`parser`、`storage` → `semantic` → `executor`。

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
| parser | A | Lexer、Parser、Token、ASTNode |
| semantic | B | SemanticAnalyzer、PlanGenerator、CatalogImpl |
| storage | C | Page、BufferPool、Catalog |
| executor | D | Main、Executor |

各模块间通过 `Token`、`ASTNode`、`PlanNode`、`Catalog` 四个接口契约协作，修改契约需全组同步。
