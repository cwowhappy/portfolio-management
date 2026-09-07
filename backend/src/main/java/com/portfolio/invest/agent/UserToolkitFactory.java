package com.portfolio.invest.agent;

import com.portfolio.invest.domain.mcp.*;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.core.tool.mcp.McpTool;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class UserToolkitFactory {
    private static final Logger log = LoggerFactory.getLogger(UserToolkitFactory.class);
    private final InvestTools investTools;
    private final McpConfigRepository repository;
    private final McpClientPool clientPool;

    public UserToolkitFactory(InvestTools investTools, McpConfigRepository repository, McpClientPool clientPool) {
        this.investTools = investTools;
        this.repository = repository;
        this.clientPool = clientPool;
    }

    public Toolkit build(Long userId) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(investTools);
        Set<String> names = new HashSet<>();
        for (McpProvider provider : repository.findEnabledProviders()) {
            McpUserConfig config = repository.findByUserIdAndProviderId(userId, provider.id()).orElse(null);
            if (config == null || !config.enabled()) continue;
            if (provider.authType() != AuthType.NONE && (provider.authSecretEnc() == null || provider.authSecretEnc().isBlank())) continue;
            String token = provider.authSecretEnc();
            for (McpEndpoint endpoint : repository.findEnabledEndpointsByProviderId(provider.id())) {
                try {
                    McpClientWrapper client = clientPool.acquire(provider, endpoint, token);
                    for (McpSchema.Tool t : client.listTools().block()) {
                        String name = t.name();
                        if (config.disabledTools().contains(name) || names.contains(name)) continue;
                        names.add(name);
                        toolkit.registerAgentTool(mcpTool(t, client));
                    }
                } catch (Exception e) {
                    log.warn("MCP 端点 {} 装配失败，跳过：{}", endpoint.name(), e.getMessage());
                }
            }
        }
        return toolkit;
    }

    /**
     * 手动构造 McpTool，readOnly 按 MCP 规范判定（FR-1）：readOnlyHint=true 只读放行；
     * 缺省或 false 视为写，触发权限审批（宁多问不漏问，治理路径见 ADR-0010）。
     * 判定式与上游 McpClientManager（agentscope 2.0.3）逐字一致——上游为包私有，只能复制。
     */
    private static McpTool mcpTool(McpSchema.Tool t, McpClientWrapper client) {
        boolean readOnly =
                t.annotations() != null && Boolean.TRUE.equals(t.annotations().readOnlyHint());
        Map<String, Object> params = McpTool.convertMcpSchemaToParameters(t.inputSchema(), Set.of());
        return new McpTool(
                t.name(),
                t.description() != null ? t.description() : "",
                params,
                t.outputSchema() != null ? new ConcurrentHashMap<>(t.outputSchema()) : null,
                client,
                null,
                client.getName(),
                readOnly);
    }
}
