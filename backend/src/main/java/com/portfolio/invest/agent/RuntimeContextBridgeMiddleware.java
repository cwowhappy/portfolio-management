package com.portfolio.invest.agent;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * rc 桥接中间件（agentscope 2.0.4 升级）：2.0.4 删除 Hook 系接口
 * {@code RuntimeContextAware}（PR #3279，官方口径「context 访问点
 * 收敛到 middleware 与工具参数」），留守生产 Hook TrustAgentHook（hook-migration 后唯一；
 * 留守理由与重开触发条件见 features/hook-migration/02-设计规格/设计规格说明.md §五）
 * 的 rc 供给改由本中间件承担——onAgent 入口把派生 rc 送抵消费者
 * （{@code TrustAgentHook::setRuntimeContext} 方法引用），时序先于 runLifecycle 内的 PRE_CALL 钩子
 * （onAgent 链包在整个生命周期外侧，2.0.4 ReActAgent.buildAgentStream 源码实证），与 2.0.3
 * 框架侧 setRuntimeContext 时序契约一致。
 *
 * <p><strong>同源性（TrustContext 池键安全）：</strong>HarnessAgent 入口
 * {@code ensureSessionDefaults} 派生的 effective rc 即到达本中间件的 ctx；TrustContext 主通道
 * 是 rc typed 属性而非 rc 身份，工具侧 {@code buildMergedRuntimeContext} 的实例拷贝复制
 * extras、池引用随行——桥接对拷贝不敏感（S1 型「真值池恒空」不复发）。
 *
 * <p><strong>链序归因（2026-10-12 终审勘误）：</strong>2.0.4 MiddlewareChain 不做 order 排序
 * （javap 全扫 core+harness 无 order() 调用），链序=纯注册序（MiddlewareChain.build 自尾向头
 * 包裹，先注册=最外层）；本中间件以<strong>先注册</strong>保证最外层、先于一切
 * （含 TrustWireMessageIdMiddleware），{@code order()} 返回 MAX_VALUE 仅作语义声明。
 * null 容忍：ctx 可能缺席（裸调用路径），消费者（Hook setter）自防 null。
 * 2.0.3 上与框架侧回调同 rc 幂等共存（升级前行为中性）。
 */
public final class RuntimeContextBridgeMiddleware implements MiddlewareBase {

    private final List<Consumer<RuntimeContext>> consumers;

    @SafeVarargs
    public RuntimeContextBridgeMiddleware(Consumer<RuntimeContext>... consumers) {
        this(List.of(consumers));
    }

    public RuntimeContextBridgeMiddleware(List<Consumer<RuntimeContext>> consumers) {
        this.consumers = List.copyOf(consumers);
    }

    /** 装配断言口（HarnessAgentFactoryTest 消费者数与 hook 数同源锁定）。 */
    int consumerCount() {
        return consumers.size();
    }

    @Override
    public int order() {
        return Integer.MAX_VALUE;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        for (Consumer<RuntimeContext> consumer : consumers) {
            consumer.accept(ctx);
        }
        return next.apply(input);
    }
}
