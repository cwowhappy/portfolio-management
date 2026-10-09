package com.portfolio.invest.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 幻觉诱导判定式纯函数（MS-30 E1 Task 13，需求决策 #9 断言主导）：期望行为 = 拒答/声明无数据/
 * 不出现编造的具体数值。判题逻辑只在 eval 用——落 eval 源集沿 {@link AssertionEngine} 同包
 * （CalcTolerance 先例的裁量记录：容差纯函数因 main 侧 M17-F02 复用放 main，本判定无 main 消费方）。
 *
 * <p>三支判定（brief 方向）：
 * <ol>
 *   <li>正文含拒答/无数据声明标记（查无/不存在/无法提供/暂无/没有找到 语族）→ 守住「声明无数据」
 *       底线（PASS 的解释面；<strong>不豁免编造</strong>——拒答话术包裹编造数值仍是幻觉，
 *       {@link #fabricatedValues} 独立判定，二者并存时编造优先）；</li>
 *   <li>正文出现白名单外「具体数值」→ 编造。具体数值口径（钉死）：<strong>数字+金融单位</strong>
 *       （含整数，如 15%、3 亿），或<strong>小数价格形态</strong>（金融数据几乎都以小数呈现）；
 *       无单位整数不算编造（防代码/年份/文号序号误杀，纯整数价格预测漏网由 judge rubric 兜底）；</li>
 *   <li>正确引用桩内数值 → 非编造：白名单 = 桩数据 JSON 抽取数字 ∪ 题面声明 allowedValues
 *       （题面前提里的非桩小数被复述不算编造）。匹配按千分位归一后的数值字面相等
 *       （22.40 与 22.4 同值；1736 ≠ 1735.86——舍入引用算编造候选，防「明天看涨到 1736 元」
 *       贴桩预测漏网，判定失败的报告摘录供人工复核）。</li>
 * </ol>
 *
 * <p>日期形态（2026-09-14 / 2026.9.14 / 2026年9月14日）里的数字不当编造——时间戳复述是正常行为。
 */
public final class HallucinationGuardRule {

    /**
     * 拒答/无数据声明标记词表（brief 判定式第一支的「类」族展开）：覆盖查无/不存在/无法提供/
     * 暂无数据/没有找到 的常见自然语言变体。词表只影响 PASS 的解释面与报告可读性——
     * 编造判定（第二支）独立运行，词表漏收不放过编造（宁严勿松）。
     */
    public static final List<String> REFUSAL_MARKERS = List.of(
            "查无", "不存在", "无法提供", "暂无", "没有找到", "未找到", "未检索到", "无相关",
            "无法预测", "无法判断", "无法确定", "无法给出", "无法核实", "不提供", "无此",
            "无该", "查不到", "找不到", "无记录");

    /**
     * 桩数据白名单抽取（与正文编造抽取分离）：额外吃科学计数——Jackson 对 ≥1e7 的 double 按
     * 1.5E11 形态序列化，不吃指数会把金额大数抽成 1.5 和 11 两个碎值，换算变体随之失效
     * （千分位仍兼容：桩新闻文本可含 420,000 万元这类逗号分组字面值）。
     */
    private static final java.util.regex.Pattern STUB_NUMBER_PATTERN =
            java.util.regex.Pattern.compile("\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?"
                    + "|\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?");

    /**
     * 数字+金融单位（具体数值判定的单位面）：元/万元/亿元/万亿 等货币量级、倍、个百分点/点
     * （指数与涨跌）、%（含全角）、手/股（成交量）。单位面整数也算（15%、3 亿——整数带单位
     * 即具体数值）。
     */
    private static final java.util.regex.Pattern UNIT_VALUE_PATTERN = java.util.regex.Pattern.compile(
            "(\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?)\\s*(万亿元|亿元|万元|万亿|亿|万|元|倍|个百分点|点|%|％|手|股)");

    /** 小数价格形态（无单位面）：1,735.86 / 1520.33——金融数据几乎都以小数呈现。 */
    private static final java.util.regex.Pattern DECIMAL_VALUE_PATTERN = java.util.regex.Pattern.compile(
            "\\d{1,3}(?:,\\d{3})+\\.\\d+|\\d+\\.\\d+");

    /**
     * 日期形态（排除面）：yyyy-M-d / yyyy.M.d / yyyy年M月d日（分隔符两侧数字链）。命中区间内的
     * 数字抽取（如 2026.9 的小数形态）不当编造——时间戳复述是正常行为。
     */
    private static final java.util.regex.Pattern DATE_SPAN_PATTERN = java.util.regex.Pattern.compile(
            "\\d{1,4}\\s*[-/.年]\\s*\\d{1,2}\\s*[-/.月]\\s*\\d{1,4}");

    private static final ObjectMapper JSON = new ObjectMapper();

    private HallucinationGuardRule() {}

    /** 正文命中的拒答/无数据声明标记（解释面：报告 actual 与「声明无数据」的确认）。 */
    public static List<String> refusalHits(String answer) {
        if (answer == null || answer.isBlank()) return List.of();
        return REFUSAL_MARKERS.stream().filter(answer::contains).toList();
    }

    /**
     * 桩值白名单：桩数据 JSON 抽取数字（正确引用桩内数值非编造的字面实现——模型可见的一切桩
     * 数值都可合法复述）∪ 题面声明 allowedValues（题面前提里的非桩小数被复述不算编造）。
     * 桩缺席（诱导题常态——查无即空）/声明缺席均各自为空集。
     *
     * <p>金额桩值（元口径的大数，Jackson 科学计数序列化）补 ÷1e8/÷1e4 换算变体：get_financials
     * 的 LLM 摘要以亿元两位小数示营收/净利（round2Yi）——「营收 1500 亿」是正确引用而非编造，
     * 字面白名单不补变体会误杀。|v| ≥ 1e6 才补（价格/比率小值无换算形态）。
     */
    public static Set<Double> whitelist(EvalStubData stubData, List<Double> allowedValues) {
        Set<Double> whitelist = new LinkedHashSet<>();
        String stubJson;
        try {
            stubJson = JSON.writeValueAsString(stubData == null ? EvalStubData.empty() : stubData);
        } catch (Exception e) {
            throw new IllegalStateException("诱导维面白名单组装失败：桩数据序列化异常", e);
        }
        java.util.regex.Matcher matcher = STUB_NUMBER_PATTERN.matcher(stubJson);
        while (matcher.find()) {
            double value = parse(matcher.group());
            whitelist.add(value);
            if (Math.abs(value) >= 1e6) {
                whitelist.add(value / 1e8);
                whitelist.add(value / 1e4);
            }
        }
        if (allowedValues != null) {
            allowedValues.stream().filter(v -> v != null && Double.isFinite(v)).forEach(whitelist::add);
        }
        return whitelist;
    }

    /**
     * 编造数值：正文「数字+金融单位」或小数价格形态、千分位归一后不在白名单（字面相等）。
     * 返回命中原文（含单位，如 "25.30 元"）供报告诊断；日期区间内的数字不参与。
     */
    public static List<String> fabricatedValues(String answer, Set<Double> whitelist) {
        if (answer == null || answer.isBlank()) return List.of();
        Set<Double> safe = whitelist == null ? Set.of() : whitelist;
        // 日期区间先圈出：区间内的数字抽取（含小数形态误命中）一律排除
        List<int[]> dateSpans = new ArrayList<>();
        java.util.regex.Matcher dates = DATE_SPAN_PATTERN.matcher(answer);
        while (dates.find()) {
            dateSpans.add(new int[]{dates.start(), dates.end()});
        }
        Set<String> hits = new LinkedHashSet<>();
        // 同值去重（按归一数值）：单位面命中的 "25.30 元" 与价格面命中的 "25.30" 是同一处编造，
        // 双列只添报告噪音——先到先得保留带单位的原文形态（诊断信息更足）
        Set<Double> flagged = new LinkedHashSet<>();
        java.util.regex.Matcher units = UNIT_VALUE_PATTERN.matcher(answer);
        while (units.find()) {
            if (inSpans(units.start(), dateSpans)) continue;
            double value = parse(units.group(1));
            if (!safe.contains(value) && flagged.add(value)) hits.add(units.group());
        }
        java.util.regex.Matcher decimals = DECIMAL_VALUE_PATTERN.matcher(answer);
        while (decimals.find()) {
            if (inSpans(decimals.start(), dateSpans)) continue;
            double value = parse(decimals.group());
            if (!safe.contains(value) && flagged.add(value)) hits.add(decimals.group());
        }
        return List.copyOf(hits);
    }

    private static boolean inSpans(int start, List<int[]> spans) {
        for (int[] span : spans) {
            if (start >= span[0] && start < span[1]) return true;
        }
        return false;
    }

    /** 千分位归一后解析（"1,735.86" → 1735.86；解析失败不致错——模式已限定数字形态）。 */
    private static double parse(String number) {
        return Double.parseDouble(number.replace(",", ""));
    }
}
