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

        return new UserToolkitFactory(investTools, repository, clientPool).build(1L);
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
}
