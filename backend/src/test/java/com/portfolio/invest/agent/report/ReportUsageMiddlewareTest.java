package com.portfolio.invest.agent.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.agent.trust.ToolInvocation;
import com.portfolio.invest.agent.trust.TrustContext;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.middleware.AgentInput;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * ReportUsageMiddleware 单元测试（hook-migration，沿旧 Hook 单测口径迁移）：
 * usage 事件源改 ModelCallEndEvent（reasoning+summary 双路同事件）；真值快照在 onAgent
 * 完成时点（doOnComplete）——与旧 POST_CALL 时序等价。真值注池走反射
 * （TrustContext.fresh/record 包私有于 trust 包，ReportSteps:311 B0 Ruling-1 同款取舍）。
 */
class ReportUsageMiddlewareTest {

    @AfterEach
    void cleanFallback() {
        TrustContext.reset();
    }

    @Test
    @DisplayName("usage 累计：两次 ModelCallEndEvent (100,50)+(30,20) → totalTokens=200")
    void givenModelCallEndEvents_whenAgentCompletes_thenTokensAccumulated() {
        ReportUsageMiddleware mw = new ReportUsageMiddleware();
        RuntimeContext ctx = RuntimeContext.builder().userId("7").sessionId("report-1").build();

        mw.onAgent(null, ctx, new AgentInput(List.of()), input -> Flux.just(
                (AgentEvent) new ModelCallEndEvent("r-1", new ChatUsage(100, 50, 0.01)),
                new ModelCallEndEvent("r-2", new ChatUsage(30, 20, 0.01)),
                new AgentResultEvent(msg("m-1")))).blockLast();

        assertThat(mw.totalTokens()).isEqualTo(200L);
    }

    @Test
    @DisplayName("usage 缺席安全跳过：模型缺报不炸中间件，读数不变小为 0")
    void givenNullUsage_whenAgentCompletes_thenSkippedSafely() {
        ReportUsageMiddleware mw = new ReportUsageMiddleware();
        RuntimeContext ctx = RuntimeContext.builder().userId("7").sessionId("report-2").build();

        mw.onAgent(null, ctx, new AgentInput(List.of()), input -> Flux.just(
                (AgentEvent) new ModelCallEndEvent("r-1", null),
                new AgentResultEvent(msg("m-1")))).blockLast();

        assertThat(mw.totalTokens()).isZero();
    }

    @Test
    @DisplayName("真值快照：完成时点入队，drain 排空后为空（防跨轮重复）")
    void givenTruthPool_whenAgentCompletes_thenDrainedOnce() throws Exception {
        ReportUsageMiddleware mw = new ReportUsageMiddleware();
        RuntimeContext ctx = RuntimeContext.builder().userId("7").sessionId("report-3").build();
        seedTruth(ctx, truth("get_quote"));

        mw.onAgent(null, ctx, new AgentInput(List.of()),
                input -> Flux.just((AgentEvent) new AgentResultEvent(msg("m-1")))).blockLast();

        assertThat(mw.drainTruthInvocations()).hasSize(1);
        assertThat(mw.drainTruthInvocations()).isEmpty();
    }

    @Test
    @DisplayName("无真值池完成：安全空快照（current 新建空池）")
    void givenEmptyPool_whenAgentCompletes_thenNothingDrained() {
        ReportUsageMiddleware mw = new ReportUsageMiddleware();
        RuntimeContext ctx = RuntimeContext.builder().userId("7").sessionId("report-4").build();

        mw.onAgent(null, ctx, new AgentInput(List.of()),
                input -> Flux.just((AgentEvent) new AgentResultEvent(msg("m-1")))).blockLast();

        assertThat(mw.drainTruthInvocations()).isEmpty();
    }

    @Test
    @DisplayName("null ctx 完成流：真值采集防御性跳过，drain 为空且 ThreadLocal 无残留")
    void givenNullContext_whenAgentCompletes_thenTruthSkippedAndDrainEmpty() {
        TrustContext.reset();
        ReportUsageMiddleware mw = new ReportUsageMiddleware();

        mw.onAgent(null, null, new AgentInput(List.of()),
                input -> Flux.just((AgentEvent) new AgentResultEvent(msg("m-1")))).blockLast();

        assertThat(mw.drainTruthInvocations()).isEmpty();
    }

    // ———— 夹具 ————

    private static Msg msg(String id) {
        return Msg.builder().id(id).role(MsgRole.ASSISTANT).textContent("轮收尾").build();
    }

    private static ToolInvocation truth(String toolName) {
        return new ToolInvocation(toolName, Map.of(), null, List.of(), null,
                ToolInvocation.AsOfKind.CALL, false, false, 5L);
    }

    /** 反射注真值（TrustContext.fresh/record 包私有——ReportSteps 同款，跨包测试唯一通道）。 */
    private static void seedTruth(RuntimeContext rc, ToolInvocation invocation) throws Exception {
        Method fresh = TrustContext.class.getDeclaredMethod("fresh", RuntimeContext.class);
        fresh.setAccessible(true);
        Object pool = fresh.invoke(null, rc);
        Method record = TrustContext.class.getDeclaredMethod("record", ToolInvocation.class);
        record.setAccessible(true);
        record.invoke(pool, invocation);
    }
}
