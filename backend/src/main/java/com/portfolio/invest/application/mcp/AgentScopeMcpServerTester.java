package com.portfolio.invest.application.mcp;

import com.portfolio.invest.domain.mcp.AuthType;
import com.portfolio.invest.domain.mcp.McpEndpoint;
import com.portfolio.invest.domain.mcp.McpErrorCode;
import com.portfolio.invest.domain.mcp.McpException;
import com.portfolio.invest.domain.mcp.McpProvider;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class AgentScopeMcpServerTester implements McpServerTester {

    @Override
    public List<McpToolDescriptor> testConnection(McpProvider provider, McpEndpoint endpoint, String token) {
        try {
            McpClientBuilder builder = McpClientBuilder.create(provider.code())
                    .streamableHttpTransport(endpoint.url())
                    .timeout(Duration.ofSeconds(10));
            switch (provider.authType()) {
                case HEADER -> builder.header(provider.authHeader(), token);
                case BEARER -> builder.header("Authorization", "Bearer " + token);
                default -> { }
            }
            McpClientWrapper client = builder.buildSync();
            List<McpToolDescriptor> tools = new ArrayList<>();
            for (var t : client.listTools().block()) {
                tools.add(new McpToolDescriptor(t.name(), t.description() != null ? t.description() : ""));
            }
            return tools;
        } catch (Exception e) {
            throw new McpException(McpErrorCode.CONNECTION_FAILED, "连接失败：" + e.getMessage());
        }
    }
}
