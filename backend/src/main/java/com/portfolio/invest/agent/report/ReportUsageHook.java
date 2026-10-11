package com.portfolio.invest.agent.report;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.PostSummaryEvent;
import io.agentscope.core.message.Msg;
import java.util.concurrent.atomic.LongAdder;
import reactor.core.publisher.Mono;

/**
 * 研报任务用量钩子（MS-32 B8）：<strong>per-task 实例（非 Spring bean）</strong>，B9 编排器每任务
 * new 一枚经 {@code HarnessAgentFactory.buildReportAgent(userId, usageHook)} 挂入，跨轮累计整任务
 * token 消耗——配额卡点（{@code invest.report.quota.per-task-token-cap}）与计费观测的唯一读数口。
 *
 * <p><strong>事件源（B0 探针 3 实证，沿 {@code ObservabilityAgentHook.accumulate} 同构）：</strong>
 * usage 只在 POST_REASONING/POST_SUMMARY 事件携带（每次模型调用一个 Msg，
 * {@code getReasoningMessage()/getSummaryMessage().getUsage()}），POST_CALL 不累计。
 * 口径：input+output 原值求和（与库内 {@code ChatUsage.getTotalTokens()} 同和）；
 * usage 缺席（null）安全跳过——模型缺报不炸钩子，读数偏小由配额侧上限兜底。
 *
 * <p><strong>线程安全：</strong>{@link LongAdder}（编排器以轮次串行为主，防御性并发安全）。
 * Hook API 2.0.3 标记 forRemoval，仓库钉版 2.0.3——沿 {@code TrustAgentHook} 同款取舍。
 */
public final class ReportUsageHook implements Hook {

    private final LongAdder totalTokens = new LongAdder();

    @Override
    @SuppressWarnings("deprecation") // Hook 系 2.0.3 标记 forRemoval，钉版使用（TrustAgentHook 同款取舍）
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PostReasoningEvent reasoning) {
            accumulate(reasoning.getReasoningMessage());
        } else if (event instanceof PostSummaryEvent summary) {
            accumulate(summary.getSummaryMessage());
        }
        return Mono.just(event);
    }

    /** 整任务累计读数（input+output 之和，token）。 */
    public long totalTokens() {
        return totalTokens.sum();
    }

    private void accumulate(Msg msg) {
        if (msg == null || msg.getUsage() == null) {
            return;
        }
        totalTokens.add(msg.getUsage().getInputTokens() + msg.getUsage().getOutputTokens());
    }
}
