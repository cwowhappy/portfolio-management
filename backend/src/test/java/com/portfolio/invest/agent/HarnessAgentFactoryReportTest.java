package com.portfolio.invest.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.portfolio.invest.agent.report.ReportUsageMiddleware;
import com.portfolio.invest.agent.report.ReportWorkflowPrompt;
import com.portfolio.invest.application.skill.SkillApplicationService;
import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.mcp.AuthType;
import com.portfolio.invest.domain.mcp.McpConfigRepository;
import com.portfolio.invest.domain.mcp.McpEndpoint;
import com.portfolio.invest.domain.mcp.McpProvider;
import com.portfolio.invest.domain.mcp.McpSecretCodec;
import com.portfolio.invest.domain.mcp.McpUserConfig;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.model.Model;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.harness.agent.HarnessAgent;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

/**
 * MS-32 B8 研报 Agent 工厂变体回归红线：对话链路 {@code build(Long)}/{@code systemPrompt(userId)}
 * 行为零变更约束下，变体差异经包私有接缝（{@code reportSystemPrompt}/{@code reportToolkit}）断言，
 * 不内省已构建 agent（装配冒烟除外）。红线 1（systemPrompt 全关授权与 InvestSystemPrompt.TEXT
 * 逐字节一致）由 MS-31 既有同包用例
 * {@code HarnessAgentFactoryTest#givenEmptyProfileBlock_whenSystemPrompt_thenByteIdenticalToBaseline}
 * 承担，不重写。
 */
class HarnessAgentFactoryReportTest {

    private static final Duration TOOL_TIMEOUT = Duration.ofSeconds(30);
    private static final Instant NOW = Instant.parse("2026-10-11T08:00:00Z");

    /** 内置 17 工具名（InvestTools 13 + UserInvestTools 4，UserToolkitFactoryTest 同款名单）。 */
    private static final List<String> BUILTIN_TOOL_NAMES = List.of(
            // InvestTools @Tool（13）
            "search_stock", "get_quote", "get_kline", "get_financials", "get_news",
            "search_news", "macro_brief", "get_market_overview", "get_valuation",
            "screen_stocks", "analyze_financials", "analyze_industry", "research_draft",
            // UserInvestTools @Tool（4）
            "analyze_portfolio", "suggest_allocation", "search_announcements", "search_wiki");

    @TempDir
    Path tempDir;

    @DisplayName("红线2：reportSystemPrompt 前缀与 systemPrompt 逐字节一致（画像块不被吞），尾部恒接指引块")
    @Test
    void givenProfileBlock_whenReportSystemPrompt_thenByteIdenticalPrefixThenWorkflowBlock() throws IOException {
        String block = "<user_profile>\n<risk_profile>平衡型</risk_profile>\n</user_profile>";
        ProfileContextBuilder profileContextBuilder = mock(ProfileContextBuilder.class);
        when(profileContextBuilder.build(1L)).thenReturn(Optional.of(block));
        HarnessAgentFactory factory = factory(mockedToolkitFactory(), profileContextBuilder);

        String dialogue = factory.systemPrompt(1L);
        String report = factory.reportSystemPrompt(1L);

        // 前缀逐字节（UTF-8）：不吞画像块、不加不减任何空白（红线口径 = 字节级比对）
        byte[] dialogueBytes = dialogue.getBytes(StandardCharsets.UTF_8);
        byte[] reportBytes = report.getBytes(StandardCharsets.UTF_8);
        assertThat(Arrays.mismatch(
                reportBytes, 0, dialogueBytes.length, dialogueBytes, 0, dialogueBytes.length))
                .as("reportSystemPrompt 前 N 字节与 systemPrompt 完全一致（N=对话提示词字节数）")
                .isEqualTo(-1);
        // 整体契约：对话提示词 + 空行 + 深研指引块（拼接序固定）
        assertThat(report).isEqualTo(dialogue + "\n\n" + ReportWorkflowPrompt.BLOCK);
    }

    @DisplayName("红线3：reportToolkit 全内置 17 名（16+search_wiki），MCP 启用亦被排除、池零咨询")
    @Test
    void givenMcpProviderEnabled_whenReportToolkit_thenBuiltinNamesOnlyAndPoolUntouched() throws IOException {
        // MCP 侧就绪（provider/endpoint/config/client 打桩齐备）——若误走 build() 全装配，
        // trade_cal 将注册进 toolkit、clientPool 将被咨询（负断言因此有意义）
        McpConfigRepository repository = mock(McpConfigRepository.class);
        McpClientPool clientPool = mock(McpClientPool.class);
        McpClientWrapper client = mock(McpClientWrapper.class);
        McpProvider provider =
                McpProvider.reconstitute(2L, "tushare", "Tushare", AuthType.BEARER, null, "token", true, null, NOW);
        McpEndpoint endpoint =
                McpEndpoint.reconstitute(3L, 2L, null, "Tushare 数据", "https://api.tushare.pro/mcp/", true, NOW);
        McpUserConfig config = McpUserConfig.reconstitute(9L, 1L, 2L, true, List.of(), 1, NOW, NOW);
        when(repository.findEnabledProviders()).thenReturn(List.of(provider));
        when(repository.findByUserIdAndProviderId(1L, 2L)).thenReturn(Optional.of(config));
        when(repository.findEnabledEndpointsByProviderId(2L)).thenReturn(List.of(endpoint));
        McpSecretCodec codec = mock(McpSecretCodec.class);
        when(codec.decrypt(any())).thenAnswer(inv -> inv.getArgument(0));
        when(clientPool.acquire(provider, endpoint, "token")).thenReturn(client);
        when(client.listTools()).thenReturn(Mono.just(List.of(McpSchema.Tool.builder()
                .name("trade_cal").description("交易日历").build())));

        UserToolkitFactory toolkitFactory = new UserToolkitFactory(mock(InvestTools.class), repository, clientPool,
                codec,
                mock(com.portfolio.invest.application.portfolio.PortfolioApplicationService.class),
                mock(com.portfolio.invest.application.allocation.AllocationApplicationService.class),
                mock(com.portfolio.invest.application.intelligence.IntelligenceQueryService.class),
                mock(com.portfolio.invest.application.wiki.WikiApplicationService.class),
                new com.fasterxml.jackson.databind.ObjectMapper(), TOOL_TIMEOUT);
        HarnessAgentFactory factory = factory(toolkitFactory, mock(ProfileContextBuilder.class));

        Toolkit toolkit = factory.reportToolkit(1L);

        assertThat(toolkit.getToolNames())
                .as("研报工具集 = 内置 17 名（16+search_wiki），无 MCP 名（trade_cal 不在）")
                .containsExactlyInAnyOrderElementsOf(BUILTIN_TOOL_NAMES);
        verifyNoInteractions(clientPool);
        verifyNoInteractions(repository);
    }

    @DisplayName("装配冒烟：buildReportAgent 双 hook + usage 中间件挂载（同实例），maxIters 取 report 执行档")
    @Test
    void givenReportUsageMiddleware_whenBuildReportAgent_thenUsageMiddlewareMountedAndReportMaxIters()
            throws IOException {
        UserToolkitFactory toolkitFactory = mockedToolkitFactory();
        SkillApplicationService skillApplicationService = mock(SkillApplicationService.class);
        when(skillApplicationService.enabledSkillCodes(1L)).thenReturn(List.of());
        InvestProperties props = tempDirProperties();
        // 特异值 7：防与 HarnessAgent 缺省 maxIters 巧合相等（缺省档为 15）
        props.getReport().setExecution(new InvestProperties.Report.Execution(
                Duration.ofSeconds(240), Duration.ofMinutes(15), 7, 2));
        HarnessAgentFactory factory = new HarnessAgentFactory(toolkitFactory, skillApplicationService,
                mock(ClasspathSkillRepository.class), mock(Model.class), props,
                mock(com.portfolio.invest.domain.observability.ObservabilityRecorder.class),
                new com.fasterxml.jackson.databind.ObjectMapper(), mock(ProfileContextBuilder.class));
        ReportUsageMiddleware usageMiddleware = new ReportUsageMiddleware();

        HarnessAgent agent = factory.buildReportAgent(1L, usageMiddleware);

        List<Hook> hooks = agent.getDelegate().getHooks();
        assertThat(hooks).anyMatch(h -> h instanceof com.portfolio.invest.agent.trust.TrustAgentHook);
        assertThat(hooks).anyMatch(h -> h instanceof com.portfolio.invest.agent.observability.ObservabilityAgentHook);
        List<io.agentscope.core.middleware.MiddlewareBase> middlewares = agent.getDelegate().getMiddlewares();
        assertThat(middlewares).as("用量中间件按入参同实例挂载（追加在既有双 hook 后）").contains(usageMiddleware);
        assertThat(agent.getDelegate().getMaxIters())
                .as("单轮最大迭代取 invest.report.execution.max-iters-per-round")
                .isEqualTo(7);
        // rc 桥接（2.0.4 升级）：buildReportAgent 路径两消费者（trust/observability）同源送抵
        // ——usage 中间件经 onAgent 入参直收 ctx，不进桥接
        RuntimeContextBridgeMiddleware bridge = middlewares.stream()
                .filter(RuntimeContextBridgeMiddleware.class::isInstance)
                .map(RuntimeContextBridgeMiddleware.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("rc 桥接中间件未装配"));
        // 防「加 hook 忘桥接」：消费者数与 hook 数同源
        assertThat(bridge.consumerCount()).isEqualTo(hooks.size());
        assertThat(bridge.order()).isEqualTo(Integer.MAX_VALUE);
        assertThat(middlewares)
                .anyMatch(m -> m instanceof com.portfolio.invest.agent.trust.TrustWireMessageIdMiddleware);
    }

    // ———— 脚手架 ————

    /** mock UserToolkitFactory：buildWithoutMcp 桩为空 Toolkit（红线 2/装配冒烟用，不涉工具名）。 */
    private static UserToolkitFactory mockedToolkitFactory() {
        UserToolkitFactory toolkitFactory = mock(UserToolkitFactory.class);
        when(toolkitFactory.buildWithoutMcp(1L)).thenReturn(new Toolkit());
        return toolkitFactory;
    }

    /** 真实 InvestProperties（Harness 嵌套 POJO 有默认值），workspace/stateRoot 指向 @TempDir。 */
    private InvestProperties tempDirProperties() throws IOException {
        Files.createDirectories(tempDir.resolve("workspace"));
        Files.createDirectories(tempDir.resolve("state"));
        InvestProperties props = new InvestProperties();
        props.getMcp().getHarness().setWorkspace(tempDir.resolve("workspace").toString());
        props.getMcp().getHarness().setStateRoot(tempDir.resolve("state").toString());
        return props;
    }

    private HarnessAgentFactory factory(UserToolkitFactory toolkitFactory,
            ProfileContextBuilder profileContextBuilder) throws IOException {
        return new HarnessAgentFactory(toolkitFactory, mock(SkillApplicationService.class),
                mock(ClasspathSkillRepository.class), mock(Model.class), tempDirProperties(),
                mock(com.portfolio.invest.domain.observability.ObservabilityRecorder.class),
                new com.fasterxml.jackson.databind.ObjectMapper(), profileContextBuilder);
    }
}
