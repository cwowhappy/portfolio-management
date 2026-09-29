package com.portfolio.invest.domain.research;

import java.time.Instant;
import java.util.List;

/**
 * 纪律检查留痕（M16-F10/F12，NFR-2 append-only）：一次检查确认 = 一条不可变记录，
 * 无 version/updated_at、不提供任何 update 方法（repository 也不暴露更新用例）。
 * OVERRIDDEN 必填 override_reason（空白或超 500 字抛 {@code OVERRIDE_REASON_REQUIRED}，
 * D5 留痕最低要求）；CONFIRMED 时理由忽略置 null。照 {@link EntryPlan} 先例：
 * 静态工厂构造、reconstitute 持久化还原。
 */
public final class CheckRecord {

    /** DB research_check_record.override_reason 为 VARCHAR(500)。 */
    private static final int REASON_MAX_LENGTH = 500;

    private final Long id;
    private final Long projectId;
    private final CheckType checkType;
    private final List<CheckItemResult> items;
    private final CheckResult result;
    private final String overrideReason;
    private final Instant createdAt;

    private CheckRecord(Long id, Long projectId, CheckType checkType, List<CheckItemResult> items,
                        CheckResult result, String overrideReason, Instant createdAt) {
        this.id = id;
        this.projectId = projectId;
        this.checkType = checkType;
        this.items = items;
        this.result = result;
        this.overrideReason = overrideReason;
        this.createdAt = createdAt;
    }

    /** 新建留痕：检查项快照至少一条；OVERRIDDEN 必填理由，CONFIRMED 理由置 null。 */
    public static CheckRecord create(Long projectId, CheckType checkType, List<CheckItemResult> items,
                                     CheckResult result, String overrideReason) {
        if (projectId == null) {
            throw new ResearchException(ResearchErrorCode.PROJECT_REQUIRED, "归属项目不能为空");
        }
        if (checkType == null) {
            throw new ResearchException(ResearchErrorCode.CHECK_TYPE_REQUIRED, "检查类型不能为空");
        }
        if (items == null || items.isEmpty()) {
            throw new ResearchException(ResearchErrorCode.CHECK_ITEMS_REQUIRED, "检查项快照不能为空");
        }
        if (result == null) {
            throw new ResearchException(ResearchErrorCode.CHECK_RESULT_REQUIRED, "检查结论不能为空");
        }
        String reason = null;
        if (result == CheckResult.OVERRIDDEN) {
            if (overrideReason == null || overrideReason.isBlank()) {
                throw new ResearchException(ResearchErrorCode.OVERRIDE_REASON_REQUIRED, "越过命中项必须填写理由");
            }
            if (overrideReason.length() > REASON_MAX_LENGTH) {
                throw new ResearchException(ResearchErrorCode.OVERRIDE_REASON_REQUIRED, "理由长度不能超过500字");
            }
            reason = overrideReason;
        }
        return new CheckRecord(null, projectId, checkType, List.copyOf(items), result, reason, Instant.now());
    }

    /** 持久化还原（JPA 转换器用，不做校验）。 */
    public static CheckRecord reconstitute(Long id, Long projectId, CheckType checkType,
                                           List<CheckItemResult> items, CheckResult result,
                                           String overrideReason, Instant createdAt) {
        return new CheckRecord(id, projectId, checkType,
                items == null ? List.of() : List.copyOf(items), result, overrideReason, createdAt);
    }

    public Long id() { return id; }
    public Long projectId() { return projectId; }
    public CheckType checkType() { return checkType; }
    public List<CheckItemResult> items() { return items; }
    public CheckResult result() { return result; }
    public String overrideReason() { return overrideReason; }
    public Instant createdAt() { return createdAt; }
}
