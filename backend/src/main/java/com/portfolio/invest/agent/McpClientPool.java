package com.portfolio.invest.agent;

import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.mcp.AuthType;
import com.portfolio.invest.domain.mcp.McpEndpoint;
import com.portfolio.invest.domain.mcp.McpProvider;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class McpClientPool {
    private final Map<Long, McpClientWrapper> clients = new ConcurrentHashMap<>();
    private final Duration connectTimeout;

    public McpClientPool(InvestProperties props) {
        this.connectTimeout = props.getMcp().getConnectTimeout();
    }

    public McpClientWrapper acquire(McpProvider provider, McpEndpoint endpoint, String token) {
        return clients.computeIfAbsent(endpoint.id(), k -> build(provider, endpoint, token));
    }

    private McpClientWrapper build(McpProvider provider, McpEndpoint endpoint, String token) {
        McpClientBuilder builder = McpClientBuilder.create(provider.code())
                .streamableHttpTransport(endpoint.url()).timeout(connectTimeout);
        if (provider.authType() == AuthType.HEADER) {
            builder.header(provider.authHeader(), token);
        } else if (provider.authType() == AuthType.BEARER) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder.buildSync();
    }
}
