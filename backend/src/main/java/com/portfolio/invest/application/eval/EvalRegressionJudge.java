package com.portfolio.invest.application.eval;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 相对回归判定（MS-30 B4，设计规格 §3.1）：纯函数——本跑 {@link RunResult} vs baseline 跑
 * → {@link JudgeVerdict}。无 IO、无 Spring、不读 InvestProperties——三阈值构造器传入
 * （默认 10/3/15，接线 invest.eval.regression.* 归 Task 6）。
 *
 * <p>判定序：
 * <ol>
 *   <li>baseline 空 → {@code NO_BASELINE}（首跑/基准变更后不判相对回归）；</li>
 *   <li>可比性指纹（题库 hash + rubric 版本集全等）不一致 → 不可比：只判绝对完成率
 *       （&lt;80% 告警 {@code DEGRADED}，否则 {@code INCOMPARABLE}），reasons 标
 *       {@code BASELINE_INCOMPARABLE}——题库不同源时降幅/翻转/分类均无对照意义；</li>
 *   <li>可比：总通过率降幅 / PASS→FAIL 翻转数 / 三新类分类通过率降幅，任一 ≥ 阈值即
 *       {@code DEGRADED}（理由聚合，多命中逐条列出）；</li>
 *   <li>全未命中：上一跑 DEGRADED → {@code RECOVERED}（恢复跑，收割侧置
 *       baseline_candidate），否则 {@code NONE}。</li>
 * </ol>
 *
 * <p>实现要点：
 * <ul>
 *   <li>通过率分母 = pass + fail + error（ERROR 计入分母，需求决策 #10；收割侧传原始
 *       计数，本函数统一合并，判 done 后不因 error&gt;0 放宽）；</li>
 *   <li>降幅比较用长整型交叉相乘（100·(bp·ct − cp·bt) ≥ 阈·bt·ct）——double 直减在
 *       「恰降 10pp」处会得 0.0999…98 而漏报，边界含等于必须精确；</li>
 *   <li>翻转判定需双侧题目级明细，缺任一侧按 0 处理（保守不误报）；只对双侧同 id 且
 *       基线 PASS、本跑 FAIL 的题计数；</li>
 *   <li>分类判定只遍历三新类常量（E1~E3 题域）；分类键单侧缺失或无分母跳过；其余分类
 *       键留在 byCategory 里不参与判定。</li>
 * </ul>
 */
public class EvalRegressionJudge {

    /** 三新类（MS-30 E1~E3 题域）：分类判定只遍历这三键。 */
    public static final Set<String> NEW_CATEGORIES =
            Set.of("MARKET_FACT", "METRIC_CALC", "HALLUCINATION_INDUCTION");

    /** 不可比降级判定的绝对完成率告警线（§3.1 固定值 &lt;80% 告警，不可配）。 */
    private static final double ABSOLUTE_COMPLETENESS_ALERT = 0.80;

    /** 翻转理由行的题 id 列举上限（超出收敛为「等共 N 题」，防 verdict_reasons 膨胀）。 */
    private static final int FLIP_ID_SAMPLE_LIMIT = 10;

    private final int passRateDropPp;
    private final int flipThreshold;
    private final int categoryDropPp;

    /**
     * @param passRateDropPp 总通过率降幅告警阈值（百分点，含等于）
     * @param flipThreshold  PASS→FAIL 翻转数告警阈值（含等于，≥1）
     * @param categoryDropPp 三新类分类通过率降幅告警阈值（百分点，含等于）
     */
    public EvalRegressionJudge(int passRateDropPp, int flipThreshold, int categoryDropPp) {
        if (passRateDropPp < 0 || categoryDropPp < 0 || flipThreshold < 1) {
            throw new IllegalArgumentException("回归阈值非法：降幅阈值不可为负、翻转阈值不可小于 1");
        }
        this.passRateDropPp = passRateDropPp;
        this.flipThreshold = flipThreshold;
        this.categoryDropPp = categoryDropPp;
    }

    /** 判定入口：本跑结果 + baseline 跑（可空）→ 五态结论。 */
    public JudgeVerdict judge(RunResult current, Optional<RunResult> baseline) {
        if (baseline.isEmpty()) {
            return new JudgeVerdict(JudgeVerdict.Status.NO_BASELINE,
                    List.of("无 baseline（首跑或基准变更后），不判相对回归"));
        }
        RunResult base = baseline.get();
        List<String> reasons = new ArrayList<>();

        if (!comparableWith(current, base)) {
            reasons.add("BASELINE_INCOMPARABLE：题库 hash 或 rubric 版本集与 baseline 不一致，相对指标不可比");
            if (current.completeness() < ABSOLUTE_COMPLETENESS_ALERT) {
                reasons.add(String.format("绝对完成率 %.1f%% 低于 80%% 告警线",
                        current.completeness() * 100));
                return new JudgeVerdict(JudgeVerdict.Status.DEGRADED, reasons);
            }
            return new JudgeVerdict(JudgeVerdict.Status.INCOMPARABLE, reasons);
        }

        long baseDen = passRateDenominator(base);
        long curDen = passRateDenominator(current);
        if (dropAtLeast(base.totalPass(), baseDen, current.totalPass(), curDen, passRateDropPp)) {
            reasons.add(String.format("总通过率 %.2f%%→%.2f%%（降 %.2fpp ≥ 阈值 %dpp）",
                    percent(base.totalPass(), baseDen), percent(current.totalPass(), curDen),
                    percent(base.totalPass(), baseDen) - percent(current.totalPass(), curDen),
                    passRateDropPp));
        }

        List<String> flippedIds = flippedIds(base, current);
        if (flippedIds.size() >= flipThreshold) {
            reasons.add("PASS→FAIL 翻转 " + flippedIds.size() + " 题（≥ 阈值 " + flipThreshold
                    + "）：" + summarizeIds(flippedIds));
        }

        for (String category : NEW_CATEGORIES) {
            long[] baseFraction = passFraction(base, category);
            long[] curFraction = passFraction(current, category);
            if (baseFraction == null || curFraction == null) {
                continue; // 分类单侧缺失或无分母：跳过不误报
            }
            if (dropAtLeast(baseFraction[0], baseFraction[1], curFraction[0], curFraction[1],
                    categoryDropPp)) {
                reasons.add(String.format("分类 %s 通过率 %.2f%%→%.2f%%（降 %.2fpp ≥ 阈值 %dpp）",
                        category, percent(baseFraction[0], baseFraction[1]),
                        percent(curFraction[0], curFraction[1]),
                        percent(baseFraction[0], baseFraction[1]) - percent(curFraction[0], curFraction[1]),
                        categoryDropPp));
            }
        }

        if (!reasons.isEmpty()) {
            return new JudgeVerdict(JudgeVerdict.Status.DEGRADED, reasons);
        }
        if (current.prevRunDegraded()) {
            return new JudgeVerdict(JudgeVerdict.Status.RECOVERED,
                    List.of("全部阈值未命中，相对回归已恢复（baseline_candidate 待人工确认）"));
        }
        return new JudgeVerdict(JudgeVerdict.Status.NONE, List.of("全部阈值未命中，无回归"));
    }

    /** 可比性指纹（§4.2）：题库 hash 双侧齐备且相等 + rubric 版本集全等。 */
    private static boolean comparableWith(RunResult current, RunResult base) {
        return current.questionBankHash() != null
                && base.questionBankHash() != null
                && current.questionBankHash().equals(base.questionBankHash())
                && current.rubricVersions().equals(base.rubricVersions());
    }

    /** 通过率分母：pass + fail + error（ERROR 计入分母，需求决策 #10）。 */
    private static long passRateDenominator(RunResult run) {
        return (long) run.totalPass() + run.totalFail() + run.totalError();
    }

    /** 分类通过率分数 [pass, 分母]；分类缺失、明细形态非法或分母为 0 返回 null（跳过判定）。 */
    private static long[] passFraction(RunResult run, String category) {
        int[] counts = run.byCategory().get(category);
        if (counts == null || counts.length < 3) {
            return null;
        }
        long denominator = (long) counts[0] + counts[1] + counts[2];
        return denominator == 0 ? null : new long[]{counts[0], denominator};
    }

    /**
     * 降幅 ≥ 阈值（百分点）的精确比较：100·(bp/bt − cp/ct) ≥ th 等价变形为
     * 100·(bp·ct − cp·bt) ≥ th·bt·ct（分母恒正，长整型无舍入）。评测题量为万级，
     * 乘积远在 long 域内。
     */
    private static boolean dropAtLeast(long basePass, long baseDen,
                                       long curPass, long curDen, int thresholdPp) {
        if (baseDen <= 0 || curDen <= 0) {
            return false; // 空跑无分母，不判降幅
        }
        return 100 * (basePass * curDen - curPass * baseDen)
                >= (long) thresholdPp * baseDen * curDen;
    }

    /**
     * PASS→FAIL 翻转题 id 清单（同 id 对齐：基线 PASS、本跑 FAIL）。
     * 双侧明细缺任一侧返回空（无法对齐即不判，保守不误报）。
     */
    private static List<String> flippedIds(RunResult base, RunResult current) {
        if (base.outcomes().isEmpty() || current.outcomes().isEmpty()) {
            return List.of();
        }
        Map<String, Boolean> basePassById = new HashMap<>();
        for (RunResult.QuestionOutcomeLite outcome : base.outcomes()) {
            basePassById.putIfAbsent(outcome.id(), outcome.pass());
        }
        Set<String> seen = new HashSet<>();
        List<String> flips = new ArrayList<>();
        for (RunResult.QuestionOutcomeLite outcome : current.outcomes()) {
            if (!outcome.pass() && Boolean.TRUE.equals(basePassById.get(outcome.id()))
                    && seen.add(outcome.id())) {
                flips.add(outcome.id());
            }
        }
        Collections.sort(flips);
        return flips;
    }

    /** 翻转理由的题 id 列举：超出采样上限收敛为「……等共 N 题」。 */
    private static String summarizeIds(List<String> ids) {
        String shown = String.join("、", ids.subList(0, Math.min(ids.size(), FLIP_ID_SAMPLE_LIMIT)));
        return ids.size() > FLIP_ID_SAMPLE_LIMIT
                ? shown + " 等共 " + ids.size() + " 题"
                : shown;
    }

    /** 展示用百分比（仅入理由文案，判定不用它——判定走 {@link #dropAtLeast} 精确比较）。 */
    private static double percent(long pass, long denominator) {
        return pass * 100.0 / denominator;
    }
}
