package com.portfolio.invest.agent.trust;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.config.InvestProperties;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.PostSummaryEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ChatUsage;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * MS-32 B0 探针轮（实施计划 Task B0，设计规格 §七探针表）：为校验门（B7）/编排器（B9）/
 * 用量钩子（B8）实证机制假设，结论回填计划附录 A。探针测试保留为回归钉。
 *
 * <p>探针覆盖（探针 4 无测试——InvestTools 构造器仅注入服务已被控制器预证，附录 A 引 javadoc；
 * 探针 5 无测试——stateStore 文件残留属文件系统观测，结论由目录现状 + agent.md state 节得出）：</p>
 * <ul>
 *   <li><strong>探针 1</strong>：TrustContext 轮间清池时序与 rc 通道读取形态——含生产路径
 *       （TrustAgentHook PRE_CALL 实际触发 fresh 清池）；</li>
 *   <li><strong>探针 2</strong>：内置工具真值 anchor 分桶（mcp 字面标志，asOfKind 不参与）——
 *       含 wiki（GENERATED）可 verified 的规格推断证伪钉；</li>
 *   <li><strong>探针 3</strong>：POST_SUMMARY/POST_REASONING 事件携带 Msg.getUsage() 的
 *       字段形态与累计口径（ReportUsageHook 事件源，沿 ObservabilityAgentHook.accumulate 同构）。</li>
 * </ul>
 */
class ReportProbeTest {

    /** 行情真值样例（ObservabilityAgentHookTest POOL_JSON 同款：含 ISO 日期/时钟段验证排除面）。 */
    private static final String QUOTE_JSON = "{\"price\":1520.33,\"time\":\"2026-10-05 14:59:32\"}";

    private final ConsistencyValidator validator =
            new ConsistencyValidator(new InvestProperties().getTrust());

    @AfterEach
    void cleanFallback() {
        TrustContext.reset();
    }

    // ———— 探针 1：TrustContext 轮间清池 + rc 通道读取（B9 编排器快照实现） ————

    @DisplayName("探针1-a：rc typed 通道可读——current(rc) 回合内同池累积，invocations() 为 List 快照")
    @Test
    void givenRcChannel_whenRecordAndRead_thenSamePoolAccumulates() {
        RuntimeContext rc = RuntimeContext.builder().userId("1").sessionId("report-probe-1").build();

        TrustContext round1 = TrustContext.current(rc);
        round1.record(truth("get_quote", QUOTE_JSON, ToolInvocation.AsOfKind.DATA, false));

        assertThat(TrustContext.current(rc)).isSameAs(round1);
        assertThat(TrustContext.current(rc).invocations()).hasSize(1);
        assertThat(TrustContext.current(rc).invocations().get(0).toolName()).isEqualTo("get_quote");
    }

    @DisplayName("探针1-b：fresh（PRE_CALL 同语义）清池——rc 换挂新池，旧池不可达")
    @Test
    void givenPopulatedRcPool_whenFresh_thenNewEmptyPoolAttached() {
        RuntimeContext rc = RuntimeContext.builder().userId("1").sessionId("report-probe-1").build();
        TrustContext round1 = TrustContext.current(rc);
        round1.record(truth("get_quote", QUOTE_JSON, ToolInvocation.AsOfKind.DATA, false));

        TrustContext round2 = TrustContext.fresh(rc);

        assertThat(round2).isNotSameAs(round1);
        assertThat(round2.invocations()).isEmpty();
        assertThat(TrustContext.current(rc)).isSameAs(round2);
    }

    @DisplayName("探针1-c：生产路径——TrustAgentHook 收到 PreCallEvent 实际清池（每轮 call 返回后须自行快照累积）")
    @Test
    void givenPopulatedPool_whenTrustAgentHookPreCall_thenPoolClearedOnRc() {
        RuntimeContext rc = RuntimeContext.builder().userId("1").sessionId("report-probe-1").build();
        TrustContext round1 = TrustContext.current(rc);
        round1.record(truth("get_quote", QUOTE_JSON, ToolInvocation.AsOfKind.DATA, false));
        TrustAgentHook hook = new TrustAgentHook(new InvestProperties().getTrust());
        hook.setRuntimeContext(rc);

        hook.onEvent(new PreCallEvent(TrustAgentHookTest.STUB_AGENT, List.of())).block();

        assertThat(TrustContext.current(rc)).isNotSameAs(round1);
        assertThat(TrustContext.current(rc).invocations()).isEmpty();
    }

    // ———— 探针 2：内置工具真值 anchor 分桶（B7 校验门输入 / B12 展示口径） ————

    @DisplayName("探针2-a：内置行情真值（mcp=false, DATA）进比对池——15.20 vs 1520.33 可归因替换成立")
    @Test
    void givenBuiltinQuoteTruth_whenCorrectDeviation_thenAttributableReplacement() {
        List<ToolInvocation> pool =
                List.of(truth("get_quote", QUOTE_JSON, ToolInvocation.AsOfKind.DATA, false));

        CorrectionResult result = validator.correct("当前股价15.20元。", pool);

        assertThat(result.corrections()).hasSize(1);
        assertThat(result.corrections().get(0).snippet()).isEqualTo("15.20元");
        assertThat(result.corrections().get(0).replacement()).isEqualTo("1520.33元");
        assertThat(result.correctedText())
                .contains("1520.33元")
                .contains(ConsistencyValidator.NOTE_LINE_PREFIX);
        assertThat(result.batch().stats().verified()).isEqualTo(1);
    }

    @DisplayName("探针2-b：MCP 真值（mcp=true, CALL）不入比对池——值相等只配源、零修正")
    @Test
    void givenMcpTruthOnly_whenCorrectEqualValue_thenSourcedWithoutCorrection() {
        List<ToolInvocation> pool = List.of(
                truth("tushare_daily", "{\"pe_ttm\":25.30}", ToolInvocation.AsOfKind.CALL, true));

        CorrectionResult result = validator.correct("市盈率25.30。", pool);

        assertThat(result.corrections()).isEmpty();
        assertThat(result.batch().stats().sourced()).isEqualTo(1);
        assertThat(result.batch().stats().verified()).isZero();
        assertThat(result.batch().anchors().get(0).state()).isEqualTo(TrustVerdict.SOURCED);
        assertThat(result.batch().anchors().get(0).tool()).isEqualTo("tushare_daily");
    }

    @DisplayName("探针2-c：内置无时点真值（mcp=false, CALL）进比对池可 verified——与 MCP CALL 语义分桶")
    @Test
    void givenBuiltinCallKindTruth_whenVerifyEqualValue_thenVerified() {
        List<ToolInvocation> pool = List.of(
                truth("macro_brief", "{\"events_in_window\":3}", ToolInvocation.AsOfKind.CALL, false));

        CorrectionResult result = validator.correct("本窗口政策事件3起。", pool);

        assertThat(result.batch().stats().verified()).isEqualTo(1);
        assertThat(result.batch().anchors().get(0).tool()).isEqualTo("macro_brief");
    }

    @DisplayName("探针2-d：wiki 真值（mcp=false, GENERATED）实测进比对池可 verified——分桶只看 mcp 标志")
    @Test
    void givenBuiltinWikiGeneratedTruth_whenVerifyEqualValue_thenVerifiedNotSourced() {
        List<ToolInvocation> pool = List.of(truth("search_wiki",
                "安全边际阈值25%，低于该阈值分批买入", ToolInvocation.AsOfKind.GENERATED, false));

        CorrectionResult result = validator.correct("本笔记设定安全边际为25%。", pool);

        // 设计规格 §3.6 推断「wiki GENERATED 恒 SOURCED/UNVERIFIED、不参与 VERIFIED 判定」——
        // 实测推翻：truths() 分桶只读 mcp 字面标志，asOfKind 不参与；规格推断作废（附录 A 回填）
        assertThat(result.batch().stats().verified()).isEqualTo(1);
        assertThat(result.batch().stats().sourced()).isZero();
        assertThat(result.batch().anchors().get(0).asOfKind())
                .isEqualTo(ToolInvocation.AsOfKind.GENERATED);
    }

    // ———— 探针 3：usage 事件源（B8 ReportUsageHook） ————

    @DisplayName("探针3-a：POST_SUMMARY/POST_REASONING 事件携带 Msg.usage——input/output/total 可读")
    @Test
    void givenUsageOnMsg_whenWrappedInPostEvents_thenUsageReadable() {
        Msg summary = Msg.builder().id("s1").role(MsgRole.ASSISTANT).textContent("阶段小结")
                .usage(new ChatUsage(100, 50, 0.01)).build();
        PostSummaryEvent summaryEvent =
                new PostSummaryEvent(TrustAgentHookTest.STUB_AGENT, "deepseek-chat", null, summary);

        assertThat(summaryEvent.getSummaryMessage().getUsage()).isNotNull();
        assertThat(summaryEvent.getSummaryMessage().getUsage().getInputTokens()).isEqualTo(100);
        assertThat(summaryEvent.getSummaryMessage().getUsage().getOutputTokens()).isEqualTo(50);
        assertThat(summaryEvent.getSummaryMessage().getUsage().getTotalTokens()).isEqualTo(150);

        Msg reasoning = Msg.builder().id("r1").role(MsgRole.ASSISTANT).textContent("推理正文")
                .usage(new ChatUsage(30, 20, 0.01)).build();
        PostReasoningEvent reasoningEvent =
                new PostReasoningEvent(TrustAgentHookTest.STUB_AGENT, "deepseek-chat", null, reasoning);

        assertThat(reasoningEvent.getReasoningMessage().getUsage()).isNotNull();
        assertThat(reasoningEvent.getReasoningMessage().getUsage().getTotalTokens()).isEqualTo(50);
    }

    @DisplayName("探针3-b：usage 缺席为 null 字段（非异常）——模型缺报时累计跳过")
    @Test
    void givenMsgWithoutUsage_whenWrappedInPostReasoning_thenUsageNull() {
        Msg noUsage = Msg.builder().id("r2").role(MsgRole.ASSISTANT).textContent("回复正文").build();
        PostReasoningEvent event =
                new PostReasoningEvent(TrustAgentHookTest.STUB_AGENT, "deepseek-chat", null, noUsage);

        assertThat(event.getReasoningMessage().getUsage()).isNull();
    }

    // ———— 脚手架 ————

    private static ToolInvocation truth(String toolName, String resultText,
            ToolInvocation.AsOfKind kind, boolean mcp) {
        return new ToolInvocation(toolName, Map.of("code", "600519"), resultText, List.of(),
                "2026-10-11 09:00:00", kind, false, mcp, 120L);
    }
}
