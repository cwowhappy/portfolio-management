package com.portfolio.invest.agent.trust;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数据性数字提取器（参数组②判定式，纯静态无状态——设计规格 §4.2 步骤 1，MS-29 B1）。
 *
 * <p><strong>判定式（用户拍板原文）：</strong>
 * <ul>
 *   <li><strong>提取（候选数字）</strong>：{@code [-+]?\d{1,3}(,\d{3})*(\.\d+)?} 加可选中文单位后缀
 *       （万 / 亿 / 万亿 / % / ％ / 倍 / 元 / 手 / 点 / 股）。实现取 {@code (,\d{3})+|\d+}
 *       的整数分支：原文形态对无千分位的 ≥4 位整数会截断（"1520.33"→"152"+"0.33"），
 *       与设计自身用例（B2「15.20 vs 1520.33 拦截」）冲突，故千分位分支要求至少一组、
 *       裸数字回退整段数字——原文形态（千分位/≤3 位/小数）全部保持匹配。</li>
 *   <li><strong>排除（非数据性数字）</strong>：纯年份（1900~2100 且无单位后缀、无小数点）；
 *       日期（数字前紧邻「月/日/号」或数字后紧跟「日/号/月/年」）；
 *       序号（「第」紧前缀）；A股代码形态（6 位纯数字且前后均非数字/小数点）。
 *       排除项 token 保留在结果中但 {@code dataLike=false}。</li>
 *   <li><strong>白名单语境（判定为数据性）</strong>：数字后紧跟金融量词（元/亿/万/万亿/%/％/倍/手/点/股）
 *       或前邻价格/估值动词（现价/最新价/涨/跌/涨幅/跌幅/收于/报/估值/市值/营收/净利/增速/回报）。
 *       白名单由排除项守卫条件结构性满足：年份排除要求「无单位后缀」（故"收于2026元"不被年份排除）、
 *       代码排除要求「纯 6 位数字」（故"600519股"不被代码排除），动词前邻不与任何排除条件共存——
 *       因此数据性判定归结为「未被排除」。无任何语境的裸数字（如复述用户成本"1800.5"）默认数据性，
 *       由决策 #16 的用户数字豁免在 hook 层（B5）跳过。</li>
 * </ul>
 *
 * <p>归一化：万/亿/万亿乘数乘入 BigDecimal 值；% / ％ 不除 100 仅置 {@code percent} 标记；
 * 千分位逗号剥离；occ 为同 snippet 在本消息中的第几次出现（1-based，含非数据性出现）。
 * null 入参返回空列表（护栏哲学：宁可少标不可断流）。
 */
public final class NumberExtractor {

    /** 候选数字 + 可选单位后缀；组 1=数字核（含符号/千分位/小数），组 2=单位后缀（万亿须先于万/亿尝试）。 */
    private static final Pattern CANDIDATE = Pattern.compile(
            "([-+]?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?)(万亿|万|亿|%|％|倍|元|手|点|股)?");

    /** 中文单位 → 十的幂乘数（其余后缀乘 1）。 */
    private static final Map<String, BigDecimal> UNIT_MULTIPLIERS = Map.of(
            "万亿", BigDecimal.TEN.pow(12),
            "亿", BigDecimal.TEN.pow(8),
            "万", BigDecimal.TEN.pow(4));

    private static final String PERCENT_SUFFIXES = "%％";

    /** 日期语境：数字前紧邻字符。 */
    private static final String DATE_HEAD_CHARS = "月日号";

    /** 日期语境：数字核后紧跟字符（单位后缀之前的相邻字符才算，"0.8%月环比" 的 % 隔开不算）。 */
    private static final String DATE_TAIL_CHARS = "日号月年";

    private NumberExtractor() {
    }

    /** 提取文本中的全部数字 token，按原文出现顺序返回。 */
    public static List<NumberToken> extract(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<NumberToken> tokens = new ArrayList<>();
        Map<String, Integer> occurrenceBySnippet = new HashMap<>();
        Matcher matcher = CANDIDATE.matcher(text);
        while (matcher.find()) {
            String core = matcher.group(1);
            String suffix = matcher.group(2);
            char before = matcher.start() > 0 ? text.charAt(matcher.start() - 1) : '\0';
            char afterCore = matcher.end(1) < text.length() ? text.charAt(matcher.end(1)) : '\0';
            String snippet = matcher.group();
            int occ = occurrenceBySnippet.merge(snippet, 1, Integer::sum);
            tokens.add(new NumberToken(
                    snippet,
                    normalize(core, suffix),
                    occ,
                    !isExcluded(core, suffix, before, afterCore),
                    suffix != null && PERCENT_SUFFIXES.indexOf(suffix.charAt(0)) >= 0));
        }
        return List.copyOf(tokens);
    }

    /** 归一化：剥符号与千分位逗号 → BigDecimal → 中文单位乘数；负号回填。 */
    private static BigDecimal normalize(String core, String suffix) {
        boolean negative = core.charAt(0) == '-';
        String digits = core.replace(",", "").replace("+", "").replace("-", "");
        // Map.of 不可变映射不接受 null 键探测，无后缀（乘 1）先行短路。
        BigDecimal multiplier = suffix == null
                ? BigDecimal.ONE
                : UNIT_MULTIPLIERS.getOrDefault(suffix, BigDecimal.ONE);
        BigDecimal value = new BigDecimal(digits).multiply(multiplier);
        return negative ? value.negate() : value;
    }

    /** 参数组②排除项：序号 / 日期 / 纯年份 / 6 位代码形态。 */
    private static boolean isExcluded(String core, String suffix, char before, char afterCore) {
        // 序号：「第」紧前缀。
        if (before == '第') {
            return true;
        }
        // 日期：前紧邻「月/日/号」，或数字核后紧跟「日/号/月/年」（单位后缀隔开的不算）。
        if (DATE_HEAD_CHARS.indexOf(before) >= 0 || DATE_TAIL_CHARS.indexOf(afterCore) >= 0) {
            return true;
        }
        // 纯年份：1900~2100 且无单位后缀、无小数点（"收于2026元"因单位在场不适用）。
        // core 已由正则约束为数字形态（剥千分位后必可解析），无需防御 NumberFormatException。
        if (suffix == null && core.indexOf('.') < 0) {
            BigDecimal value = new BigDecimal(core.replace(",", ""));
            if (value.signum() > 0
                    && value.compareTo(new BigDecimal("1900")) >= 0
                    && value.compareTo(new BigDecimal("2100")) <= 0) {
                return true;
            }
        }
        // A股代码形态：6 位纯数字（无符号/千分位/小数/单位）且前后均非数字/小数点。
        return suffix == null
                && core.length() == 6
                && core.chars().allMatch(c -> c >= '0' && c <= '9')
                && !isDigitOrDot(before)
                && !isDigitOrDot(afterCore);
    }

    private static boolean isDigitOrDot(char c) {
        return c != '\0' && (Character.isDigit(c) || c == '.');
    }
}
