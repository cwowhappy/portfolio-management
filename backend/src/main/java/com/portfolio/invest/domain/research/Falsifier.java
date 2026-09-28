package com.portfolio.invest.domain.research;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 证伪条件实体（D10）：PREDICATE 谓词类（predicate + threshold，P3 FalsifierEvaluator
 * 自动求值）/ EVENT 事件类（note 文字说明 + event_checked 人工勾选）。
 * 不可变值对象，照 {@link StrategyDoc} 先例。
 */
public final class Falsifier {

    private final Long id;
    private final Long strategyId;
    private final FalsifierKind kind;
    private final FalsifierPredicate predicate;
    private final BigDecimal threshold;
    private final boolean eventChecked;
    private final String note;
    private final boolean enabled;
    private final Long version;
    private final Instant createdAt;
    private final Instant updatedAt;

    private Falsifier(Long id, Long strategyId, FalsifierKind kind, FalsifierPredicate predicate,
                      BigDecimal threshold, boolean eventChecked, String note, boolean enabled,
                      Long version, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.strategyId = strategyId;
        this.kind = kind;
        this.predicate = predicate;
        this.threshold = threshold;
        this.eventChecked = eventChecked;
        this.note = note;
        this.enabled = enabled;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** 谓词类：predicate 必填，threshold 必填非空非负（零合法），note 可空。 */
    public static Falsifier ofPredicate(Long strategyId, FalsifierPredicate predicate,
                                        BigDecimal threshold, String note) {
        if (strategyId == null) {
            throw new ResearchException(ResearchErrorCode.STRATEGY_REQUIRED, "归属策略文档不能为空");
        }
        if (predicate == null) {
            throw new ResearchException(ResearchErrorCode.PREDICATE_REQUIRED, "谓词类证伪条件必须指定谓词");
        }
        if (threshold == null || threshold.signum() < 0) {
            throw new ResearchException(ResearchErrorCode.THRESHOLD_INVALID, "谓词阈值必须为非负数值");
        }
        Instant now = Instant.now();
        return new Falsifier(null, strategyId, FalsifierKind.PREDICATE, predicate, threshold,
                false, note, true, null, now, now);
    }

    /**
     * 事件类：note 必填（事件靠文字说明，不设谓词与阈值）；eventChecked 为人工勾选置位——
     * 唯一写路径是 PUT 整替项携带（无独立 update 用例，照 T2/回填先例整替重建传导）。
     */
    public static Falsifier ofEvent(Long strategyId, String note, boolean eventChecked) {
        if (strategyId == null) {
            throw new ResearchException(ResearchErrorCode.STRATEGY_REQUIRED, "归属策略文档不能为空");
        }
        if (note == null || note.isBlank()) {
            throw new ResearchException(ResearchErrorCode.NOTE_REQUIRED, "事件类证伪条件必须填写文字说明");
        }
        Instant now = Instant.now();
        return new Falsifier(null, strategyId, FalsifierKind.EVENT, null, null,
                eventChecked, note, true, null, now, now);
    }

    /** 事件类（勾选缺省 false）：委托三参重载，兼容扫描/种子等不携带勾选的构造方。 */
    public static Falsifier ofEvent(Long strategyId, String note) {
        return ofEvent(strategyId, note, false);
    }

    /** 持久化还原（JPA 转换器用，不做校验）。 */
    public static Falsifier reconstitute(Long id, Long strategyId, FalsifierKind kind,
                                         FalsifierPredicate predicate, BigDecimal threshold,
                                         boolean eventChecked, String note, boolean enabled,
                                         Long version, Instant createdAt, Instant updatedAt) {
        return new Falsifier(id, strategyId, kind, predicate, threshold, eventChecked, note,
                enabled, version, createdAt, updatedAt);
    }

    public Long id() { return id; }
    public Long strategyId() { return strategyId; }
    public FalsifierKind kind() { return kind; }
    public FalsifierPredicate predicate() { return predicate; }
    public BigDecimal threshold() { return threshold; }
    public boolean eventChecked() { return eventChecked; }
    public String note() { return note; }
    public boolean enabled() { return enabled; }
    public Long version() { return version; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
}
