package com.portfolio.invest.eval;

import java.util.List;
import java.util.Locale;

/**
 * token 预算护栏（MS-30 终审 I-1「接线」拍板项）：EvalRunner 每题完成后累计题级
 * {@code tokenUsage.totalTokens}（{@link AguiEventExtractor} 解析 SSE token_usage
 * cumulative 所得的末快照，即该题整跑累计——题间求和即当次运行总消耗），累计越过
 * {@code invest.eval.token-budget} 即中止剩余题（占位口径同 real 轨预留跳过：
 * SKIPPED + skipReason + 零轮次），runMeta 由 runner 标 completeness=PARTIAL +
 * tokenBudgetExceeded=true（收割侧据此落 PARTIAL 语义不任 baseline，并注理由行）。
 *
 * <p>护栏分层：120 分钟超时护栏（destroyForcibly）仍是第一道防线；本护栏补成本维度，
 * 单位是题级 totalTokens 求和（input+output 合计，与报告/看板口径一致）。
 */
public final class TokenBudgetGuard {

    /**
     * 兜底默认（上下文环境缺 {@code invest.eval.token-budget} 时）：7,500,000 =
     * 64 题全量真 LLM 实测 total 2,447,427（2026-10-10，input 2,374,121 + output
     * 73,306）的 ~3 倍，留题库扩展余量。与 application.yml 默认值同步维护。
     */
    public static final long DEFAULT_TOKEN_BUDGET = 7_500_000L;

    private final long budget;
    private long consumedTotalTokens;
    private boolean exceeded;

    /** 非正预算 fail-fast（防误配 0/负值把整跑静默全跳成 SKIPPED）。 */
    public TokenBudgetGuard(long budget) {
        if (budget <= 0) {
            throw new IllegalArgumentException("token 预算必须为正数: " + budget
                    + "（invest.eval.token-budget）");
        }
        this.budget = budget;
    }

    /**
     * 每题完成后累计（tokenUsage 缺席或无 totalTokens 的题——如框架异常 ERROR 题、
     * 无 token_usage 事件的退化流——安全跳过：无数字可累计，不计入也不致错）。
     */
    public void accumulate(QuestionOutcome outcome) {
        if (outcome.tokenUsage() == null || outcome.tokenUsage().totalTokens() == null) {
            return;
        }
        consumedTotalTokens += outcome.tokenUsage().totalTokens();
        if (consumedTotalTokens > budget) {
            exceeded = true;
        }
    }

    /** 是否已超限（此后剩余题不再起 LLM；「超过」取严格大于——恰好用尽不算超）。 */
    public boolean exceeded() {
        return exceeded;
    }

    /** 剩余题的中止占位结果（同 real 轨预留跳过口径：零轮次、无用户/threadId、无 judge）。 */
    public QuestionOutcome skippedOutcome(EvalQuestion question) {
        return new QuestionOutcome(question, QuestionOutcome.Status.SKIPPED, skipReason(),
                null, null, null, 0, List.of(), List.of(), null, null, null, null);
    }

    /** 人读中止理由（含累计/预算数字；报告 skipReason 与控制台共用一份）。 */
    public String skipReason() {
        return String.format(Locale.ROOT,
                "token 预算超限中止: 累计 %s > 预算 %s（invest.eval.token-budget），剩余题跳过",
                String.format(Locale.ROOT, "%,d", consumedTotalTokens),
                String.format(Locale.ROOT, "%,d", budget));
    }

    /** 已累计的题级 totalTokens 求和（诊断/测试读数）。 */
    public long consumedTotalTokens() {
        return consumedTotalTokens;
    }
}
