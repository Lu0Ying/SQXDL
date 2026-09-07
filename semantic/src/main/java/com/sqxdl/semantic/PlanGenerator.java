package com.sqxdl.semantic;

import com.sqxdl.parser.ASTNode;

/**
 * 执行计划生成器（B 组）。
 * 职责：将通过语义检查的 AST 转换为逻辑执行计划树（{@link PlanNode}）。
 */
public class PlanGenerator {

    /**
     * 根据 AST 生成逻辑执行计划。
     *
     * @param ast 语法树根节点
     * @return 计划树根节点
     */
    public PlanNode generate(ASTNode ast) {
        // TODO: 自底向上生成 Project -> Filter -> SeqScan 计划树：
        //       叶子为 SeqScanPlan（表名），有 WHERE 时包一层 FilterPlan（条件），
        //       最外层包 ProjectPlan（投影列）；无 WHERE 时省略 Filter 层
        return null;
    }
}
