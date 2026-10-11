package com.portfolio.invest.application.research;

import com.portfolio.invest.domain.research.ReportAnchor;

/**
 * 研报锚点重查端口（MS-32 B12，设计规格 §3.8）：application 侧定义、中性类型
 * （禁引用 agent 类型，A13 分层）——沿 {@code ReportTaskSink} / {@code AgentPromptAssetPort}
 * 先例由 agent 侧实现（白名单直调 InvestTools/UserInvestTools 实例方法，绕过装饰器
 * 不污染真值池；NumberExtractor 与 B7 假设绑定口径同源），依赖方向保持 agent → application。
 */
public interface ReportAnchorRecheckPort {

    /**
     * 单锚点重查（只读，不回写锚点行）：白名单外工具 / args 缺参错型 / 工具失败信封 /
     * 服务异常 / 锚定片段无数据性数字收敛 {@link ReportStalenessService.AnchorState#UNVERIFIABLE}，
     * 绝不向上抛。
     */
    ReportStalenessService.AnchorStatus recheck(Long userId, ReportAnchor anchor);
}
