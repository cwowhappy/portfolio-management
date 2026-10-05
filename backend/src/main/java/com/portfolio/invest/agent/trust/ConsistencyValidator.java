package com.portfolio.invest.agent.trust;

import com.portfolio.invest.config.InvestProperties;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;

/**
 * 数字一致性校验器（MS-29 B2，设计规格 §三/§4.2 步骤 3~4）：文本数字 token × 工具真值池
 * → 三态锚定（容差参数组①）+ 可归因大偏差的确定性替换（修正参数组⑥）。纯函数、零 Spring 注解
 * （容差/上限经 {@link InvestProperties.Trust} 注入，agent→config 白名单依赖）。
 *
 * <p><strong>三态判定（决策 #1/#5）：</strong>
 * <ul>
 *   <li><strong>verified</strong>：与内置工具真值满足 {@code |text − truth| ≤ max(relative×|truth|,
 *       量纲绝对容差)}。绝对容差选择：snippet 或命中真值原值含「元」用 price（价格语义），
 *       否则 absolute（无量纲：%、倍）。</li>
 *   <li><strong>sourced</strong>：有工具来源但未比对——MCP 真值（{@link ToolInvocation#mcp()}
 *       字面标志，终审 B3-① 迁移）值相等只配源（不进比对池，决策 #5）；或可归因偏差（见下）
 *       在 verify() 单独调用时的显式标注态。</li>
 *   <li><strong>unverified</strong>：无工具数据支撑（含失败调用——不产生可比真值）。</li>
 * </ul>
 *
 * <p><strong>可归因（配对）规则——B2 落地口径（设计规格留白，供 B5 复核）：</strong>
 * token 与真值可配对当且仅当百分比维度一致，且满足其一：
 * <ol>
 *   <li><strong>容差内</strong>：直接可归因（覆盖舍入/有效位/中文单位换算——万亿/百分比换算
 *       已在 {@link NumberExtractor} 归一，{@code 1.9万亿 vs 19000亿} 等值过）；</li>
 *   <li><strong>有效数字同源</strong>：双方前 {@code min(2, 较短有效数字位数)} 位有效数字相同
 *       （数量级错 {@code 15.20 vs 1520.33}、截尾 {@code 5 vs 58.2} 的共同形态），且量级跨度
 *       {@code |log10(|text|/|truth|)| ≤ 4}（护栏：阻断「2 vs 2.4万亿」类同首数字远跨度误配）。</li>
 * </ol>
 * 多真值命中取偏差最近者（同差取池序后者=最近一次调用，需求「取最近一次命中并留痕」）。
 * 容差外即可归因即拦截（决策 #1 中间带口径：容差外但 ≤ deviation 与 &gt; deviation 同处置，
 * {@code deviation} 参数当前不区分行为，保留为后续分级钩子）。
 *
 * <p><strong>确定性替换（决策 #10/#12）：</strong>错误 snippet 替换为工具原值的同单位回写
 * （snippet 带单位后缀→按该单位换算的真值数值；无单位→真值原值字符串），句尾追加注记行
 * 「&gt; ⚠ 校验修正：原文误述 X」；替换后重校验（注记行仅在收尾一次性插入，重校验始终在
 * 纯正文上——注记引用原误述值，不计入再校验），重试上限 {@code max-retries}，触达降级：
 * 原文保留 + 偏差锚定转显式标注（sourced + 修正失败注记，供 B5/B7 信号）。替换与邻近数字
 * 融合等病理形态由重校验自然消化，融合产物不可归因时收敛为 unverified（宁可少标）。
 * 调用前提：入参原文不含既有注记行（B5 对每条 assistant 消息单次调用）。
 */
public final class ConsistencyValidator {

    /** 注记行前缀（决策 #10 文案）。 */
    static final String NOTE_LINE_PREFIX = "> ⚠ 校验修正";

    private static final String SENTENCE_ENDS = "。！？；!?\n";

    /** 单位后缀（回写换算用；长后缀先行匹配：万亿元→万亿→亿元/万元→万/亿→单字，复合单位 controller 裁定 1）。 */
    private static final String[] UNIT_SUFFIXES =
            {"万亿元", "万亿", "亿元", "万元", "万", "亿", "%", "％", "元", "手", "点", "股", "倍"};

    /** 量级跨度护栏（数量级）：阻断同首有效数字的远跨度误配（如 2 vs 2.4万亿）。 */
    private static final double MAGNITUDE_SPAN_LIMIT = 4.0;

    private final InvestProperties.Trust.ToleranceSettings tolerance;
    private final int maxRetries;

    public ConsistencyValidator(InvestProperties.Trust settings) {
        this.tolerance = settings.getTolerance();
        this.maxRetries = settings.getCorrection().maxRetries();
    }

    /** 三态判定：tokens × 真值池 → 锚定批次（不改写文本；豁免与统计只消费 dataLike token）。 */
    public AnchorBatch verify(List<NumberToken> tokens, List<ToolInvocation> pool) {
        List<Truth> comparable = comparableTruths(pool);
        List<Truth> mcp = mcpTruths(pool);
        int verified = 0;
        int sourced = 0;
        int unverified = 0;
        List<AnchorRecord> anchors = new ArrayList<>();
        if (tokens != null) {
            for (NumberToken token : tokens) {
                if (!token.dataLike()) {
                    continue;
                }
                Eval eval = judge(token, comparable, mcp);
                switch (eval.verdict()) {
                    case VERIFIED -> verified++;
                    case SOURCED -> sourced++;
                    default -> unverified++;
                }
                anchors.add(toAnchor(token, eval));
            }
        }
        return new AnchorBatch(List.copyOf(anchors),
                new AnchorBatch.Stats(verified, sourced, unverified), List.of());
    }

    /**
     * 拦截改写：text × 真值池 → 修正后文本 + 最终锚定批次 + 修正明细。
     * 降级（重试耗尽）时 correctedText 为原文，偏差锚定 sourced + 修正失败注记。
     */
    public CorrectionResult correct(String text, List<ToolInvocation> pool) {
        if (text == null || text.isEmpty()) {
            return new CorrectionResult(text, verify(null, pool), List.of(), 0);
        }
        List<Truth> comparable = comparableTruths(pool);
        String body = text;
        List<CorrectionResult.Correction> rootCorrections = new ArrayList<>();
        List<String> rootNotes = new ArrayList<>();
        List<Offender> round0 = null;

        int attempts = 0;
        while (true) {
            List<Offender> offenders = findOffenders(body, comparable);
            if (offenders.isEmpty()) {
                break;
            }
            if (round0 == null) {
                round0 = List.copyOf(offenders);
                for (Offender offender : round0) {
                    rootCorrections.add(new CorrectionResult.Correction(offender.snippet(), offender.occ(),
                            offender.replacement(), "原文误述 " + offender.snippet(), false));
                    rootNotes.add("原文误述 " + offender.snippet());
                }
            }
            // 重试上限（1 轮初替 + maxRetries 轮重替）；触达降级：原文保留 + 显式标注
            if (attempts >= 1 + maxRetries) {
                String original = text;
                List<CorrectionResult.Correction> degraded = rootCorrections.stream()
                        .map(c -> new CorrectionResult.Correction(
                                c.snippet(), c.occ(), c.replacement(), c.note(), true))
                        .toList();
                List<String> failureNotes = rootNotes.stream()
                        .map(n -> "校验修正失败：" + n + "（重试上限已到，保留原文并转显式标注）")
                        .toList();
                AnchorBatch batch = withNotes(verify(NumberExtractor.extract(original), pool), failureNotes);
                return new CorrectionResult(original, batch, degraded, degraded.size());
            }
            body = applyReplacements(body, offenders);
            attempts++;
        }

        // 无偏差直通（原文无 dataLike 偏差）：corrections/notes 为空
        AnchorBatch batch = withNotes(verify(NumberExtractor.extract(body), pool), rootNotes);
        return new CorrectionResult(
                appendNotes(body, rootCorrections), batch, List.copyOf(rootCorrections), 0);
    }

    // ---------- 判定 ----------

    /** 真值池条目：从调用结果文本提取的数据性数字（raw 为结果原值片段）。 */
    private record Truth(String raw, BigDecimal value, boolean percent, ToolInvocation source) {}

    /** 单 token 判定结论：matched 为命中的真值（unverified 为 null）；interceptable = 可归因偏差。 */
    private record Eval(TrustVerdict verdict, Truth matched, boolean interceptable) {}

    private Eval judge(NumberToken token, List<Truth> comparable, List<Truth> mcp) {
        // 两级匹配：同单位后缀真值优先（价格配价格、手数配手数），无同单位候选再放宽到异单位
        Truth best = bestMatch(token, comparable, true);
        if (best == null) {
            best = bestMatch(token, comparable, false);
        }
        if (best == null) {
            for (Truth truth : mcp) {
                if (truth.percent() == token.percent()
                        && token.value().compareTo(truth.value()) == 0) {
                    return new Eval(TrustVerdict.SOURCED, truth, false);
                }
            }
            return new Eval(TrustVerdict.UNVERIFIED, null, false);
        }
        if (withinTolerance(token, best)) {
            return new Eval(TrustVerdict.VERIFIED, best, false);
        }
        return new Eval(TrustVerdict.SOURCED, best, true);
    }

    /** 可归因真值中的最优命中：相对偏差最近者（同差取池序后者=最近一次调用）；sameUnitOnly 只看同单位后缀。 */
    private Truth bestMatch(NumberToken token, List<Truth> comparable, boolean sameUnitOnly) {
        String tokenUnit = unitFamily(trailingUnit(token.snippet()));
        Truth best = null;
        BigDecimal bestDeviation = null;
        for (Truth truth : comparable) {
            if (truth.percent() != token.percent() || !attributable(token, truth)) {
                continue;
            }
            if (sameUnitOnly && !unitFamily(trailingUnit(truth.raw())).equals(tokenUnit)) {
                continue;
            }
            BigDecimal deviation = relativeDeviation(token.value(), truth.value());
            if (best == null || deviation.compareTo(bestDeviation) <= 0) {
                best = truth;
                bestDeviation = deviation;
            }
        }
        return best;
    }

    /** 相对偏差 |text−truth|/|truth|（truth=0 时退化为绝对差——量纲一致的兜底比较）。 */
    private static BigDecimal relativeDeviation(BigDecimal text, BigDecimal truth) {
        BigDecimal diff = text.subtract(truth).abs();
        return truth.signum() == 0 ? diff : diff.divide(truth.abs(), MathContext.DECIMAL64);
    }

    private static AnchorRecord toAnchor(NumberToken token, Eval eval) {
        Truth matched = eval.matched();
        if (matched == null) {
            return new AnchorRecord(token.snippet(), token.occ(), TrustVerdict.UNVERIFIED,
                    null, null, null, null, null);
        }
        ToolInvocation source = matched.source();
        return new AnchorRecord(token.snippet(), token.occ(), eval.verdict(),
                source.toolName(), source.args(), source.asOf(), source.asOfKind(), matched.raw());
    }

    /** 可归因：容差内，或有效数字同源且量级跨度内（百分比维度已在外层门控）。 */
    private boolean attributable(NumberToken token, Truth truth) {
        if (withinTolerance(token, truth)) {
            return true;
        }
        String tokenDigits = significantDigits(token.value());
        String truthDigits = significantDigits(truth.value());
        if (tokenDigits.isEmpty() || truthDigits.isEmpty()) {
            return false;
        }
        int k = Math.min(2, Math.min(tokenDigits.length(), truthDigits.length()));
        if (!tokenDigits.substring(0, k).equals(truthDigits.substring(0, k))) {
            return false;
        }
        double span = Math.log10(token.value().abs()
                .divide(truth.value().abs(), MathContext.DECIMAL64).doubleValue());
        return Math.abs(span) <= MAGNITUDE_SPAN_LIMIT;
    }

    /** 容差判定：|text − truth| ≤ max(relative×|truth|, 量纲绝对容差)（price：snippet/真值含「元」）。 */
    private boolean withinTolerance(NumberToken token, Truth truth) {
        BigDecimal diff = token.value().subtract(truth.value()).abs();
        BigDecimal relative = truth.value().abs().multiply(tolerance.relative());
        boolean price = token.snippet().contains("元") || truth.raw().contains("元");
        BigDecimal absolute = price ? tolerance.price() : tolerance.absolute();
        BigDecimal floor = relative.compareTo(absolute) >= 0 ? relative : absolute;
        return diff.compareTo(floor) <= 0;
    }

    /** 有效数字串：去符号去前后导零、剥尾零（1520.33→"152033"、2000→"2"、0→""）。 */
    private static String significantDigits(BigDecimal value) {
        if (value.signum() == 0) {
            return "";
        }
        return value.abs().stripTrailingZeros().unscaledValue().toString();
    }

    // ---------- 真值池 ----------

    private static List<Truth> comparableTruths(List<ToolInvocation> pool) {
        return truths(pool, false);
    }

    private static List<Truth> mcpTruths(List<ToolInvocation> pool) {
        return truths(pool, true);
    }

    /**
     * 从调用结果文本提取真值：failed 调用不产生可比真值；池分桶读 {@link ToolInvocation#mcp()}
     * 字面标志（终审 B3-① 行为等价迁移）——MCP 恒精确配源池（值相等只配源，决策 #5），
     * 内置无时点（CALL 兜底）真值进比对池（不再与 MCP 同桶）。
     */
    private static List<Truth> truths(List<ToolInvocation> pool, boolean mcp) {
        List<Truth> out = new ArrayList<>();
        if (pool == null) {
            return out;
        }
        for (ToolInvocation invocation : pool) {
            if (invocation.failed() || invocation.mcp() != mcp) {
                continue;
            }
            for (NumberToken token : NumberExtractor.extract(invocation.resultText())) {
                if (token.dataLike()) {
                    out.add(new Truth(token.snippet(), token.value(), token.percent(), invocation));
                }
            }
        }
        return out;
    }

    // ---------- 确定性替换 ----------

    /** 轮内待替换偏差：snippet+occ 定位（stripped 空间的合法出现位次）+ 回写串。 */
    private record Offender(String snippet, int occ, String replacement) {}

    private List<Offender> findOffenders(String body, List<Truth> comparable) {
        List<Offender> offenders = new ArrayList<>();
        List<Truth> unusedMcp = List.of();
        for (NumberToken token : NumberExtractor.extract(body)) {
            if (!token.dataLike()) {
                continue;
            }
            Eval eval = judge(token, comparable, unusedMcp);
            if (!eval.interceptable()) {
                continue;
            }
            String replacement = displayForm(token, eval.matched());
            if (occurrenceStart(body, token.snippet(), token.occ()) >= 0) {
                offenders.add(new Offender(token.snippet(), token.occ(), replacement));
            }
        }
        return offenders;
    }

    /** 回写串：snippet 带单位后缀→按 snippet 单位（含复合 亿元/万元/万亿元）换算回写；无单位→真值原值。 */
    private static String displayForm(NumberToken token, Truth truth) {
        return trailingUnit(token.snippet()).isEmpty() ? truth.raw() : writeBack(truth.value(), token.snippet());
    }

    /**
     * 同单位回写（包内可见供白盒测试）：真值按 snippet 自身单位（含复合单位）换算为数值并去尾零。
     * 复合单位乘数：万元=10^4、亿元=10^8、万亿元=10^12。
     */
    static String writeBack(BigDecimal truthValue, String snippet) {
        String unit = trailingUnit(snippet);
        return truthValue.divide(unitMultiplier(unit), MathContext.DECIMAL64)
                .stripTrailingZeros()
                .toPlainString() + unit;
    }

    /** 尾部单位后缀（万亿两字先行；无后缀返回空串）。 */
    private static String trailingUnit(String snippet) {
        for (String unit : UNIT_SUFFIXES) {
            if (snippet.length() > unit.length() && snippet.endsWith(unit)) {
                return unit;
            }
        }
        return "";
    }

    /** 单位族归一（全角％与半角% 同族），用于同量纲候选优先级比较。 */
    private static String unitFamily(String unit) {
        return "％".equals(unit) ? "%" : unit;
    }

    private static BigDecimal unitMultiplier(String unit) {
        return switch (unit) {
            case "万亿", "万亿元" -> new BigDecimal("1000000000000");
            case "亿", "亿元" -> new BigDecimal("100000000");
            case "万", "万元" -> new BigDecimal("10000");
            default -> BigDecimal.ONE;
        };
    }

    /** snippet 第 occ 次合法出现的起始位（合法=非符号前缀时前邻非数字/逗号/小数点，后邻非数字/续小数点）；未找到 −1。 */
    private static int occurrenceStart(String text, String snippet, int occ) {
        int index = 0;
        for (int seen = 0; seen < occ; ) {
            index = text.indexOf(snippet, index);
            if (index < 0) {
                return -1;
            }
            if (validBoundary(text, index, snippet.length(), isSignPrefixed(snippet))) {
                seen++;
                if (seen == occ) {
                    return index;
                }
            }
            index += 1;
        }
        return -1;
    }

    private static boolean isSignPrefixed(String snippet) {
        return !snippet.isEmpty() && (snippet.charAt(0) == '-' || snippet.charAt(0) == '+');
    }

    /** 合法边界：符号前缀（如「1500-2000元」的 -2000元）由符号切断与前数字的连续，跳过前邻检查。 */
    private static boolean validBoundary(String text, int start, int length, boolean signPrefixed) {
        char before = start > 0 ? text.charAt(start - 1) : '\0';
        if (!signPrefixed && (before == ',' || before == '.' || (before >= '0' && before <= '9'))) {
            return false;
        }
        int afterIndex = start + length;
        char after = afterIndex < text.length() ? text.charAt(afterIndex) : '\0';
        if (after >= '0' && after <= '9') {
            return false;
        }
        if (after == '.' && afterIndex + 1 < text.length()
                && Character.isDigit(text.charAt(afterIndex + 1))) {
            return false;
        }
        return true;
    }

    /** 一轮替换：全部偏差由右向左原位回写（保位序）。 */
    private static String applyReplacements(String body, List<Offender> offenders) {
        record Span(int start, int end, String replacement) {}
        List<Span> spans = new ArrayList<>();
        for (Offender offender : offenders) {
            int start = occurrenceStart(body, offender.snippet(), offender.occ());
            if (start >= 0) {
                spans.add(new Span(start, start + offender.snippet().length(), offender.replacement()));
            }
        }
        spans.sort((a, b) -> Integer.compare(b.start(), a.start()));
        StringBuilder sb = new StringBuilder(body);
        for (Span span : spans) {
            sb.replace(span.start(), span.end(), span.replacement());
        }
        return sb.toString();
    }

    /** 注记插入：每条注记行置于其替换值所在句的句尾标点后（无标点则文末），同句多条按修正顺序排列。 */
    private static String appendNotes(String body, List<CorrectionResult.Correction> corrections) {
        if (corrections.isEmpty()) {
            return body;
        }
        record Insertion(int offset, String line) {}
        List<Insertion> insertions = new ArrayList<>();
        int searchFrom = 0;
        for (CorrectionResult.Correction correction : corrections) {
            int at = findReplacement(body, correction.replacement(), searchFrom);
            if (at < 0) {
                at = findReplacement(body, correction.replacement(), 0);
            }
            if (at < 0) {
                at = body.length();   // 回写值定位失败兜底：注记置于文末
            } else {
                searchFrom = at + correction.replacement().length();
            }
            insertions.add(new Insertion(sentenceEndAfter(body, at + (at < body.length()
                    ? correction.replacement().length() : 0)), noteLine(correction.note())));
        }
        insertions.sort((a, b) -> Integer.compare(a.offset(), b.offset()));
        StringBuilder sb = new StringBuilder(body.length() + insertions.size() * 32);
        int cursor = 0;
        for (Insertion insertion : insertions) {
            sb.append(body, cursor, insertion.offset());
            sb.append('\n').append(insertion.line());
            if (insertion.offset() < body.length()) {
                sb.append('\n');   // 句中注记：注记行后换行再接后续正文
            }
            cursor = insertion.offset();
        }
        sb.append(body, cursor, body.length());
        return sb.toString();
    }

    /** 回写值定位：合法边界优先（替换值可能彼此包含，按序消费）。 */
    private static int findReplacement(String body, String replacement, int from) {
        int index = from;
        while (index >= 0) {
            index = body.indexOf(replacement, index);
            if (index < 0 || validBoundary(body, index, replacement.length(), isSignPrefixed(replacement))) {
                return index;
            }
            index += 1;
        }
        return -1;
    }

    /** 替换值所在句的句尾偏移（句尾标点后一位；无句尾标点=文末）。 */
    private static int sentenceEndAfter(String body, int from) {
        for (int i = from; i < body.length(); i++) {
            if (SENTENCE_ENDS.indexOf(body.charAt(i)) >= 0) {
                return i + 1;
            }
        }
        return body.length();
    }

    private static String noteLine(String note) {
        return NOTE_LINE_PREFIX + "：" + note;
    }

    private static AnchorBatch withNotes(AnchorBatch batch, List<String> notes) {
        if (notes.isEmpty()) {
            return batch;
        }
        return new AnchorBatch(batch.anchors(), batch.stats(), List.copyOf(notes));
    }
}
