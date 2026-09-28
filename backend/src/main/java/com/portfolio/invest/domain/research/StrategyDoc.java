package com.portfolio.invest.domain.research;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 策略文档（D13 两级状态机 DRAFT ⇄ FINALIZED，覆盖式修订不留版本链）：
 * 不可变，变更操作返回新实例（照 {@link ResearchProject} 先例）。
 * 定稿校验估值下限 &lt; 上限且均非空——DB CHECK ck_strategy_valuation 兜底，domain 先拦。
 */
public final class StrategyDoc {

    private final Long id;
    private final Long projectId;
    private final StrategyState state;
    private final String thesis;
    private final BigDecimal valuationLow;
    private final BigDecimal valuationHigh;
    private final String positionPlan;
    private final String buyConditions;
    private final String riskNotes;
    private final Instant finalizedAt;
    private final Long version;
    private final Instant createdAt;
    private final Instant updatedAt;

    private StrategyDoc(Long id, Long projectId, StrategyState state, String thesis,
                        BigDecimal valuationLow, BigDecimal valuationHigh, String positionPlan,
                        String buyConditions, String riskNotes, Instant finalizedAt,
                        Long version, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.projectId = projectId;
        this.state = state;
        this.thesis = thesis;
        this.valuationLow = valuationLow;
        this.valuationHigh = valuationHigh;
        this.positionPlan = positionPlan;
        this.buyConditions = buyConditions;
        this.riskNotes = riskNotes;
        this.finalizedAt = finalizedAt;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** 新建草稿（内容字段全空，随 saveDraft 逐步补全）。 */
    public static StrategyDoc draftOf(Long projectId) {
        if (projectId == null) {
            throw new ResearchException(ResearchErrorCode.PROJECT_REQUIRED, "归属项目不能为空");
        }
        Instant now = Instant.now();
        return new StrategyDoc(null, projectId, StrategyState.DRAFT, null, null, null,
                null, null, null, null, null, now, now);
    }

    /** 持久化还原（JPA 转换器用，不做校验）。 */
    public static StrategyDoc reconstitute(Long id, Long projectId, StrategyState state, String thesis,
                                           BigDecimal valuationLow, BigDecimal valuationHigh,
                                           String positionPlan, String buyConditions, String riskNotes,
                                           Instant finalizedAt, Long version,
                                           Instant createdAt, Instant updatedAt) {
        return new StrategyDoc(id, projectId, state, thesis, valuationLow, valuationHigh,
                positionPlan, buyConditions, riskNotes, finalizedAt, version, createdAt, updatedAt);
    }

    /** DRAFT 态暂存六字段（均可空，字段不完整可存）；整组覆盖，未提供字段不残留旧值。 */
    public StrategyDoc saveDraft(String thesis, BigDecimal valuationLow, BigDecimal valuationHigh,
                                 String positionPlan, String buyConditions, String riskNotes) {
        if (state == StrategyState.FINALIZED) {
            throw new ResearchException(ResearchErrorCode.STRATEGY_FINALIZED, "策略已定稿，须先修订回草稿再修改");
        }
        return new StrategyDoc(id, projectId, StrategyState.DRAFT, thesis, valuationLow, valuationHigh,
                positionPlan, buyConditions, riskNotes, null, version, createdAt, Instant.now());
    }

    /**
     * 定稿（D13）：校验估值下限 &lt; 上限且均非空，置 FINALIZED + finalizedAt=now；
     * 已定稿幂等返回原实例。
     *
     * <p>命名说明：不能叫 {@code finalize()}——与 {@link Object#finalize()} 返回类型
     * （void）冲突无法编译，故按「领域动作 + 实体」惯例取名 finalizeDoc。
     */
    public StrategyDoc finalizeDoc() {
        if (state == StrategyState.FINALIZED) {
            return this;
        }
        if (valuationLow == null || valuationHigh == null) {
            throw new ResearchException(ResearchErrorCode.VALUATION_RANGE_INVALID,
                    "定稿前估值区间上下限均不能为空");
        }
        if (valuationLow.compareTo(valuationHigh) >= 0) {
            throw new ResearchException(ResearchErrorCode.VALUATION_RANGE_INVALID,
                    "估值下限必须小于上限");
        }
        Instant now = Instant.now();
        return new StrategyDoc(id, projectId, StrategyState.FINALIZED, thesis, valuationLow, valuationHigh,
                positionPlan, buyConditions, riskNotes, now, version, createdAt, now);
    }

    /** 修订（D13 覆盖式）：FINALIZED→DRAFT、清 finalizedAt，内容字段保留待 saveDraft 覆盖；DRAFT 态幂等返回。 */
    public StrategyDoc revise() {
        if (state == StrategyState.DRAFT) {
            return this;
        }
        return new StrategyDoc(id, projectId, StrategyState.DRAFT, thesis, valuationLow, valuationHigh,
                positionPlan, buyConditions, riskNotes, null, version, createdAt, Instant.now());
    }

    public Long id() { return id; }
    public Long projectId() { return projectId; }
    public StrategyState state() { return state; }
    public String thesis() { return thesis; }
    public BigDecimal valuationLow() { return valuationLow; }
    public BigDecimal valuationHigh() { return valuationHigh; }
    public String positionPlan() { return positionPlan; }
    public String buyConditions() { return buyConditions; }
    public String riskNotes() { return riskNotes; }
    public Instant finalizedAt() { return finalizedAt; }
    public Long version() { return version; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
}
