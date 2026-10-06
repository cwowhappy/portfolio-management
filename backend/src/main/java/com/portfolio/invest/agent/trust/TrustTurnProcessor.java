package com.portfolio.invest.agent.trust;

import com.portfolio.invest.config.InvestProperties;
import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 回合校验流水（MS-29 B5，设计规格 §4.2 步骤 1~4/9 的纯函数半边）：完整文本 + 真值池
 * → 数字提取 → 用户豁免（决策 #16：先配真值 verified/sourced → 无匹配且用户来源 → 豁免
 * 不锚定 → 其余 unverified）→ 三态锚定与确定性替换（{@link ConsistencyValidator}）→
 * 跨轮池摘要（§2.2）与 payload v1（§2.1）。
 *
 * <p>纯 POJO 零 agentscope 依赖（历史池以解析后的 entry 列表传入，metadata 读写归
 * {@link TrustAgentHook}）；护栏哲学：宁可少标不可断流——null 文本直通、异常由 hook 层兜底。
 * advice 半边已接入（B6，步骤 7），confidence 半边已接入（B7，步骤 8——陈旧度「今日」
 * 取注入时钟，生产装配 Asia/Shanghai，与 B3 装饰器 callTime 同时区）。
 */
public class TrustTurnProcessor {

    /** 池摘要上限（§2.2 metadata 膨胀护栏；超出保留最近条目）。 */
    static final int POOL_SUMMARY_LIMIT = 40;

    /**
     * 真值池显式排除清单（需求决策 #6 定稿，终审 Important）：回显类写工具——其返回是模型
     * 自产数据的回显，非独立真值。排除语义：该工具的 ToolInvocation <strong>完全不进校验池
     * （比对池与 MCP 精确配源池都不进）也不落池摘要</strong>——其数字在正文引用落 unverified
     * （诚实语义：无独立真值可证，不压制 unverified_ratio 信号）。在 {@link #validatorPool}
     * 建池处统一过滤（含历史 metadata 反解条目——升级前会话已落库的排除工具条目同样拦下）。
     */
    static final Set<String> POOL_EXCLUDED_TOOLS = Set.of("research_draft");

    private final ConsistencyValidator validator;
    private final AdviceDetector adviceDetector;
    private final ConfidenceScorer confidenceScorer;
    private final String disclaimerText;

    public TrustTurnProcessor(InvestProperties.Trust settings) {
        this(settings, Clock.system(ZoneId.of("Asia/Shanghai")));
    }

    /** 测试构造器：注入时钟（陈旧度「今日」可确定化，禁真实 sleep）。 */
    TrustTurnProcessor(InvestProperties.Trust settings, Clock clock) {
        this.validator = new ConsistencyValidator(settings);
        this.adviceDetector = new AdviceDetector(settings.getAdviceLexicon());
        this.confidenceScorer = new ConfidenceScorer(settings.getConfidence(), clock);
        this.disclaimerText = settings.getDisclaimerText();
    }

    /**
     * 跑一轮校验流水。
     *
     * @param finalText       末轮完整文本（null/空 → 直通空报告）
     * @param currentPool     本回合真值池（{@link TrustContext} 快照；resultText+emittedSpecs 提数建池）
     * @param historyPool     历史 Msg metadata 池摘要（§2.2 回溯；条目 {v, tool, asOf, kind}）
     * @param recentUserTexts 近期 user 消息文本（豁免判定）
     */
    public TrustTurnReport process(String finalText, List<ToolInvocation> currentPool,
            List<Map<String, Object>> historyPool, List<String> recentUserTexts) {
        if (finalText == null || finalText.isBlank()) {
            return new TrustTurnReport(finalText, finalText, List.of(),
                    new AnchorBatch.Stats(0, 0, 0), List.of(), List.of(), List.of(), 0, 0,
                    TrustTurnReport.Advice.NONE, List.of());
        }
        // 步骤 7 前半（B6）：自声明标记先行检测剥离——剥离后的文本才进入校验/改写
        // （注记行附加在干净文本上），词表兜底扫描亦在干净文本上（标记本身无词表词）
        AdviceDetector.Detection detection = adviceDetector.detect(finalText);
        List<ToolInvocation> pool = validatorPool(currentPool, historyPool);
        CorrectionResult correction = validator.correct(detection.cleanText(), pool);

        // 用户豁免（决策 #16）：仅对无真值匹配（unverified）的锚定生效——真值匹配优先
        List<NumberToken> userValues = userNumericValues(recentUserTexts);
        List<AnchorRecord> anchors = new ArrayList<>();
        int verified = 0;
        int sourced = 0;
        int unverified = 0;
        int exempted = 0;
        for (AnchorRecord anchor : correction.batch().anchors()) {
            if (anchor.state() == TrustVerdict.UNVERIFIED && isUserSourced(anchor, userValues)) {
                exempted++;
                continue;
            }
            switch (anchor.state()) {
                case VERIFIED -> verified++;
                case SOURCED -> sourced++;
                default -> unverified++;
            }
            anchors.add(anchor);
        }
        // 步骤 8（B7）：confidence 半边——四类机制信号（豁免过滤后的锚定与 stats 口径一致）
        List<ConfidenceSignal.Hit> confidence = confidenceScorer.score(
                anchors, new AnchorBatch.Stats(verified, sourced, unverified), currentPool,
                correction.corrections().size(), correction.correctionFailures());
        return new TrustTurnReport(
                finalText,
                correction.correctedText(),
                List.copyOf(anchors),
                new AnchorBatch.Stats(verified, sourced, unverified),
                correction.corrections(),
                correction.batch().correctionNotes(),
                poolSummary(currentPool),
                exempted,
                correction.correctionFailures(),
                adviceOf(detection),
                confidence);
    }

    /** advice 半边组装：命中时 disclaimer 文案（参数组③）随行——payload 缺键表达无建议。 */
    private TrustTurnReport.Advice adviceOf(AdviceDetector.Detection detection) {
        if (!detection.flag()) {
            return TrustTurnReport.Advice.NONE;
        }
        return new TrustTurnReport.Advice(true, detection.by(), disclaimerText);
    }

    // ———— 真值池 ————

    /**
     * 校验器池：本回合池（resultText + emittedSpecs 合并提数，emit 图表数字同为工具真值）
     * + 历史池摘要反解为合成调用（tool/asOf/kind/mcp 随行，B2 线名编码）。
     * 排除清单（决策 #6）在此统一过滤——回显类工具不产真值。
     */
    private static List<ToolInvocation> validatorPool(
            List<ToolInvocation> currentPool, List<Map<String, Object>> historyPool) {
        List<ToolInvocation> pool = new ArrayList<>();
        if (currentPool != null) {
            for (ToolInvocation invocation : currentPool) {
                if (invocation.emittedSpecs() == null || invocation.emittedSpecs().isEmpty()) {
                    pool.add(invocation);
                    continue;
                }
                StringBuilder text = new StringBuilder(invocation.resultText() == null
                        ? "" : invocation.resultText());
                for (String spec : invocation.emittedSpecs()) {
                    if (!text.isEmpty()) {
                        text.append('\n');
                    }
                    text.append(spec);
                }
                pool.add(new ToolInvocation(invocation.toolName(), invocation.args(), text.toString(),
                        List.of(), invocation.asOf(), invocation.asOfKind(), invocation.failed(),
                        invocation.mcp()));
            }
        }
        if (historyPool != null) {
            for (Map<String, Object> entry : historyPool) {
                ToolInvocation synthesized = fromHistoryEntry(entry);
                if (synthesized != null) {
                    pool.add(synthesized);
                }
            }
        }
        pool.removeIf(invocation -> POOL_EXCLUDED_TOOLS.contains(invocation.toolName()));
        return pool;
    }

    /**
     * 历史池条目 {v, tool, asOf, kind, mcp} → 合成 ToolInvocation（resultText 即原值片段，
     * truths() 自然提取）。mcp 读字面标志；缺键（升级前旧条目）回退 {@code kind==CALL}——
     * 与旧分桶行为严格等价（旧代码 CALL 即 MCP 精确配源池）。
     */
    private static ToolInvocation fromHistoryEntry(Map<String, Object> entry) {
        if (entry == null) {
            return null;
        }
        String value = str(entry.get("v"));
        String tool = str(entry.get("tool"));
        if (value == null || value.isBlank() || tool == null) {
            return null;
        }
        ToolInvocation.AsOfKind kind = ToolInvocation.AsOfKind.fromWireName(str(entry.get("kind")));
        if (kind == null) {
            kind = ToolInvocation.AsOfKind.CALL;
        }
        boolean mcp = entry.get("mcp") instanceof Boolean flag ? flag : kind == ToolInvocation.AsOfKind.CALL;
        return new ToolInvocation(tool, Map.of(), value, List.of(), str(entry.get("asOf")), kind, false, mcp);
    }

    /**
     * 池摘要（§2.2 本回合「值→(tool, asOf)」映射，落 Msg metadata）：resultText+emittedSpecs
     * 提数、failed 不入池、排除清单工具不入池（决策 #6：回显非真值）、同值去重（后写覆盖=
     * 最近一次调用）、上限 {@link #POOL_SUMMARY_LIMIT}（超出保留最近条目）。mcp 随行落键，
     * 回读分桶不依赖 kind 编码（缺键旧条目由 {@link #fromHistoryEntry} 回退兼容）。
     */
    private static List<Map<String, Object>> poolSummary(List<ToolInvocation> currentPool) {
        LinkedHashMap<String, Map<String, Object>> byValue = new LinkedHashMap<>();
        if (currentPool != null) {
            for (ToolInvocation invocation : currentPool) {
                if (invocation.failed() || POOL_EXCLUDED_TOOLS.contains(invocation.toolName())) {
                    continue;
                }
                for (NumberToken token : NumberExtractor.extract(numbersTextOf(invocation))) {
                    if (!token.dataLike()) {
                        continue;
                    }
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("v", token.value().stripTrailingZeros().toPlainString());
                    entry.put("tool", invocation.toolName());
                    entry.put("asOf", invocation.asOf() == null ? "" : invocation.asOf());
                    entry.put("kind", invocation.asOfKind() == null
                            ? ToolInvocation.AsOfKind.CALL.wireName() : invocation.asOfKind().wireName());
                    entry.put("mcp", invocation.mcp());
                    // 覆盖序（MS-29 后续②）：更新既有键须 remove+put 移到尾部——LinkedHashMap
                    // 重 put 保留原插入位，会破坏「保留最近条目」的 40-cap 逐出意图（早插入
                    // 但刚更新的值可能被逐）；移尾后截断语义 = 逐出<strong>最久未更新</strong>的键，
                    // 与「后写覆盖=最近一次调用」一致
                    String key = (String) entry.get("v");
                    byValue.remove(key);
                    byValue.put(key, entry);
                }
            }
        }
        List<Map<String, Object>> entries = new ArrayList<>(byValue.values());
        return entries.size() <= POOL_SUMMARY_LIMIT
                ? List.copyOf(entries)
                : List.copyOf(entries.subList(entries.size() - POOL_SUMMARY_LIMIT, entries.size()));
    }

    private static String numbersTextOf(ToolInvocation invocation) {
        StringBuilder sb = new StringBuilder(invocation.resultText() == null ? "" : invocation.resultText());
        if (invocation.emittedSpecs() != null) {
            for (String spec : invocation.emittedSpecs()) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(spec);
            }
        }
        return sb.toString();
    }

    // ———— 用户豁免 ————

    /** 近期 user 文本的数据性数字值集合（归一比较：值相等且百分比维度一致）。 */
    private static List<NumberToken> userNumericValues(List<String> recentUserTexts) {
        List<NumberToken> values = new ArrayList<>();
        if (recentUserTexts != null) {
            for (String text : recentUserTexts) {
                for (NumberToken token : NumberExtractor.extract(text)) {
                    if (token.dataLike()) {
                        values.add(token);
                    }
                }
            }
        }
        return values;
    }

    /**
     * 锚定是否用户来源：snippet 归一化的值与近期 user 数字同值同维度（豁免只作用于
     * unverified 锚定——未被替换，snippet 即原文片段，重提取取值）。
     */
    private static boolean isUserSourced(AnchorRecord anchor, List<NumberToken> userValues) {
        if (userValues.isEmpty()) {
            return false;
        }
        // anchor 的 snippet 即文本片段（未改写——豁免锚定必未被替换）；重提取取值
        for (NumberToken token : NumberExtractor.extract(anchor.snippet())) {
            if (!token.dataLike()) {
                continue;
            }
            for (NumberToken userToken : userValues) {
                if (userToken.percent() == token.percent()
                        && userToken.value().compareTo(token.value()) == 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
