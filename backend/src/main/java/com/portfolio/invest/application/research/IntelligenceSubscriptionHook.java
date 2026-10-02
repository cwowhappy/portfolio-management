package com.portfolio.invest.application.research;

import java.util.List;

/**
 * 持仓情报挂接端口（F11，D12/决策 #26）：research 域向情报域暴露「正在持仓跟踪的研究项目」
 * ——status=ACTIVE ∧ current_stage=POSITION ∧ intelligence_alert_enabled=true 的项目集合，
 * 供公告定向推送（{@code application.intelligence.AnnouncementPushService}）与订阅命中
 * 取并集。union 语义 = 订阅查询时 union，零写入（不改订阅表）；application 横向依赖合法
 * （PackageConventionsTest 白名单为整个 application..，先例 MarketSnapshotAssembler）。
 *
 * <p>实现 {@code infrastructure.persistence.ResearchIntelligenceSubscriptionHookImpl}
 * （照 {@link ResearchFalsifierNotifier} 端口模式：@Component 无条件注册、失败不抛按空集
 * 处理——挂接查询失败不拖垮推送主流程）。
 * 2026-09-29 随 M15 命名原则（全名 intelligence，禁用 intel 缩写）由 IntelSubscriptionHook 改名。
 */
public interface IntelligenceSubscriptionHook {

    /**
     * 当前持仓跟踪中的项目标的全集（userId 升序、同用户按 projectId 升序）。
     * 尽力而为：实现失败不抛、返回空集。
     */
    List<IntelligenceTarget> activePositionTargets();

    /**
     * 持仓挂接目标（D12 签名定稿；M15 命名原则用全名 intelligence）。
     *
     * @param userId    项目归属用户
     * @param projectId 研究项目 id（推送命中时 journal 留痕锚点，D14）
     * @param stockCode 标的代码（union 匹配键）
     * @param stockName 标的名称（展示用；空白由实现回退为 stockCode）
     */
    record IntelligenceTarget(Long userId, Long projectId, String stockCode, String stockName) {
    }
}
