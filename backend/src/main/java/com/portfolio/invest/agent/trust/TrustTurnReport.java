package com.portfolio.invest.agent.trust;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 一次回合校验流水（{@link TrustTurnProcessor}）的产物（MS-29 B5，设计规格 §4.2）：
 * 修正后文本 + 豁免过滤后的锚定批次 + 池摘要（§2.2 metadata 落盘形态）与留痕计数
 * + advice 半边（B6：自声明标记 + 词表兜底井集）+ confidence 半边（B7：四类机制信号命中，
 * {@link ConfidenceScorer} 产出）。
 *
 * <p>{@link #toPayload()} 输出 payload v1（设计规格 §2.1）：无 null 用缺键——
 * unverified anchor 缺省 tool/args/asOf/asOfKind/raw；correction 键仅有注记时存在；
 * advice 键仅有命中时存在（B6）；confidence 键任一信号命中才携带（B7，缺键表达无低置信信号）。
 */
public record TrustTurnReport(
        String originalText,
        String correctedText,
        List<AnchorRecord> anchors,
        AnchorBatch.Stats stats,
        List<CorrectionResult.Correction> corrections,
        List<String> correctionNotes,
        List<Map<String, Object>> poolSummary,
        int exempted,
        int correctionFailures,
        Advice advice,
        List<ConfidenceSignal.Hit> confidence) {

    /**
     * advice 半边（B6，§2.1）：flag=任一层命中；by=self|lexicon|both；text=disclaimer
     * 文案（参数组③，仅 flag=true 时由 processor 随行——payload 缺 advice 键表达无建议）。
     */
    public record Advice(boolean flag, String by, String text) {
        /** 无建议命中的缺省实例（payload 以缺键表达，不产出 advice map）。 */
        public static final Advice NONE = new Advice(false, null, null);
    }

    /** 文本是否被改写（大偏差替换发生，或自声明标记行被剥离）。 */
    public boolean rewritten() {
        return !Objects.equals(originalText, correctedText);
    }

    /** payload v1（§2.1）：无 null——缺键表达缺省。 */
    public Map<String, Object> toPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("v", 1);
        payload.put("anchors", anchors.stream().map(TrustTurnReport::anchorToMap).toList());
        Map<String, Object> statsMap = new LinkedHashMap<>();
        statsMap.put("verified", stats.verified());
        statsMap.put("sourced", stats.sourced());
        statsMap.put("unverified", stats.unverified());
        payload.put("stats", statsMap);
        if (!correctionNotes.isEmpty()) {
            payload.put("correction", Map.of("notes", List.copyOf(correctionNotes)));
        }
        if (advice.flag()) {
            Map<String, Object> adviceMap = new LinkedHashMap<>();
            adviceMap.put("flag", true);
            adviceMap.put("by", advice.by());
            if (advice.text() != null && !advice.text().isBlank()) {
                adviceMap.put("text", advice.text());
            }
            payload.put("advice", adviceMap);
        }
        if (!confidence.isEmpty()) {
            payload.put("confidence",
                    Map.of("signals", confidence.stream().map(ConfidenceSignal.Hit::wire).toList()));
        }
        return payload;
    }

    private static Map<String, Object> anchorToMap(AnchorRecord anchor) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("snippet", anchor.snippet());
        map.put("occ", anchor.occ());
        map.put("state", anchor.state().wireName());
        if (anchor.tool() != null) {
            map.put("tool", anchor.tool());
        }
        if (anchor.args() != null) {
            map.put("args", anchor.args());
        }
        if (anchor.asOf() != null) {
            map.put("asOf", anchor.asOf());
        }
        if (anchor.asOfKind() != null) {
            map.put("asOfKind", anchor.asOfKind().wireName());
        }
        if (anchor.raw() != null) {
            map.put("raw", anchor.raw());
        }
        return map;
    }
}
