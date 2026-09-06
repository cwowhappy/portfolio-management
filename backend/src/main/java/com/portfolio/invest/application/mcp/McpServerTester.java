package com.portfolio.invest.application.mcp;

import com.portfolio.invest.domain.mcp.McpEndpoint;
import com.portfolio.invest.domain.mcp.McpProvider;
import java.util.List;

/** MCP 连接测试端口：真实握手并返回工具清单。 */
public interface McpServerTester {
    List<McpToolDescriptor> testConnection(McpProvider provider, McpEndpoint endpoint, String token);
}
