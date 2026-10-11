package com.portfolio.invest.agent.report;

import com.portfolio.invest.agent.trust.ToolInvocation;
import com.portfolio.invest.agent.trust.TrustContext;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.PostSummaryEvent;
import io.agentscope.core.hook.RuntimeContextAware;
import io.agentscope.core.message.Msg;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 研报任务用量 + 真值采集钩子（MS-32 B8，Ruling-21 修复③扩展）：<strong>per-task 实例（非 Spring
 * bean）</strong>，B9 编排器每任务 new 一枚经 {@code HarnessAgentFactory.buildReportAgent(userId,
 * usageHook)} 挂入，双职责——
 *
 * <p><strong>① token 累计（B8 原职责）：</strong>跨轮累计整任务 token 消耗——配额卡点
 * （{@code invest.report.quota.per-task-token-cap}）与计费观测的唯一读数口。事件源（B0 探针 3
 * 实证，沿 {@code ObservabilityAgentHook.accumulate} 同构）：usage 只在 POST_REASONING/
 * POST_SUMMARY 事件携带（每次模型调用一个 Msg，{@code getReasoningMessage()/
 * getSummaryMessage().getUsage()}），POST_CALL 不累计。口径：input+output 原值求和（与库内
 * {@code ChatUsage.getTotalTokens()} 同和）；usage 缺席（null）安全跳过——模型缺报不炸钩子，
 * 读数偏小由配额侧上限兜底。</p>
 *
 * <p><strong>② 真值池轮末快照（Ruling-21 修复③主通道）：</strong>POST_CALL 时点读
 * {@code TrustContext.current(runtimeContext).invocations()} 累积进并发队列，编排器每轮 call
 * 返回后 {@link #drainTruthInvocations()} 排空并入任务级池。<strong>为什么必须走 hook 通道
 * （S1 真 LLM 实证 + HarnessAgent 2.0.3 字节码归因）：</strong>{@code HarnessAgent.call} 经
 * {@code ensureSessionDefaults} 对裸 rc（无 SandboxContext/workspace 挂载，编排器构造的 rc
 * 恒如此）<b>总是派生新 rc 实例</b>（{@code RuntimeContext.builder(rc)….build()}），工具装饰器
 * （{@code ToolCallParam.getRuntimeContext()}）与 hook 的 {@code setRuntimeContext} 都只见派生
 * rc——编排器在自有 rc 上 {@code TrustContext.current(rc)} 读到的恒是新挂空池（S1 taskId=14：
 * trace 实证 8 次工具调用 SUCCESS 而门判「真值池为空」）。hook 的 runtimeContext 与工具记录同源，
 * 沿 {@code ObservabilityAgentHook.mapTools}（MS-30 生产同款读法）时序一致；trust hook 的
 * POST_CALL 只清 ThreadLocal 兜底通道（{@code TrustContext.reset()}）不动 rc 池，读取时点安全。
 * 派生池在下一轮 PRE_CALL 被 {@code fresh} 覆盖，故每轮 POST_CALL 抢先快照、编排器排空防跨轮
 * 重复。采集异常仅 WARN 降级（任务将在门处「真值池为空」如实红，不阻断 agent 调用）。</p>
 *
 * <p><strong>线程安全：</strong>token 用 {@link LongAdder}、真值用 {@link ConcurrentLinkedQueue}
 * （POST_CALL 事件线程写、编排器线程排空；hook 事件在 reactor 线程到达，编排器以轮次串行为主，
 * 防御性并发安全）。Hook API 2.0.3 标记 forRemoval，仓库钉版 2.0.3——沿 {@code TrustAgentHook}
 * 同款取舍。</p>
 */
public final class ReportUsageHook implements Hook, RuntimeContextAware {

    private static final Logger log = LoggerFactory.getLogger(ReportUsageHook.class);

    private final LongAdder totalTokens = new LongAdder();
    private final ConcurrentLinkedQueue<ToolInvocation> truthInvocations = new ConcurrentLinkedQueue<>();
    private volatile RuntimeContext runtimeContext;

    @Override
    public void setRuntimeContext(RuntimeContext rc) {
        this.runtimeContext = rc;
    }

    @Override
    @SuppressWarnings("deprecation") // Hook 系 2.0.3 标记 forRemoval，钉版使用（TrustAgentHook 同款取舍）
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PostReasoningEvent reasoning) {
            accumulate(reasoning.getReasoningMessage());
        } else if (event instanceof PostSummaryEvent summary) {
            accumulate(summary.getSummaryMessage());
        } else if (event instanceof PostCallEvent) {
            collectTruth();
        }
        return Mono.just(event);
    }

    /** 整任务 token 累计读数（input+output 之和，token）。 */
    public long totalTokens() {
        return totalTokens.sum();
    }

    /** 真值池轮末快照（POST_CALL 时点，编排器每轮排空——见类注释②）。 */
    private void collectTruth() {
        RuntimeContext rc = runtimeContext;
        if (rc == null) {
            return;
        }
        try {
            truthInvocations.addAll(TrustContext.current(rc).invocations());
        } catch (Exception e) {
            log.warn("report truth collect: POST_CALL 真值快照异常（任务将在校验门如实红）: {}", e.getMessage());
        }
    }

    /**
     * 排空已采集真值（编排器每轮 call 返回后消费并入任务级池；排空语义防跨轮重复累计）。
     */
    public List<ToolInvocation> drainTruthInvocations() {
        List<ToolInvocation> drained = new ArrayList<>();
        ToolInvocation invocation;
        while ((invocation = truthInvocations.poll()) != null) {
            drained.add(invocation);
        }
        return drained;
    }

    private void accumulate(Msg msg) {
        if (msg == null || msg.getUsage() == null) {
            return;
        }
        totalTokens.add(msg.getUsage().getInputTokens() + msg.getUsage().getOutputTokens());
    }
}
