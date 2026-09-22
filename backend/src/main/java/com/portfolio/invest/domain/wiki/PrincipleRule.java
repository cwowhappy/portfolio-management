package com.portfolio.invest.domain.wiki;

import java.math.BigDecimal;
import java.time.Instant;

/** 投资原则纪律规则聚合根：不可变，update 返回新实例（metric 不可改）。每用户每指标至多一条（DB UNIQUE 兜底）。 */
public final class PrincipleRule {

    private final Long id;
    private final Long userId;
    private final PrincipleMetric metric;
    private final BigDecimal threshold;
    private final boolean enabled;
    private final String description;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final Long version;

    private PrincipleRule(Long id, Long userId, PrincipleMetric metric, BigDecimal threshold, boolean enabled,
                          String description, Instant createdAt, Instant updatedAt, Long version) {
        this.id = id;
        this.userId = userId;
        this.metric = metric;
        this.threshold = threshold;
        this.enabled = enabled;
        this.description = description;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    public static PrincipleRule create(Long userId, PrincipleMetric metric, BigDecimal threshold,
                                       boolean enabled, String description, Instant now) {
        validate(metric, threshold, description);
        return new PrincipleRule(null, userId, metric, threshold, enabled, description, now, now, null);
    }

    public static PrincipleRule reconstitute(Long id, Long userId, PrincipleMetric metric, BigDecimal threshold,
                                             boolean enabled, String description,
                                             Instant createdAt, Instant updatedAt, Long version) {
        return new PrincipleRule(id, userId, metric, threshold, enabled, description, createdAt, updatedAt, version);
    }

    /** 更新可变字段（metric/userId 不可变——指标换设走删旧建新），返回新实例。 */
    public PrincipleRule update(BigDecimal threshold, boolean enabled, String description) {
        validate(metric, threshold, description);
        return new PrincipleRule(id, userId, metric, threshold, enabled, description,
                createdAt, Instant.now(), version);
    }

    private static void validate(PrincipleMetric metric, BigDecimal threshold, String description) {
        if (metric == null) {
            throw new WikiException(WikiErrorCode.INVALID_INPUT, "指标不能为空");
        }
        if (threshold == null) {
            throw new WikiException(WikiErrorCode.INVALID_INPUT, "阈值不能为空");
        }
        // 单位语义边界：ratio 类 (0,1]，倍数类 > 0（compareTo 兼容任意 scale）
        int cmpZero = threshold.compareTo(BigDecimal.ZERO);
        if (metric.isRatio()) {
            if (cmpZero <= 0 || threshold.compareTo(BigDecimal.ONE) > 0) {
                throw new WikiException(WikiErrorCode.INVALID_INPUT, "比例阈值必须在 (0,1] 区间（如 0.20 表示 20%）");
            }
        } else if (cmpZero <= 0) {
            throw new WikiException(WikiErrorCode.INVALID_INPUT, "阈值必须为正数");
        }
        if (description != null && description.length() > 500) {
            throw new WikiException(WikiErrorCode.INVALID_INPUT, "说明长度不能超过500字");
        }
    }

    public Long id() { return id; }
    public Long userId() { return userId; }
    public PrincipleMetric metric() { return metric; }
    public BigDecimal threshold() { return threshold; }
    public boolean enabled() { return enabled; }
    public String description() { return description; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public Long version() { return version; }
}
