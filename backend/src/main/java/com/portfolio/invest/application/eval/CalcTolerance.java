package com.portfolio.invest.application.eval;

/**
 * 计算类断言相对容差纯函数（MS-30 E1，需求决策 #15）：指标计算类题的期望值断言带相对容差——
 * LLM 数字表述有四舍五入习惯（2.837% 写成 2.84%），零容差误杀。eval 源集 AssertionEngine 的
 * dataFidelityTolerance 维面按本题域容差调用；后续数字一致性校验器（阶段三规划 M17-F02 的
 * 「容差放行」档）如需同口径亦复用本类。纯函数不读配置——invest.eval.calc-tolerance-pct
 * （默认 2）由调用方解析后经参数下传，保持 main 侧零 Spring 依赖可直测。
 */
public final class CalcTolerance {

    private CalcTolerance() {}

    /**
     * 相对容差判定：{@code |actual-expected| ≤ |expected|×pct/100}，含边界（恰好 pct% 即过，
     * brief 四例钉死）。实现用「差值 ≤ 允差」形态而非「百分比 ≤ pct」——后者在边界值上撞
     * 浮点（0.02×100 = 2.000…4 假 FAIL）。expected=0 时相对容差无定义，退化为精确相等；
     * pct 为负按 0 处理（防御，只允许完全相等）。
     */
    public static boolean within(double expected, double actual, double pct) {
        double clamped = Math.max(pct, 0);
        double tolerance = Math.abs(expected) * clamped / 100.0;
        return Math.abs(actual - expected) <= tolerance;
    }
}
