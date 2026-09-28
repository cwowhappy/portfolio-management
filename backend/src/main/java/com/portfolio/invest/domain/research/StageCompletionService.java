package com.portfolio.invest.domain.research;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * 四阶段完成度唯一计算点（S6 / NFR-1）：纯函数，读时从「各阶段产物件数 + 手动覆盖」推导，
 * 不落库、前端不重复实现。优先级（D16 兜底）：
 * REOPENED &gt; 手动 COMPLETED &gt; AUTO（产物 ≥ {@link StageArtifactCatalog#required}）
 * &gt; IN_PROGRESS（当前阶段）&gt; NOT_STARTED。
 */
public final class StageCompletionService {

    private StageCompletionService() {}

    /** research_stage_record.manual_state 的手动覆盖值（S6：只存手动覆盖，NULL = 无覆盖）。 */
    public enum ManualState {
        NULL("未标记"),
        COMPLETED("手动完成"),
        REOPENED("已重开");

        private final String label;

        ManualState(String label) { this.label = label; }

        public String label() { return label; }
    }

    /**
     * 计算四阶段完成度。manualStates / artifactCounts 允许 null 或缺项（缺项视同无覆盖 / 0 件产物）。
     *
     * @return 以阶段声明序排列的全四阶段完成度（不可修改）
     */
    public static Map<ResearchStage, StageCompletion> compute(ResearchProject project,
                                                              Map<ResearchStage, ManualState> manualStates,
                                                              Map<ResearchStage, Integer> artifactCounts) {
        if (project == null) {
            throw new ResearchException(ResearchErrorCode.PROJECT_REQUIRED, "项目不能为空");
        }
        Map<ResearchStage, StageCompletion> result = new EnumMap<>(ResearchStage.class);
        for (ResearchStage stage : ResearchStage.values()) {
            result.put(stage, resolve(project, stage, manualStates, artifactCounts));
        }
        return Collections.unmodifiableMap(result);
    }

    private static StageCompletion resolve(ResearchProject project, ResearchStage stage,
                                           Map<ResearchStage, ManualState> manualStates,
                                           Map<ResearchStage, Integer> artifactCounts) {
        ManualState manual = manualStates == null ? null : manualStates.get(stage);
        if (manual == null) {
            manual = ManualState.NULL;
        }
        // 1. REOPENED 最高优先：显式重开覆盖一切完成判定，回到进行中（D4 复盘常触发重新分析）
        if (manual == ManualState.REOPENED) {
            return new StageCompletion(stage, StageStatus.IN_PROGRESS, CompletionBasis.PENDING);
        }
        // 2. 手动 COMPLETED 次之：无产物也算完成（D16 兜底）
        if (manual == ManualState.COMPLETED) {
            return new StageCompletion(stage, StageStatus.COMPLETED, CompletionBasis.MANUAL);
        }
        // 3. AUTO：必备产物齐套
        int count = artifactCounts == null || artifactCounts.get(stage) == null ? 0 : artifactCounts.get(stage);
        if (count >= StageArtifactCatalog.required(stage)) {
            return new StageCompletion(stage, StageStatus.COMPLETED, CompletionBasis.AUTO);
        }
        // 4. 当前阶段算进行中；5. 其余未开始
        if (project.currentStage() == stage) {
            return new StageCompletion(stage, StageStatus.IN_PROGRESS, CompletionBasis.PENDING);
        }
        return new StageCompletion(stage, StageStatus.NOT_STARTED, CompletionBasis.PENDING);
    }
}
