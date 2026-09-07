# SQXDL

数据库管理系统实训项目（MiniSQL），Java 实现，包含词法/语法分析、语义分析与执行计划、页式存储与执行器四个模块。

## 环境要求

- JDK 17
- Maven 3.6+

## 项目结构

```
src/main/java/com/sqxdl/core/
├── parser/     # 词法分析（Lexer/Token）、语法分析（Parser/ASTNode）
├── semantic/   # 语义分析（SemanticAnalyzer）、计划生成（PlanGenerator/PlanNode）、数据字典（CatalogImpl）
├── storage/    # 页式存储（Page）、缓冲池（BufferPool）、数据字典接口（Catalog）
└── executor/   # 执行器（Executor）、REPL 入口（Main）
```

## 常用命令

```bash
mvn compile    # 编译
mvn test       # 运行测试
mvn package    # 打包
```

## 分工

| 模块 | 负责人 | 内容 |
|---|---|---|
| parser | A | Lexer、Parser、Token、ASTNode |
| semantic | B | SemanticAnalyzer、PlanGenerator、CatalogImpl |
| storage | C | Page、BufferPool、Catalog |
| executor | D | Main、Executor |

各模块间通过 `Token`、`ASTNode`、`PlanNode`、`Catalog` 四个接口契约协作，修改契约需全组同步。
