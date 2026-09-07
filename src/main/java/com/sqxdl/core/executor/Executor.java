package com.sqxdl.core.executor;

import com.sqxdl.core.semantic.PlanNode;

/**
 * 执行器（D 组）。
 * 职责：遍历逻辑执行计划树，调用存储引擎接口（BufferPool/Catalog）
 *       完成实际的数据扫描、过滤与投影，并输出结果。
 */
public class Executor {

    /**
     * 执行给定的逻辑计划。
     *
     * @param plan 计划树根节点
     */
    public void execute(PlanNode plan) {
        // TODO: 调用存储引擎接口执行 Plan：
        //       1) SeqScanPlan：通过 BufferPool 逐页扫描表数据并产出元组；
        //       2) FilterPlan：递归执行 child，对每行按 condition 求值过滤；
        //       3) ProjectPlan：递归执行 child，仅保留 columns 指定列并打印结果
    }
}
