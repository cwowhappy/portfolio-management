package com.portfolio.invest.agent.trust;

import com.portfolio.invest.config.InvestProperties;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 低置信机制信号打分器（MS-29 B7，设计规格 §4.2 步骤 8 + 决策 #3 参数组⑤）：纯函数四类信号，
 * 任一命中即随 payload 携带（v1 无加权组合，简单可解释）。
 *
 * <ul>
 *   <li><strong>unverified_ratio</strong>：未懂数字比例 &gt; {unverified-ratio} 且未懂数
 *       ≥ {unverified-min}——口径分母 = 数据性锚定数（verified+sourced+unverified），
 *       用户豁免数字不入分子分母（B5 已排除出 stats）；</li>
 *   <li><strong>陈旧度</strong>（按数据类别，「今日」取注入时钟）：行情族（quote/kline/overview）
 *       超过 {stale-quote-days} 自然日 / 财报族（get_financials/analyze_financials 的 reportDate）
 *       超过 {stale-financial-days}（季报节奏+缓冲）/ 宏观族（macro_brief 的 period）超过
 *       {stale-macro-days}（月度节奏+缓冲）→ stale_quotes/stale_financials/stale_macro:N
 *       （N=该族陈旧锚定数）；asOfKind=GENERATED/CALL 的锚定不参与（无数据时点语义，决策 #17），
 *       未归族工具与不可解析 asOf 保守跳过（新增工具默认不参与）；</li>
 *   <li><strong>tool_failures</strong>：本回合真值池 failed=true 的调用数 ≥ 1（B3 装饰器计数）；</li>
 *   <li><strong>corrections</strong>：本回合校验修正次数 ≥ 1（B2 的 correctionFailures 已含在
 *       修正计数内），修正失败单列 correction_failed（决策 #12）。</li>
 * </ul>
 *
 * <p>纯 POJO 零 Spring/agentscope 依赖，时钟经构造注入（测试确定性，禁真实 sleep）；
 * asOf 解析对齐 B3 resolveAsOf 产出形态：{@code yyyy-MM-dd[ HH:mm[:ss]]}（源站/调用时刻）
 * 与宏观月度期别 {@code yyyy-MM}（按月初折算，MacroPoint 契约）。
 */
public class ConfidenceScorer {

    /** 工具名 → 数据类别（陈旧度归族常量映射；新增工具默认不参与——保守）。 */
    private static final Map<String, Family> TOOL_FAMILY = Map.of(
            "get_quote", Family.QUOTES,
            "get_kline", Family.QUOTES,
            "get_market_overview", Family.QUOTES,
            "get_financials", Family.FINANCIALS,
            "analyze_financials", Family.FINANCIALS,
            "macro_brief", Family.MACRO);

    private final InvestProperties.Trust.ConfidenceSettings settings;
    private final Clock clock;

    public ConfidenceScorer(InvestProperties.Trust.ConfidenceSettings settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    /**
     * 打一轮分：锚定集（B5 豁免过滤后）+ stats + 本回合真值池 + 修正计数 → 命中信号
     * （固定线序：ratio → 陈旧三类 → failures → corrections → correction_failed）。
     */
    public List<ConfidenceSignal.Hit> score(List<AnchorRecord> anchors, AnchorBatch.Stats stats,
            List<ToolInvocation> currentPool, int corrections, int correctionFailures) {
        List<ConfidenceSignal.Hit> hits = new ArrayList<>();
        unverifiedRatio(stats).ifPresent(hits::add);
        staleHits(anchors).forEach(hits::add);
        long failures = failedCount(currentPool);
        if (failures >= 1) {
            hits.add(new ConfidenceSignal.Hit(ConfidenceSignal.TOOL_FAILURES, failures));
        }
        if (corrections >= 1) {
            hits.add(new ConfidenceSignal.Hit(ConfidenceSignal.CORRECTIONS, corrections));
        }
        if (correctionFailures >= 1) {
            hits.add(new ConfidenceSignal.Hit(ConfidenceSignal.CORRECTION_FAILED, correctionFailures));
        }
        return List.copyOf(hits);
    }

    /** 未溯源比例：unverified/total 严格大于阈值且 unverified 达下限；分母为 0 不触发。 */
    private Optional<ConfidenceSignal.Hit> unverifiedRatio(AnchorBatch.Stats stats) {
        int total = stats.verified() + stats.sourced() + stats.unverified();
        if (total == 0 || stats.unverified() < settings.unverifiedMin()) {
            return Optional.empty();
        }
        double ratio = (double) stats.unverified() / total;
        return ratio > settings.unverifiedRatio()
                ? Optional.of(new ConfidenceSignal.Hit(ConfidenceSignal.UNVERIFIED_RATIO, ratio))
                : Optional.empty();
    }

    /** 陈旧度：DATA 锚定按工具归族，超阈值的族各计一信号（N=该族陈旧锚定数）。 */
    private List<ConfidenceSignal.Hit> staleHits(List<AnchorRecord> anchors) {
        int staleQuotes = 0;
        int staleFinancials = 0;
        int staleMacro = 0;
        LocalDate today = LocalDate.now(clock);
        if (anchors != null) {
            for (AnchorRecord anchor : anchors) {
                if (anchor.asOfKind() != ToolInvocation.AsOfKind.DATA || anchor.tool() == null) {
                    continue; // GENERATED/CALL 无数据时点语义；unverified 锚定无来源
                }
                Family family = TOOL_FAMILY.get(anchor.tool());
                if (family == null) {
                    continue; // 未归族工具不参与（保守）
                }
                LocalDate asOf = parseAsOf(anchor.asOf());
                if (asOf == null) {
                    continue; // 不可解析保守跳过
                }
                long age = ChronoUnit.DAYS.between(asOf, today);
                switch (family) {
                    case QUOTES -> {
                        if (age > settings.staleQuoteDays()) {
                            staleQuotes++;
                        }
                    }
                    case FINANCIALS -> {
                        if (age > settings.staleFinancialDays()) {
                            staleFinancials++;
                        }
                    }
                    case MACRO -> {
                        if (age > settings.staleMacroDays()) {
                            staleMacro++;
                        }
                    }
                }
            }
        }
        List<ConfidenceSignal.Hit> hits = new ArrayList<>();
        if (staleQuotes > 0) {
            hits.add(new ConfidenceSignal.Hit(ConfidenceSignal.STALE_QUOTES, staleQuotes));
        }
        if (staleFinancials > 0) {
            hits.add(new ConfidenceSignal.Hit(ConfidenceSignal.STALE_FINANCIALS, staleFinancials));
        }
        if (staleMacro > 0) {
            hits.add(new ConfidenceSignal.Hit(ConfidenceSignal.STALE_MACRO, staleMacro));
        }
        return hits;
    }

    /**
     * asOf 解析（best-effort，对齐 B3 resolveAsOf 产出）：≥10 字符取前 10 位
     * {@code yyyy-MM-dd}（兼容 "HH:mm[:ss]" 与 ISO 时间前缀）；不足 10 位按宏观月度期别
     * {@code yyyy-MM} 折算月初；不可解析返回 null（保守跳过）。
     */
    private static LocalDate parseAsOf(String asOf) {
        if (asOf == null || asOf.isBlank()) {
            return null;
        }
        String text = asOf.strip();
        try {
            if (text.length() >= 10) {
                return LocalDate.parse(text.substring(0, 10));
            }
            return YearMonth.parse(text).atDay(1);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static long failedCount(List<ToolInvocation> currentPool) {
        if (currentPool == null) {
            return 0;
        }
        return currentPool.stream().filter(ToolInvocation::failed).count();
    }

    /** 数据类别（陈旧度归族；未归族 = 不参与）。 */
    private enum Family { QUOTES, FINANCIALS, MACRO }
}
