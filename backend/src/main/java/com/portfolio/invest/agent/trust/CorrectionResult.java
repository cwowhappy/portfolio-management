package com.portfolio.invest.agent.trust;

import java.util.List;

/**
 * 确定性替换产物（{@link ConsistencyValidator#correct} 输出，MS-29 B2，设计规格 §4.2 步骤 4）。
 *
 * <p>correctedText 为修正后完整文本（含注记行「&gt; ⚠ 校验修正：原文误述 X」；降级时原文保留）；
 * batch 为最终锚定批次（对剥除注记行后的文本判定——注记引用原误述值，不参与再校验）；
 * corrections 只记录<strong>原文</strong>中的偏差引用（轮内中间态不记录，B5 据此发
 * {@code trust.correction} 事件）；correctionFailures 为重试耗尽降级的替换数
 * （B7 映射 {@link ConfidenceSignal#CORRECTIONS}，决策 #12 含修正失败）。
 */
public record CorrectionResult(
        String correctedText,
        AnchorBatch batch,
        List<Correction> corrections,
        int correctionFailures) {

    /**
     * 单次替换：snippet+occ 定位原文偏差；replacement 为回写串（降级时未应用、仅记录拟回写值）；
     * note 为注记正文（无前缀，payload v1 {@code correction.notes} 形态）；degraded = 重试耗尽降级。
     */
    public record Correction(String snippet, int occ, String replacement, String note, boolean degraded) {}
}
