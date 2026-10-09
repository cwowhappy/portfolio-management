package com.portfolio.invest.domain.observability;

import java.time.Instant;
import java.util.List;

/**
 * 观测写入端口（MS-30 §5.3）：回合结束由观测 hook（Task 9）一次调用批量落两表
 * （tool_invocation_obs / turn_observation，V5）——1 条轮观测 + N 条工具调用观测，
 * 不在单工具回调里逐条 INSERT。
 *
 * <p><strong>降级契约（设计规格 §5.3）：</strong>实现侧 try/catch 吞异常、仅 ERROR 日志——
 * 观测旁路自身失效不得阻断对话，调用方无须再防御。resultText 截断（UTF-8 字节安全，
 * invest.eval.observability.result-text-max-bytes）由实现统一执行。
 */
public interface ObservabilityRecorder {

    /**
     * 批量落一轮观测（1 轮 + N 工具调用）。
     *
     * @param turn  轮观测（时延/token/trust 摘要）
     * @param tools 该轮工具调用观测（空列表 = 仅落轮行）
     */
    void recordTurn(TurnObservation turn, List<ToolCallObservation> tools);

    /**
     * 滚动清理观测数据（Task 10）：直删两表中时间戳严格早于 cutoff 的行——
     * tool_invocation_obs 按 {@code called_at}、turn_observation 按 {@code created_at}。
     *
     * @param cutoff 保留截止线（严格小于才删，恰在 cutoff 的行保留——沿清理任务统一口径）
     * @return 两表删除行数合计（端口取粗粒度：调用方仅日志留痕，不分表细分）
     */
    int purgeBefore(Instant cutoff);
}
