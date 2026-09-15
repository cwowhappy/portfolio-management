package com.portfolio.invest.eval;

import java.util.List;

/** 单题评估结果（报告装配入参）：题目 + 执行观察 + 断言维度 + judge 结论。 */
public record QuestionOutcome(
        EvalQuestion question,
        Status status,               // PASS / FAIL / SKIPPED / ERROR
        String skipReason,           // SKIPPED 时必填（如：real 轨题目在 stub 基线下跳过）
        String error,                // ERROR 时必填（异常摘要）
        String username,
        String threadId,
        long durationMs,
        List<AguiDriver.SseTurn> turns,
        List<AssertionEngine.DimensionResult> dimensions,
        DeepSeekJudge.Verdict judge,
        AguiEventExtractor.TokenUsage tokenUsage,
        String answerText) {

    public enum Status { PASS, FAIL, SKIPPED, ERROR }

    public int eventCount() {
        return turns.stream().mapToInt(t -> t.events().size()).sum();
    }
}
