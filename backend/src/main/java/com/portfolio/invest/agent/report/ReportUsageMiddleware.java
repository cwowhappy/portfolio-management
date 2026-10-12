package com.portfolio.invest.agent.report;

import com.portfolio.invest.agent.trust.ToolInvocation;
import com.portfolio.invest.agent.trust.TrustContext;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * 研报任务用量 + 真值采集中间件（hook-migration，自旧 Hook 等价迁移）：
 * per-task 实例（非 Spring bean），编排器每任务 new 一枚经 {@code HarnessAgentFactory
 * .buildReportAgent(userId, usageMiddleware)} 挂入，双职责——
 *
 * <p><strong>① token 累计：</strong>事件源自 Hook 的 POST_REASONING/POST_SUMMARY Msg usage
 * 改为 {@link ModelCallEndEvent#getUsage()}（单点 onAgent 层过滤；reasoning 与 summary 双路
 * 模型调用各发一枚，逐次累加口径不变——设计规格 §2.2 映射表）。配额卡点与计费观测读数口
 * （{@code totalTokens()}），usage 缺席安全跳过。
 *
 * <p><strong>② 真值池轮末快照：</strong>onAgent 完成时点（doOnComplete）读
 * {@code TrustContext.current(ctx).invocations()} 入并发队列（ctx 缺席防御性跳过——恢复旧
 * Hook null 守卫路径），编排器每轮 call 返回后
 * {@link #drainTruthInvocations()} 排空。时序等价性：onAgent 链包在 runLifecycle 外侧
 * （2.0.4 ReActAgent.buildAgentStream），doOnComplete 晚于核心内 Trust hook 的 POST_CALL
 * 与 state 落盘；ctx 即 ensureSessionDefaults 派生 rc（与工具装饰器同源，PR #110 实证）——
 * 旧「hook 的 runtimeContext 与工具记录同源」论证原样成立（MS-32 类注释②）。采集异常仅
 * WARN 降级（任务将在校验门如实红，不阻断 agent 调用）。
 *
 * <p><strong>线程安全：</strong>token 用 {@link LongAdder}、真值用
 * {@link ConcurrentLinkedQueue}（完成信号线程写、编排器线程排空，防御性并发安全——同旧 Hook）。
 */
public final class ReportUsageMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(ReportUsageMiddleware.class);

    private final LongAdder totalTokens = new LongAdder();
    private final ConcurrentLinkedQueue<ToolInvocation> truthInvocations = new ConcurrentLinkedQueue<>();

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        return next.apply(input)
                .doOnNext(this::accumulate)
                .doOnComplete(() -> collectTruth(ctx));
    }

    /** 整任务 token 累计读数（input+output 之和，token）——签名/语义沿旧 Hook。 */
    public long totalTokens() {
        return totalTokens.sum();
    }

    /**
     * 排空已采集真值（编排器每轮 call 返回后消费并入任务级池；排空语义防跨轮重复累计）
     * ——签名/语义沿旧 Hook。
     */
    public List<ToolInvocation> drainTruthInvocations() {
        List<ToolInvocation> drained = new ArrayList<>();
        ToolInvocation invocation;
        while ((invocation = truthInvocations.poll()) != null) {
            drained.add(invocation);
        }
        return drained;
    }

    private void accumulate(AgentEvent event) {
        if (event instanceof ModelCallEndEvent end && end.getUsage() != null) {
            totalTokens.add(end.getUsage().getInputTokens() + (long) end.getUsage().getOutputTokens());
        }
    }

    private void collectTruth(RuntimeContext ctx) {
        if (ctx == null) {
            return;
        }
        try {
            truthInvocations.addAll(TrustContext.current(ctx).invocations());
        } catch (Exception e) {
            log.warn("report truth collect: 回合完成真值快照异常（任务将在校验门如实红）: {}", e.getMessage());
        }
    }
}
