package com.sqxdl.semantic;

import com.sqxdl.parser.ASTNode;

/**
 * 语义分析器（B 组）。
 * 职责：遍历 AST，借助 {@link com.sqxdl.storage.Catalog} 校验语义合法性。
 */
public class SemanticAnalyzer {

    /**
     * 对 AST 做语义检查，发现错误时抛出带定位信息的运行时异常。
     *
     * @param ast 语法树根节点
     */
    public void analyze(ASTNode ast) {
        // TODO: 检查表/列存在性及类型：
        //       1) 遍历 SelectStmt，确认 tableName 在 Catalog 中存在；
        //       2) selectList 与 whereCond 中引用的列必须属于该表；
        //       3) BinaryExpr 两侧操作数类型兼容（如整数与字符串不能比较）
    }
}
