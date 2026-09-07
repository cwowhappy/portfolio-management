package com.portfolio.invest.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
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

/** 用户工具装配：MCP 工具须以 readOnly=true 注册（数据源工具未标 readOnlyHint，但本系统只接只读数据源）。 */
class UserToolkitFactoryTest {

    private static final Instant NOW = Instant.parse("2026-09-07T08:00:00Z");

    @DisplayName("MCP 工具装配后 readOnly=true")
    @Test
    void givenMcpTools_whenBuild_thenToolsAreReadOnly() {
        InvestTools investTools = mock(InvestTools.class);
        McpConfigRepository repository = mock(McpConfigRepository.class);
        McpClientPool clientPool = mock(McpClientPool.class);
        McpClientWrapper client = mock(McpClientWrapper.class);

        McpProvider provider = McpProvider.reconstitute(2L, "tushare", "Tushare", AuthType.BEARER, null, "token", true, null, NOW);
        McpEndpoint endpoint = McpEndpoint.reconstitute(3L, 2L, null, "Tushare 数据", "https://api.tushare.pro/mcp/", true, NOW);
        McpUserConfig config = McpUserConfig.reconstitute(9L, 1L, 2L, true, List.of(), 1, NOW, NOW);

        McpSchema.Tool tool = mock(McpSchema.Tool.class);
        when(tool.name()).thenReturn("trade_cal");
        when(tool.description()).thenReturn("获取交易日历");
        when(tool.inputSchema()).thenReturn(null);
        when(tool.outputSchema()).thenReturn(null);

        when(repository.findEnabledProviders()).thenReturn(List.of(provider));
        when(repository.findByUserIdAndProviderId(1L, 2L)).thenReturn(Optional.of(config));
        when(repository.findEnabledEndpointsByProviderId(2L)).thenReturn(List.of(endpoint));
        when(clientPool.acquire(provider, endpoint, "token")).thenReturn(client);
        when(client.listTools()).thenReturn(Mono.just(List.of(tool)));
        when(client.getName()).thenReturn("tushare");

        UserToolkitFactory factory = new UserToolkitFactory(investTools, repository, clientPool);
        Toolkit toolkit = factory.build(1L);

        assertThat(toolkit.getTool("trade_cal")).isNotNull();
        assertThat(toolkit.getTool("trade_cal").isReadOnly()).isTrue();
    }

    @DisplayName("被禁用的 MCP 工具不注册")
    @Test
    void givenDisabledTool_whenBuild_thenToolSkipped() {
        InvestTools investTools = mock(InvestTools.class);
        McpConfigRepository repository = mock(McpConfigRepository.class);
        McpClientPool clientPool = mock(McpClientPool.class);
        McpClientWrapper client = mock(McpClientWrapper.class);

        McpProvider provider = McpProvider.reconstitute(2L, "tushare", "Tushare", AuthType.BEARER, null, "token", true, null, NOW);
        McpEndpoint endpoint = McpEndpoint.reconstitute(3L, 2L, null, "Tushare 数据", "https://api.tushare.pro/mcp/", true, NOW);
        McpUserConfig config = McpUserConfig.reconstitute(9L, 1L, 2L, true, List.of("trade_cal"), 1, NOW, NOW);

        McpSchema.Tool tool = mock(McpSchema.Tool.class);
        when(tool.name()).thenReturn("trade_cal");
        when(tool.description()).thenReturn("获取交易日历");
        when(tool.inputSchema()).thenReturn(null);
        when(tool.outputSchema()).thenReturn(null);

        when(repository.findEnabledProviders()).thenReturn(List.of(provider));
        when(repository.findByUserIdAndProviderId(1L, 2L)).thenReturn(Optional.of(config));
        when(repository.findEnabledEndpointsByProviderId(2L)).thenReturn(List.of(endpoint));
        when(clientPool.acquire(provider, endpoint, "token")).thenReturn(client);
        when(client.listTools()).thenReturn(Mono.just(List.of(tool)));

        UserToolkitFactory factory = new UserToolkitFactory(investTools, repository, clientPool);
        Toolkit toolkit = factory.build(1L);

        assertThat(toolkit.getTool("trade_cal")).isNull();
    }
}
