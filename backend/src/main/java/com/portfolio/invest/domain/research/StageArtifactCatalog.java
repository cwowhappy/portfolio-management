package com.portfolio.invest.domain.research;

import java.util.Map;

/**
 * 阶段必备产物静态目录（D16：必备产物由 F01「必须有」清单映射，与完成度定义同源）。
 * v1 静态映射：NEW_ANALYSIS→1（立项即分析 checklist）、STRATEGY→1（策略定稿）、
 * POSITION→2（建仓计划 + 任一检查留痕）、REVIEW→1（复盘记录）。
 */
public final class StageArtifactCatalog {

    private StageArtifactCatalog() {}

    private static final Map<ResearchStage, Integer> REQUIRED = Map.of(
            ResearchStage.NEW_ANALYSIS, 1,
            ResearchStage.STRATEGY, 1,
            ResearchStage.POSITION, 2,
            ResearchStage.REVIEW, 1);

    /** 该阶段判定 AUTO 完成所需的产物件数。 */
    public static int required(ResearchStage stage) {
        if (stage == null) {
            throw new ResearchException(ResearchErrorCode.STAGE_REQUIRED, "阶段不能为空");
        }
        Integer required = REQUIRED.get(stage);
        if (required == null) {
            throw new ResearchException(ResearchErrorCode.STAGE_NOT_MAPPED, "阶段未配置必备产物数: " + stage);
        }
        return required;
    }
}
