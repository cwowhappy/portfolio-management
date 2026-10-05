package com.portfolio.invest.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.portfolio.invest.agent.trust.RecordingAgentToolDecorator;
import com.portfolio.invest.agent.trust.ToolInvocation;
import com.portfolio.invest.agent.trust.TrustContext;
import com.portfolio.invest.domain.mcp.AuthType;
import com.portfolio.invest.domain.mcp.McpConfigRepository;
import com.portfolio.invest.domain.mcp.McpEndpoint;
import com.portfolio.invest.domain.mcp.McpErrorCode;
import com.portfolio.invest.domain.mcp.McpException;
import com.portfolio.invest.domain.mcp.McpProvider;
import com.portfolio.invest.domain.mcp.McpSecretCodec;
import com.portfolio.invest.domain.mcp.McpUserConfig;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/** 用户工具装配：MCP 工具 readOnly 按 MCP 规范判定——readOnlyHint=true 放行，缺省/false 视为写（FR-1）。 */
class UserToolkitFactoryTest {

    private static final Instant NOW = Instant.parse("2026-09-07T08:00:00Z");
    /** 装配 listTools 阻塞超时（invest.mcp.toolTimeout，B3 接线断言用）。 */
    private static final java.time.Duration TOOL_TIMEOUT = java.time.Duration.ofSeconds(30);

    /** ToolAnnotations 规范字段序：title, readOnlyHint, destructiveHint, idempotentHint, openWorldHint, returnDirect。 */
    private static McpSchema.ToolAnnotations annotations(Boolean readOnlyHint) {
        return new McpSchema.ToolAnnotations(null, readOnlyHint, null, null, null, null);
    }

    private static McpSchema.Tool tool(String name, McpSchema.ToolAnnotations toolAnnotations) {
        return McpSchema.Tool.builder()
                .name(name)
                .description("验证工具")
                .annotations(toolAnnotations)
                .build();
    }

    /** 明文直读 codec（存量值原样返回；解密路径由专项用例覆盖）。 */
    private static McpSecretCodec passthroughCodec() {
        McpSecretCodec codec = mock(McpSecretCodec.class);
        when(codec.decrypt(any())).thenAnswer(inv -> inv.getArgument(0));
        return codec;
    }

    @AfterEach
    void cleanTrustFallback() {
        TrustContext.reset();
    }

    /** 标准装配环境：单 provider/endpoint/config，注入给定工具定义列表。 */
    private Toolkit buildToolkitWith(List<McpSchema.Tool> mcpTools, List<String> disabledTools) {
        InvestTools investTools = mock(InvestTools.class);
        McpConfigRepository repository = mock(McpConfigRepository.class);
        McpClientPool clientPool = mock(McpClientPool.class);
        McpClientWrapper client = mock(McpClientWrapper.class);

        McpProvider provider = McpProvider.reconstitute(2L, "tushare", "Tushare", AuthType.BEARER, null, "token", true, null, NOW);
        McpEndpoint endpoint = McpEndpoint.reconstitute(3L, 2L, null, "Tushare 数据", "https://api.tushare.pro/mcp/", true, NOW);
        McpUserConfig config = McpUserConfig.reconstitute(9L, 1L, 2L, true, disabledTools, 1, NOW, NOW);

        when(repository.findEnabledProviders()).thenReturn(List.of(provider));
        when(repository.findByUserIdAndProviderId(1L, 2L)).thenReturn(Optional.of(config));
        when(repository.findEnabledEndpointsByProviderId(2L)).thenReturn(List.of(endpoint));
        when(clientPool.acquire(provider, endpoint, "token")).thenReturn(client);
        when(client.listTools()).thenReturn(Mono.just(mcpTools));
        when(client.getName()).thenReturn("tushare");

        return new UserToolkitFactory(investTools, repository, clientPool, passthroughCodec(),
                mock(com.portfolio.invest.application.portfolio.PortfolioApplicationService.class),
                mock(com.portfolio.invest.application.allocation.AllocationApplicationService.class),
                mock(com.portfolio.invest.application.intelligence.IntelligenceQueryService.class),
                new com.fasterxml.jackson.databind.ObjectMapper(), TOOL_TIMEOUT).build(1L);
    }

    @DisplayName("readOnlyHint=true 的 MCP 工具装配为只读（不触发审批）")
    @Test
    void givenReadOnlyHintTrue_whenBuild_thenToolReadOnly() {
        Toolkit toolkit = buildToolkitWith(List.of(tool("trade_cal", annotations(true))), List.of());
        assertThat(toolkit.getTool("trade_cal")).isNotNull();
        assertThat(toolkit.getTool("trade_cal").isReadOnly()).isTrue();
    }

    @DisplayName("未标 readOnlyHint 的 MCP 工具装配为写（触发审批）")
    @Test
    void givenNoAnnotations_whenBuild_thenToolWritable() {
        Toolkit toolkit = buildToolkitWith(List.of(tool("write_note", null)), List.of());
        assertThat(toolkit.getTool("write_note")).isNotNull();
        assertThat(toolkit.getTool("write_note").isReadOnly()).isFalse();
    }

    @DisplayName("readOnlyHint=false 的 MCP 工具装配为写（触发审批）")
    @Test
    void givenReadOnlyHintFalse_whenBuild_thenToolWritable() {
        Toolkit toolkit = buildToolkitWith(List.of(tool("write_note", annotations(false))), List.of());
        assertThat(toolkit.getTool("write_note")).isNotNull();
        assertThat(toolkit.getTool("write_note").isReadOnly()).isFalse();
    }

    @DisplayName("被禁用的 MCP 工具不注册")
    @Test
    void givenDisabledTool_whenBuild_thenToolSkipped() {
        Toolkit toolkit = buildToolkitWith(List.of(tool("trade_cal", null)), List.of("trade_cal"));
        assertThat(toolkit.getTool("trade_cal")).isNull();
    }

    @DisplayName("MCP 工具与内置同名：内置保留，MCP 版被跳过")
    @Test
    void givenMcpToolNamedAsBuiltin_whenBuild_thenBuiltinWinsAndMcpSkipped() {
        // search_stock 与内置同名且无 readOnlyHint（若 MCP 覆盖则只读判定翻为 false）；mcp_extra 不重名作对照
        Toolkit toolkit = buildToolkitWith(
                List.of(tool("search_stock", null), tool("mcp_extra", annotations(true))), List.of());
        assertThat(toolkit.getTool("mcp_extra")).as("不重名 MCP 工具正常注册（装配流程走通）").isNotNull();
        assertThat(toolkit.getTool("search_stock").isReadOnly())
                .as("同名时内置胜出（内置 search_stock 只读；被 MCP 覆盖则为写）")
                .isTrue();
    }

    @DisplayName("用户配置缺失 → MCP 工具不注册，内置工具仍在")
    @Test
    void givenUserConfigMissing_whenBuild_thenMcpToolsSkippedAndBuiltinRemain() {
        InvestTools investTools = mock(InvestTools.class);
        McpConfigRepository repository = mock(McpConfigRepository.class);
        McpClientPool clientPool = mock(McpClientPool.class);
        McpClientWrapper client = mock(McpClientWrapper.class);

        McpProvider provider = McpProvider.reconstitute(2L, "tushare", "Tushare", AuthType.BEARER, null, "token", true, null, NOW);
        McpEndpoint endpoint = McpEndpoint.reconstitute(3L, 2L, null, "Tushare 数据", "https://api.tushare.pro/mcp/", true, NOW);

        when(repository.findEnabledProviders()).thenReturn(List.of(provider));
        when(repository.findByUserIdAndProviderId(1L, 2L)).thenReturn(Optional.empty());
        // 端点与工具就绪：若未在「配置缺失」分支短路，trade_cal 将被注册（负断言因此有意义）
        when(repository.findEnabledEndpointsByProviderId(2L)).thenReturn(List.of(endpoint));
        when(clientPool.acquire(provider, endpoint, "token")).thenReturn(client);
        when(client.listTools()).thenReturn(Mono.just(List.of(tool("trade_cal", annotations(true)))));

        Toolkit toolkit = new UserToolkitFactory(investTools, repository, clientPool, passthroughCodec(),
                mock(com.portfolio.invest.application.portfolio.PortfolioApplicationService.class),
                mock(com.portfolio.invest.application.allocation.AllocationApplicationService.class),
                mock(com.portfolio.invest.application.intelligence.IntelligenceQueryService.class),
                new com.fasterxml.jackson.databind.ObjectMapper(), TOOL_TIMEOUT).build(1L);

        assertThat(toolkit.getTool("trade_cal")).as("配置缺失 → MCP 工具不注册").isNull();
        assertThat(toolkit.getTool("search_stock")).as("内置工具不受影响").isNotNull();
        verifyNoInteractions(clientPool);
    }

    @DisplayName("用户配置停用 → MCP 工具不注册，内置工具仍在")
    @Test
    void givenUserConfigDisabled_whenBuild_thenMcpToolsSkipped() {
        InvestTools investTools = mock(InvestTools.class);
        McpConfigRepository repository = mock(McpConfigRepository.class);
        McpClientPool clientPool = mock(McpClientPool.class);
        McpClientWrapper client = mock(McpClientWrapper.class);

        McpProvider provider = McpProvider.reconstitute(2L, "tushare", "Tushare", AuthType.BEARER, null, "token", true, null, NOW);
        McpEndpoint endpoint = McpEndpoint.reconstitute(3L, 2L, null, "Tushare 数据", "https://api.tushare.pro/mcp/", true, NOW);
        McpUserConfig config = McpUserConfig.reconstitute(9L, 1L, 2L, false, List.of(), 1, NOW, NOW);

        when(repository.findEnabledProviders()).thenReturn(List.of(provider));
        when(repository.findByUserIdAndProviderId(1L, 2L)).thenReturn(Optional.of(config));
        // 端点与工具就绪：若未在「配置停用」分支短路，trade_cal 将被注册
        when(repository.findEnabledEndpointsByProviderId(2L)).thenReturn(List.of(endpoint));
        when(clientPool.acquire(provider, endpoint, "token")).thenReturn(client);
        when(client.listTools()).thenReturn(Mono.just(List.of(tool("trade_cal", annotations(true)))));

        Toolkit toolkit = new UserToolkitFactory(investTools, repository, clientPool, passthroughCodec(),
                mock(com.portfolio.invest.application.portfolio.PortfolioApplicationService.class),
                mock(com.portfolio.invest.application.allocation.AllocationApplicationService.class),
                mock(com.portfolio.invest.application.intelligence.IntelligenceQueryService.class),
                new com.fasterxml.jackson.databind.ObjectMapper(), TOOL_TIMEOUT).build(1L);

        assertThat(toolkit.getTool("trade_cal")).as("配置停用 → MCP 工具不注册").isNull();
        assertThat(toolkit.getTool("search_stock")).as("内置工具不受影响").isNotNull();
        verifyNoInteractions(clientPool);
    }

    @DisplayName("provider 密钥缺失（需鉴权）→ 该 provider 工具全部跳过")
    @Test
    void givenAuthSecretMissing_whenBuild_thenToolsSkipped() {
        InvestTools investTools = mock(InvestTools.class);
        McpConfigRepository repository = mock(McpConfigRepository.class);
        McpClientPool clientPool = mock(McpClientPool.class);
        McpClientWrapper client = mock(McpClientWrapper.class);

        // BEARER 但密钥为 null：配置存在且启用，证明跳过源于密钥守卫而非配置分支
        McpProvider provider = McpProvider.reconstitute(2L, "tushare", "Tushare", AuthType.BEARER, null, null, true, null, NOW);
        McpEndpoint endpoint = McpEndpoint.reconstitute(3L, 2L, null, "Tushare 数据", "https://api.tushare.pro/mcp/", true, NOW);
        McpUserConfig config = McpUserConfig.reconstitute(9L, 1L, 2L, true, List.of(), 1, NOW, NOW);

        when(repository.findEnabledProviders()).thenReturn(List.of(provider));
        when(repository.findByUserIdAndProviderId(1L, 2L)).thenReturn(Optional.of(config));
        // 端点与工具就绪（token 将为 null）：若未在「密钥缺失」分支短路，trade_cal 将被注册
        when(repository.findEnabledEndpointsByProviderId(2L)).thenReturn(List.of(endpoint));
        when(clientPool.acquire(provider, endpoint, null)).thenReturn(client);
        when(client.listTools()).thenReturn(Mono.just(List.of(tool("trade_cal", annotations(true)))));

        Toolkit toolkit = new UserToolkitFactory(investTools, repository, clientPool, passthroughCodec(),
                mock(com.portfolio.invest.application.portfolio.PortfolioApplicationService.class),
                mock(com.portfolio.invest.application.allocation.AllocationApplicationService.class),
                mock(com.portfolio.invest.application.intelligence.IntelligenceQueryService.class),
                new com.fasterxml.jackson.databind.ObjectMapper(), TOOL_TIMEOUT).build(1L);

        assertThat(toolkit.getTool("trade_cal")).as("密钥缺失 → MCP 工具不注册").isNull();
        assertThat(toolkit.getTool("search_stock")).as("内置工具不受影响").isNotNull();
        verifyNoInteractions(clientPool);
    }

    @DisplayName("无启用端点 → 不获取客户端，内置工具仍在")
    @Test
    void givenEndpointDisabled_whenBuild_thenNoAcquire() {
        InvestTools investTools = mock(InvestTools.class);
        McpConfigRepository repository = mock(McpConfigRepository.class);
        McpClientPool clientPool = mock(McpClientPool.class);

        McpProvider provider = McpProvider.reconstitute(2L, "tushare", "Tushare", AuthType.BEARER, null, "token", true, null, NOW);
        McpUserConfig config = McpUserConfig.reconstitute(9L, 1L, 2L, true, List.of(), 1, NOW, NOW);

        when(repository.findEnabledProviders()).thenReturn(List.of(provider));
        when(repository.findByUserIdAndProviderId(1L, 2L)).thenReturn(Optional.of(config));
        when(repository.findEnabledEndpointsByProviderId(2L)).thenReturn(List.of());

        Toolkit toolkit = new UserToolkitFactory(investTools, repository, clientPool, passthroughCodec(),
                mock(com.portfolio.invest.application.portfolio.PortfolioApplicationService.class),
                mock(com.portfolio.invest.application.allocation.AllocationApplicationService.class),
                mock(com.portfolio.invest.application.intelligence.IntelligenceQueryService.class),
                new com.fasterxml.jackson.databind.ObjectMapper(), TOOL_TIMEOUT).build(1L);

        assertThat(toolkit.getTool("search_stock")).as("内置工具不受影响").isNotNull();
        verifyNoInteractions(clientPool);
    }

    @DisplayName("单端点 listTools 失败 → 该端点跳过，其余端点照常注册")
    @Test
    void givenOneEndpointListToolsFails_whenBuild_thenThatEndpointSkippedAndOthersRegistered() {
        InvestTools investTools = mock(InvestTools.class);
        McpConfigRepository repository = mock(McpConfigRepository.class);
        McpClientPool clientPool = mock(McpClientPool.class);
        McpClientWrapper clientA = mock(McpClientWrapper.class);
        McpClientWrapper clientB = mock(McpClientWrapper.class);

        McpProvider provider = McpProvider.reconstitute(2L, "tushare", "Tushare", AuthType.BEARER, null, "token", true, null, NOW);
        McpEndpoint endpointA = McpEndpoint.reconstitute(3L, 2L, null, "Tushare 主端点", "https://a.tushare.pro/mcp/", true, NOW);
        McpEndpoint endpointB = McpEndpoint.reconstitute(4L, 2L, null, "Tushare 备端点", "https://b.tushare.pro/mcp/", true, NOW);
        McpUserConfig config = McpUserConfig.reconstitute(9L, 1L, 2L, true, List.of(), 1, NOW, NOW);

        when(repository.findEnabledProviders()).thenReturn(List.of(provider));
        when(repository.findByUserIdAndProviderId(1L, 2L)).thenReturn(Optional.of(config));
        when(repository.findEnabledEndpointsByProviderId(2L)).thenReturn(List.of(endpointA, endpointB));
        when(clientPool.acquire(provider, endpointA, "token")).thenReturn(clientA);
        when(clientPool.acquire(provider, endpointB, "token")).thenReturn(clientB);
        when(clientA.listTools()).thenReturn(Mono.error(new IllegalStateException("端点 A 不可用")));
        when(clientB.listTools()).thenReturn(Mono.just(List.of(tool("b_tool", annotations(true)))));
        when(clientB.getName()).thenReturn("tushare-b");

        Toolkit toolkit = new UserToolkitFactory(investTools, repository, clientPool, passthroughCodec(),
                mock(com.portfolio.invest.application.portfolio.PortfolioApplicationService.class),
                mock(com.portfolio.invest.application.allocation.AllocationApplicationService.class),
                mock(com.portfolio.invest.application.intelligence.IntelligenceQueryService.class),
                new com.fasterxml.jackson.databind.ObjectMapper(), TOOL_TIMEOUT).build(1L);

        assertThat(toolkit.getTool("b_tool")).as("正常端点 B 的工具照常注册（单点失败不拖垮装配）").isNotNull();
        assertThat(toolkit.getTool("search_stock")).as("内置工具不受影响").isNotNull();
    }

    @DisplayName("装配后内置工具含 5 新工具 + research_draft + search_news + search_announcements + macro_brief（无 MCP 环境共 16 个）")
    @Test
    void givenInvestToolsAndUserServices_whenBuild_thenRegistersUserTools() {
        InvestTools investTools = mock(InvestTools.class);
        McpConfigRepository repository = mock(McpConfigRepository.class);
        McpClientPool clientPool = mock(McpClientPool.class);
        when(repository.findEnabledProviders()).thenReturn(List.of());

        Toolkit toolkit = new UserToolkitFactory(investTools, repository, clientPool, passthroughCodec(),
                mock(com.portfolio.invest.application.portfolio.PortfolioApplicationService.class),
                mock(com.portfolio.invest.application.allocation.AllocationApplicationService.class),
                mock(com.portfolio.invest.application.intelligence.IntelligenceQueryService.class),
                new com.fasterxml.jackson.databind.ObjectMapper(), TOOL_TIMEOUT).build(1L);

        var names = toolkit.getToolNames();
        assertThat(names).contains("screen_stocks", "analyze_financials", "analyze_industry",
                "analyze_portfolio", "suggest_allocation", "research_draft", "search_news",
                "search_announcements", "macro_brief");
        assertThat(names).as("7 既有 + 5 新 + research_draft + search_news + search_announcements + macro_brief（inline mock 保留 @Tool 注解扫描）").hasSize(16);
    }

    @DisplayName("token 为 v1 密文 → 解密后的明文进入客户端装配")
    @Test
    void givenEncryptedSecret_whenBuild_thenAcquireWithDecryptedToken() {
        InvestTools investTools = mock(InvestTools.class);
        McpConfigRepository repository = mock(McpConfigRepository.class);
        McpClientPool clientPool = mock(McpClientPool.class);
        McpClientWrapper client = mock(McpClientWrapper.class);
        McpSecretCodec codec = mock(McpSecretCodec.class);

        McpProvider provider = McpProvider.reconstitute(2L, "tushare", "Tushare", AuthType.BEARER, null, "v1:Ag==", true, null, NOW);
        McpEndpoint endpoint = McpEndpoint.reconstitute(3L, 2L, null, "Tushare 数据", "https://api.tushare.pro/mcp/", true, NOW);
        McpUserConfig config = McpUserConfig.reconstitute(9L, 1L, 2L, true, List.of(), 1, NOW, NOW);

        when(repository.findEnabledProviders()).thenReturn(List.of(provider));
        when(repository.findByUserIdAndProviderId(1L, 2L)).thenReturn(Optional.of(config));
        when(repository.findEnabledEndpointsByProviderId(2L)).thenReturn(List.of(endpoint));
        when(codec.decrypt("v1:Ag==")).thenReturn("plain-token");
        // 装配必须拿解密后的明文（而非库内密文）——acquire 以 plain-token 打桩即证
        when(clientPool.acquire(provider, endpoint, "plain-token")).thenReturn(client);
        when(client.listTools()).thenReturn(Mono.just(List.of(tool("trade_cal", annotations(true)))));
        when(client.getName()).thenReturn("tushare");

        Toolkit toolkit = new UserToolkitFactory(investTools, repository, clientPool, codec,
                mock(com.portfolio.invest.application.portfolio.PortfolioApplicationService.class),
                mock(com.portfolio.invest.application.allocation.AllocationApplicationService.class),
                mock(com.portfolio.invest.application.intelligence.IntelligenceQueryService.class),
                new com.fasterxml.jackson.databind.ObjectMapper(), TOOL_TIMEOUT).build(1L);

        assertThat(toolkit.getTool("trade_cal")).as("密文经解密后装配成功").isNotNull();
    }

    @DisplayName("token 解密失败 → 该 provider 跳过，其余 provider 照常装配")
    @Test
    void givenDecryptFails_whenBuild_thenProviderSkippedAndOthersWork() {
        InvestTools investTools = mock(InvestTools.class);
        McpConfigRepository repository = mock(McpConfigRepository.class);
        McpClientPool clientPool = mock(McpClientPool.class);
        McpClientWrapper clientB = mock(McpClientWrapper.class);
        McpSecretCodec codec = mock(McpSecretCodec.class);

        McpProvider providerA = McpProvider.reconstitute(2L, "tushare", "Tushare", AuthType.BEARER, null, "v1:broken", true, null, NOW);
        McpEndpoint endpointA = McpEndpoint.reconstitute(3L, 2L, null, "Tushare 数据", "https://api.tushare.pro/mcp/", true, NOW);
        McpProvider providerB = McpProvider.reconstitute(5L, "miaoxiang", "妙想", AuthType.HEADER, "X-Api-Key", "plain-b", true, null, NOW);
        McpEndpoint endpointB = McpEndpoint.reconstitute(6L, 5L, null, "妙想数据", "https://mxapi.eastmoney.com/mxds/mcp", true, NOW);
        McpUserConfig configA = McpUserConfig.reconstitute(9L, 1L, 2L, true, List.of(), 1, NOW, NOW);
        McpUserConfig configB = McpUserConfig.reconstitute(10L, 1L, 5L, true, List.of(), 1, NOW, NOW);

        when(repository.findEnabledProviders()).thenReturn(List.of(providerA, providerB));
        when(repository.findByUserIdAndProviderId(1L, 2L)).thenReturn(Optional.of(configA));
        when(repository.findByUserIdAndProviderId(1L, 5L)).thenReturn(Optional.of(configB));
        when(repository.findEnabledEndpointsByProviderId(2L)).thenReturn(List.of(endpointA));
        when(repository.findEnabledEndpointsByProviderId(5L)).thenReturn(List.of(endpointB));
        when(codec.decrypt("v1:broken")).thenThrow(new McpException(McpErrorCode.SECRET_DECRYPT_FAILED, "token 解密失败（密钥不匹配或载荷损坏）"));
        when(codec.decrypt("plain-b")).thenReturn("plain-b");
        when(clientPool.acquire(providerB, endpointB, "plain-b")).thenReturn(clientB);
        when(clientB.listTools()).thenReturn(Mono.just(List.of(tool("mx_tool", annotations(true)))));
        when(clientB.getName()).thenReturn("miaoxiang");

        Toolkit toolkit = new UserToolkitFactory(investTools, repository, clientPool, codec,
                mock(com.portfolio.invest.application.portfolio.PortfolioApplicationService.class),
                mock(com.portfolio.invest.application.allocation.AllocationApplicationService.class),
                mock(com.portfolio.invest.application.intelligence.IntelligenceQueryService.class),
                new com.fasterxml.jackson.databind.ObjectMapper(), TOOL_TIMEOUT).build(1L);

        assertThat(toolkit.getTool("mx_tool")).as("解密失败只跳过故障 provider，其余照常").isNotNull();
        verify(clientPool, never()).acquire(org.mockito.ArgumentMatchers.eq(providerA),
                org.mockito.ArgumentMatchers.eq(endpointA), any());
    }

    @DisplayName("listTools 阻塞带 toolTimeout 上限（B3：死配置接线为真实生效）")
    @Test
    @SuppressWarnings("unchecked")
    void givenBuild_whenListTools_thenBlockedWithConfiguredTimeout() {
        InvestTools investTools = mock(InvestTools.class);
        McpConfigRepository repository = mock(McpConfigRepository.class);
        McpClientPool clientPool = mock(McpClientPool.class);
        McpClientWrapper client = mock(McpClientWrapper.class);
        Mono<List<McpSchema.Tool>> listTools = mock(Mono.class);

        McpProvider provider = McpProvider.reconstitute(2L, "tushare", "Tushare", AuthType.BEARER, null, "token", true, null, NOW);
        McpEndpoint endpoint = McpEndpoint.reconstitute(3L, 2L, null, "Tushare 数据", "https://api.tushare.pro/mcp/", true, NOW);
        McpUserConfig config = McpUserConfig.reconstitute(9L, 1L, 2L, true, List.of(), 1, NOW, NOW);

        when(repository.findEnabledProviders()).thenReturn(List.of(provider));
        when(repository.findByUserIdAndProviderId(1L, 2L)).thenReturn(Optional.of(config));
        when(repository.findEnabledEndpointsByProviderId(2L)).thenReturn(List.of(endpoint));
        when(clientPool.acquire(provider, endpoint, "token")).thenReturn(client);
        when(client.listTools()).thenReturn(listTools);
        when(listTools.block(TOOL_TIMEOUT)).thenReturn(List.of());
        when(client.getName()).thenReturn("tushare");

        new UserToolkitFactory(investTools, repository, clientPool, passthroughCodec(),
                mock(com.portfolio.invest.application.portfolio.PortfolioApplicationService.class),
                mock(com.portfolio.invest.application.allocation.AllocationApplicationService.class),
                mock(com.portfolio.invest.application.intelligence.IntelligenceQueryService.class),
                new com.fasterxml.jackson.databind.ObjectMapper(), TOOL_TIMEOUT).build(1L);

        verify(listTools).block(TOOL_TIMEOUT);
    }

    @DisplayName("listTools 超过 toolTimeout：端点被跳过（超时异常而非永久阻塞），其余端点照常")
    @Test
    void givenListToolsTimesOut_whenBuild_thenEndpointSkippedAndElapsedBounded() {
        InvestTools investTools = mock(InvestTools.class);
        McpConfigRepository repository = mock(McpConfigRepository.class);
        McpClientPool clientPool = mock(McpClientPool.class);
        McpClientWrapper clientA = mock(McpClientWrapper.class);
        McpClientWrapper clientB = mock(McpClientWrapper.class);

        McpProvider provider = McpProvider.reconstitute(2L, "tushare", "Tushare", AuthType.BEARER, null, "token", true, null, NOW);
        McpEndpoint endpointA = McpEndpoint.reconstitute(3L, 2L, null, "A", "https://a.tushare.pro/mcp/", true, NOW);
        McpEndpoint endpointB = McpEndpoint.reconstitute(4L, 2L, null, "B", "https://b.tushare.pro/mcp/", true, NOW);
        McpUserConfig config = McpUserConfig.reconstitute(9L, 1L, 2L, true, List.of(), 1, NOW, NOW);

        when(repository.findEnabledProviders()).thenReturn(List.of(provider));
        when(repository.findByUserIdAndProviderId(1L, 2L)).thenReturn(Optional.of(config));
        when(repository.findEnabledEndpointsByProviderId(2L)).thenReturn(List.of(endpointA, endpointB));
        when(clientPool.acquire(provider, endpointA, "token")).thenReturn(clientA);
        when(clientPool.acquire(provider, endpointB, "token")).thenReturn(clientB);
        // A 端点永久无响应：block(Duration) 到点必抛 IllegalStateException（真实 reactor 语义）
        when(clientA.listTools()).thenReturn(Mono.never());
        when(clientB.listTools()).thenReturn(Mono.just(List.of(tool("b_tool", annotations(true)))));
        when(clientB.getName()).thenReturn("tushare-b");

        long start = System.nanoTime();
        Toolkit toolkit = new UserToolkitFactory(investTools, repository, clientPool, passthroughCodec(),
                mock(com.portfolio.invest.application.portfolio.PortfolioApplicationService.class),
                mock(com.portfolio.invest.application.allocation.AllocationApplicationService.class),
                mock(com.portfolio.invest.application.intelligence.IntelligenceQueryService.class),
                new com.fasterxml.jackson.databind.ObjectMapper(), java.time.Duration.ofMillis(100)).build(1L);
        long elapsedMs = java.time.Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertThat(toolkit.getTool("b_tool")).as("超时端点被跳过，正常端点照常注册").isNotNull();
        assertThat(elapsedMs)
                .as("listTools 挂死端点须在 toolTimeout 内抛超时而非永久阻塞")
                .isLessThan(10_000);
    }

    // ———— MS-29 B3：真值捕获装饰器装配 ————

    @DisplayName("装配后内置与 MCP 工具均被装饰器同名覆盖注册（getTool 返回装饰器）")
    @Test
    void givenBuilt_whenGetTool_thenAllToolsDecorated() {
        Toolkit toolkit = buildToolkitWith(List.of(tool("trade_cal", annotations(true))), List.of());

        assertThat(toolkit.getTool("search_stock"))
                .as("内置工具被 RecordingAgentToolDecorator 包裹").isInstanceOf(RecordingAgentToolDecorator.class);
        assertThat(toolkit.getTool("analyze_portfolio"))
                .as("用户态工具同样被包裹").isInstanceOf(RecordingAgentToolDecorator.class);
        assertThat(toolkit.getTool("trade_cal"))
                .as("MCP 工具同样被包裹").isInstanceOf(RecordingAgentToolDecorator.class);
        assertThat(toolkit.getTool("search_stock").isReadOnly())
                .as("装饰器透传只读语义（委托面不变形）").isTrue();
        assertThat(toolkit.getTool("trade_cal").isReadOnly()).isTrue();
    }

    @DisplayName("MCP 工具经装饰器调用：真值池记录 sourced 语义（asOfKind=CALL 恒定，结果含 time 亦然）")
    @Test
    void givenMcpToolCall_whenDecorated_thenRecordedAsSourcedCall() {
        McpClientWrapper client = mock(McpClientWrapper.class);
        McpProvider provider = McpProvider.reconstitute(2L, "tushare", "Tushare", AuthType.BEARER, null, "token", true, null, NOW);
        McpEndpoint endpoint = McpEndpoint.reconstitute(3L, 2L, null, "Tushare 数据", "https://api.tushare.pro/mcp/", true, NOW);
        McpUserConfig config = McpUserConfig.reconstitute(9L, 1L, 2L, true, List.of(), 1, NOW, NOW);
        McpConfigRepository repository = mock(McpConfigRepository.class);
        McpClientPool clientPool = mock(McpClientPool.class);
        InvestTools investTools = mock(InvestTools.class);

        when(repository.findEnabledProviders()).thenReturn(List.of(provider));
        when(repository.findByUserIdAndProviderId(1L, 2L)).thenReturn(Optional.of(config));
        when(repository.findEnabledEndpointsByProviderId(2L)).thenReturn(List.of(endpoint));
        when(clientPool.acquire(provider, endpoint, "token")).thenReturn(client);
        when(client.listTools()).thenReturn(Mono.just(List.of(tool("trade_cal", annotations(true)))));
        when(client.getName()).thenReturn("tushare");
        when(client.callTool(org.mockito.ArgumentMatchers.eq("trade_cal"), any(), any()))
                .thenReturn(Mono.just(new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("{\"time\":\"09:31:00\",\"close\":3900.5}")), false)));

        Toolkit toolkit = new UserToolkitFactory(investTools, repository, clientPool, passthroughCodec(),
                mock(com.portfolio.invest.application.portfolio.PortfolioApplicationService.class),
                mock(com.portfolio.invest.application.allocation.AllocationApplicationService.class),
                mock(com.portfolio.invest.application.intelligence.IntelligenceQueryService.class),
                new com.fasterxml.jackson.databind.ObjectMapper(), TOOL_TIMEOUT).build(1L);

        AgentTool tradeCal = toolkit.getTool("trade_cal");
        assertThat(tradeCal).isInstanceOf(RecordingAgentToolDecorator.class);
        RuntimeContext rc = RuntimeContext.empty();
        Map<String, Object> input = Map.of("exchange", "SSE");
        ToolUseBlock use = new ToolUseBlock("call_tc", "trade_cal", input,
                new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(input).toString(), Map.of());
        tradeCal.callAsync(ToolCallParam.builder()
                .toolUseBlock(use).input(input).runtimeContext(rc).build()).block();

        var pool = TrustContext.current(rc).invocations();
        assertThat(pool).hasSize(1);
        ToolInvocation invocation = pool.get(0);
        assertThat(invocation.toolName()).isEqualTo("trade_cal");
        assertThat(invocation.args()).containsEntry("exchange", "SSE");
        assertThat(invocation.resultText()).contains("3900.5");
        assertThat(invocation.asOfKind())
                .as("MCP 恒 sourced（asOfKind=CALL，决策 #5：不入比对池），结果含 time 亦不升 DATA")
                .isEqualTo(ToolInvocation.AsOfKind.CALL);
        assertThat(invocation.failed()).isFalse();
    }
}
