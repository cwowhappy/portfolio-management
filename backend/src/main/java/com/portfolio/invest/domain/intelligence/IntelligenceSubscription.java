package com.portfolio.invest.domain.intelligence;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 情报订阅聚合根（F16，决策 #18，intelligence_subscription 主表 + 子表
 * intelligence_subscription_stock）：不可变，照 {@code ResearchProject} 先例——
 * 变更操作返回新实例（wither），更新走「构建新聚合整体替换保存」。
 *
 * <p><b>缺省语义</b>：每用户至多一行，<b>无行 = 默认</b>（pushEnabled=true、空集）——
 * 由 {@link #defaults} 表达且<b>不落库</b>（读路径永不 INSERT 占位行）。
 *
 * <p><b>聚合行为最小化</b>（YAGNI）：仅 togglePush / withStocks / withIndustries
 * 三个整体替换 wither，不做逐项 add/remove——PUT 全量替换语义由应用层组装新聚合后
 * {@code save} 差集同步（保留未动标的的 added_at）。
 */
public final class IntelligenceSubscription {

    private final Long userId;
    private final boolean pushEnabled;
    private final Set<String> industries;
    private final Set<SubscriptionStock> stocks;
    private final Instant updatedAt;

    private IntelligenceSubscription(Long userId, boolean pushEnabled,
                                     Set<String> industries, Set<SubscriptionStock> stocks,
                                     Instant updatedAt) {
        this.userId = userId;
        this.pushEnabled = pushEnabled;
        this.industries = industries;
        this.stocks = stocks;
        this.updatedAt = updatedAt;
    }

    /**
     * 缺省实例：无行语义（pushEnabled=true、空行业/空标的）。
     * 仅内存表达，读路径不落库。
     */
    public static IntelligenceSubscription defaults(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("归属用户不能为空");
        }
        return new IntelligenceSubscription(userId, true, new LinkedHashSet<>(),
                new LinkedHashSet<>(), null);
    }

    /** 仓库读路径重组：字段保真往返，集合做防御性拷贝。 */
    public static IntelligenceSubscription reconstitute(Long userId, boolean pushEnabled,
                                                        Collection<String> industries,
                                                        Collection<SubscriptionStock> stocks,
                                                        Instant updatedAt) {
        if (userId == null) {
            throw new IllegalArgumentException("归属用户不能为空");
        }
        return new IntelligenceSubscription(userId, pushEnabled,
                copyOf(industries), copyOfStocks(stocks), updatedAt);
    }

    /** 切换推送开关（打点时间取入参，A3 业务时间可注入）；同值幂等原样返回。 */
    public IntelligenceSubscription togglePush(boolean enabled, Instant now) {
        if (enabled == pushEnabled) {
            return this;
        }
        return new IntelligenceSubscription(userId, enabled, industries, stocks, now);
    }

    /** 整体替换行业码集合（null 归一为空集）；打点时间取入参。 */
    public IntelligenceSubscription withIndustries(Collection<String> newIndustries, Instant now) {
        return new IntelligenceSubscription(userId, pushEnabled,
                copyOf(newIndustries), stocks, now);
    }

    /** 整体替换标的集合（null 归一为空集）；同名代码去重保留首见；打点时间取入参。 */
    public IntelligenceSubscription withStocks(Collection<SubscriptionStock> newStocks, Instant now) {
        return new IntelligenceSubscription(userId, pushEnabled,
                industries, copyOfStocks(newStocks), now);
    }

    public Long userId() { return userId; }
    public boolean pushEnabled() { return pushEnabled; }
    public Set<String> industries() { return Collections.unmodifiableSet(industries); }
    public Set<SubscriptionStock> stocks() { return Collections.unmodifiableSet(stocks); }
    public Instant updatedAt() { return updatedAt; }

    private static Set<String> copyOf(Collection<String> values) {
        return values == null ? new LinkedHashSet<>() : new LinkedHashSet<>(values);
    }

    private static Set<SubscriptionStock> copyOfStocks(Collection<SubscriptionStock> values) {
        return values == null ? new LinkedHashSet<>() : new LinkedHashSet<>(values);
    }
}
