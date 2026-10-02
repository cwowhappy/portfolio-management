package com.portfolio.invest.domain.intelligence;

import java.util.List;
import java.util.Optional;

/**
 * 情报订阅仓库端口（F16，决策 #18）：聚合根 {@link IntelligenceSubscription}
 * （intelligence_subscription 主表 + intelligence_subscription_stock 子表）。
 *
 * <p><b>缺省语义</b>：每用户至多一行，无行 = 默认（pushEnabled=true、空集）——
 * {@link #findByUserId} 无行返回 {@link Optional#empty()}，缺省实例由调用方以
 * {@link IntelligenceSubscription#defaults} 物化，读路径不落占位行。
 */
public interface SubscriptionRepository {

    /** 按用户取订阅聚合；无行返回 empty（子表标的一并装配）。 */
    Optional<IntelligenceSubscription> findByUserId(Long userId);

    /**
     * 主表 upsert + 子表差集同步（保留标的 added_at；改名走更新；移除的删行）。
     * 两表一致性的事务边界在 application 层（@Transactional 只允许出现在 application，
     * ArchUnit A2）。
     */
    IntelligenceSubscription save(IntelligenceSubscription subscription);

    /** 开启推送的全部用户 id（盘前简报群发收件人口径，P4 消费）。 */
    List<Long> findUserIdsWithPushEnabled();

    /** 持有该标的 ∧ push_enabled 的订阅聚合（公告定向触达反查，Task 7 消费；聚合带全量标的）。 */
    List<IntelligenceSubscription> findAllWithStock(String stockCode);
}
