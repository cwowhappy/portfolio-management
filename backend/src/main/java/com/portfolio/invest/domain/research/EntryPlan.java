package com.portfolio.invest.domain.research;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

/**
 * 建仓计划（M16-F09，D23）：凯利参数 v1 手动输入（winRate/payoffRatio 可空），系统只做算术不做估计；
 * 分批批次列表 Σratio ≤ 1 硬校验（D5 唯一阻断）。
 * 不可变，照 {@link StrategyDoc} 先例：静态工厂构造、reconstitute 持久化还原。
 */
public final class EntryPlan {

    /** kelly_ratio 列 NUMERIC(6,4)：除法显式 scale 4 + HALF_UP（仓库 BigDecimal 惯例）。 */
    private static final int KELLY_SCALE = 4;

    private final Long id;
    private final Long projectId;
    private final BigDecimal winRate;
    private final BigDecimal payoffRatio;
    private final List<EntryBatch> batches;
    private final Long version;
    private final Instant createdAt;
    private final Instant updatedAt;

    private EntryPlan(Long id, Long projectId, BigDecimal winRate, BigDecimal payoffRatio,
                      List<EntryBatch> batches, Long version, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.projectId = projectId;
        this.winRate = winRate;
        this.payoffRatio = payoffRatio;
        this.batches = batches;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** 新建计划：winRate/payoffRatio 可空（D23 手动可选）；batches 至少一批且 Σratio ≤ 1。 */
    public static EntryPlan of(Long projectId, BigDecimal winRate, BigDecimal payoffRatio,
                               List<EntryBatch> batches) {
        if (projectId == null) {
            throw new ResearchException(ResearchErrorCode.PROJECT_REQUIRED, "归属项目不能为空");
        }
        if (winRate != null && (winRate.signum() <= 0 || winRate.compareTo(BigDecimal.ONE) >= 0)) {
            throw new ResearchException(ResearchErrorCode.KELLY_PARAM_INVALID, "胜率须在 (0,1) 开区间");
        }
        if (payoffRatio != null && payoffRatio.signum() <= 0) {
            throw new ResearchException(ResearchErrorCode.KELLY_PARAM_INVALID, "赔率必须为正数");
        }
        if (batches == null || batches.isEmpty()) {
            throw new ResearchException(ResearchErrorCode.BATCH_REQUIRED, "建仓计划至少需要一批");
        }
        validateBatches(batches);
        Instant now = Instant.now();
        return new EntryPlan(null, projectId, winRate, payoffRatio, List.copyOf(batches), null, now, now);
    }

    /** 持久化还原（JPA 转换器用，不做校验）。 */
    public static EntryPlan reconstitute(Long id, Long projectId, BigDecimal winRate, BigDecimal payoffRatio,
                                         List<EntryBatch> batches, Long version,
                                         Instant createdAt, Instant updatedAt) {
        return new EntryPlan(id, projectId, winRate, payoffRatio,
                batches == null ? List.of() : List.copyOf(batches), version, createdAt, updatedAt);
    }

    /**
     * 简化凯利（D23）：f* = p − (1−p)/b（p=winRate、b=payoffRatio）。
     * 任一参数为空返回 null（不估）；不做范围裁剪——负值原样返回，无意义仓位由调用方判断。
     */
    public BigDecimal kellyRatio() {
        if (winRate == null || payoffRatio == null) {
            return null;
        }
        BigDecimal loss = BigDecimal.ONE.subtract(winRate);
        return winRate.subtract(loss.divide(payoffRatio, KELLY_SCALE, RoundingMode.HALF_UP));
    }

    /**
     * Σratio 硬校验（D5 唯一阻断）：合计 &gt; 1 抛 {@code RATIO_SUM_EXCEEDED}，恰为 1 通过
     * （compareTo 判定不看 scale）；空列表 Σ=0 视为通过，非空约束由 {@link #of} 承担。
     */
    public static void validateBatches(List<EntryBatch> batches) {
        BigDecimal sum = BigDecimal.ZERO;
        for (EntryBatch batch : batches) {
            sum = sum.add(batch.ratio());
        }
        if (sum.compareTo(BigDecimal.ONE) > 0) {
            throw new ResearchException(ResearchErrorCode.RATIO_SUM_EXCEEDED, "批次仓位占比合计不能超过 100%");
        }
    }

    public Long id() { return id; }
    public Long projectId() { return projectId; }
    public BigDecimal winRate() { return winRate; }
    public BigDecimal payoffRatio() { return payoffRatio; }
    public List<EntryBatch> batches() { return batches; }
    public Long version() { return version; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
}
