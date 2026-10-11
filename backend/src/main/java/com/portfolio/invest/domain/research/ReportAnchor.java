package com.portfolio.invest.domain.research;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 研报锚点（MS-32，设计规格 §二 research_report_anchor 行）：数字片段的审计真相，
 * 创建后不可变；过期状态为打开时重查的瞬态计算（§3.8），不落库。
 *
 * <p>全基元/标准类型入参——domain 禁依赖 agent（A13 分层）；由 application 层（B11 sink）
 * 从 agent {@code AnchorRecord} 转录。state 沿 TrustVerdict.name() 三态
 * VERIFIED/SOURCED/UNVERIFIED；args 防御性拷贝为不可变 Map（null 安全，源 Map 含 null 值不炸）。
 */
public record ReportAnchor(Long id, Long reportId, String snippet, int occ, String state,
                           String toolName, Map<String, Object> args, Instant asOf, String asOfKind,
                           String raw) {

    /** 锚点真值三态（沿 agent TrustVerdict.name()，DB CHECK ck 兜底，domain 先拦）。 */
    private static final Set<String> STATES = Set.of("VERIFIED", "SOURCED", "UNVERIFIED");

    public static ReportAnchor of(Long reportId, String snippet, int occ, String state,
                                  String toolName, Map<String, Object> args,
                                  Instant asOf, String asOfKind, String raw) {
        if (reportId == null) {
            throw new ResearchException(ResearchErrorCode.REPORT_REQUIRED, "归属研报不能为空");
        }
        if (snippet == null || snippet.isBlank()) {
            throw new ResearchException(ResearchErrorCode.ANCHOR_INVALID, "锚点数字片段不能为空");
        }
        if (occ < 1) {
            throw new ResearchException(ResearchErrorCode.ANCHOR_INVALID, "锚点出现位次必须为 1 起始");
        }
        if (state == null || !STATES.contains(state)) {
            throw new ResearchException(ResearchErrorCode.ANCHOR_INVALID,
                    "锚点真值态须为 VERIFIED/SOURCED/UNVERIFIED 之一");
        }
        return new ReportAnchor(null, reportId, snippet, occ, state, toolName,
                args == null ? null : Collections.unmodifiableMap(new HashMap<>(args)),
                asOf, asOfKind, raw);
    }
}
