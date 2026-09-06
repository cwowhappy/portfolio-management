package com.portfolio.invest.agent;

import com.portfolio.invest.domain.mcp.*;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
                    List<String> toDisable = new ArrayList<>();
                    for (McpSchema.Tool t : client.listTools().block()) {
                        if (config.disabledTools().contains(t.name()) || names.contains(t.name())) toDisable.add(t.name());
                        else names.add(t.name());
                    }
                    toolkit.registration().mcpClient(client).disableTools(toDisable).apply();
                } catch (Exception e) {
                    log.warn("MCP 端点 {} 装配失败，跳过：{}", endpoint.name(), e.getMessage());
                }
            }
        }
        return toolkit;
    }
}
