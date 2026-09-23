package com.portfolio.invest.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.portfolio.invest.domain.mcp.AuthType;
import com.portfolio.invest.domain.mcp.McpConfigRepository;
import com.portfolio.invest.domain.mcp.McpEndpoint;
import com.portfolio.invest.domain.mcp.McpProvider;
import com.portfolio.invest.domain.mcp.McpUserConfig;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/** 用户工具装配：MCP 工具 readOnly 按 MCP 规范判定——readOnlyHint=true 放行，缺省/false 视为写（FR-1）。 */
class UserToolkitFactoryTest {

    private static final Instant NOW = Instant.parse("2026-09-07T08:00:00Z");

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

        return new UserToolkitFactory(investTools, repository, clientPool,
                mock(com.portfolio.invest.application.portfolio.PortfolioApplicationService.class),
                mock(com.portfolio.invest.application.allocation.AllocationApplicationService.class),
                new com.fasterxml.jackson.databind.ObjectMapper()).build(1L);
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

        Toolkit toolkit = new UserToolkitFactory(investTools, repository, clientPool,
                mock(com.portfolio.invest.application.portfolio.PortfolioApplicationService.class),
                mock(com.portfolio.invest.application.allocation.AllocationApplicationService.class),
                new com.fasterxml.jackson.databind.ObjectMapper()).build(1L);

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

        Toolkit toolkit = new UserToolkitFactory(investTools, repository, clientPool,
                mock(com.portfolio.invest.application.portfolio.PortfolioApplicationService.class),
                mock(com.portfolio.invest.application.allocation.AllocationApplicationService.class),
                new com.fasterxml.jackson.databind.ObjectMapper()).build(1L);

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

        Toolkit toolkit = new UserToolkitFactory(investTools, repository, clientPool,
                mock(com.portfolio.invest.application.portfolio.PortfolioApplicationService.class),
                mock(com.portfolio.invest.application.allocation.AllocationApplicationService.class),
                new com.fasterxml.jackson.databind.ObjectMapper()).build(1L);

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

        Toolkit toolkit = new UserToolkitFactory(investTools, repository, clientPool,
                mock(com.portfolio.invest.application.portfolio.PortfolioApplicationService.class),
                mock(com.portfolio.invest.application.allocation.AllocationApplicationService.class),
                new com.fasterxml.jackson.databind.ObjectMapper()).build(1L);

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

        Toolkit toolkit = new UserToolkitFactory(investTools, repository, clientPool,
                mock(com.portfolio.invest.application.portfolio.PortfolioApplicationService.class),
                mock(com.portfolio.invest.application.allocation.AllocationApplicationService.class),
                new com.fasterxml.jackson.databind.ObjectMapper()).build(1L);

        assertThat(toolkit.getTool("b_tool")).as("正常端点 B 的工具照常注册（单点失败不拖垮装配）").isNotNull();
        assertThat(toolkit.getTool("search_stock")).as("内置工具不受影响").isNotNull();
    }

    @DisplayName("装配后内置工具含 5 新工具（无 MCP 环境共 12 个）")
    @Test
    void givenInvestToolsAndUserServices_whenBuild_thenRegistersUserTools() {
        InvestTools investTools = mock(InvestTools.class);
        McpConfigRepository repository = mock(McpConfigRepository.class);
        McpClientPool clientPool = mock(McpClientPool.class);
        when(repository.findEnabledProviders()).thenReturn(List.of());

        Toolkit toolkit = new UserToolkitFactory(investTools, repository, clientPool,
                mock(com.portfolio.invest.application.portfolio.PortfolioApplicationService.class),
                mock(com.portfolio.invest.application.allocation.AllocationApplicationService.class),
                new com.fasterxml.jackson.databind.ObjectMapper()).build(1L);

        var names = toolkit.getToolNames();
        assertThat(names).contains("screen_stocks", "analyze_financials", "analyze_industry",
                "analyze_portfolio", "suggest_allocation");
        assertThat(names).as("7 既有 + 5 新（inline mock 保留 @Tool 注解扫描）").hasSize(12);
    }
}
