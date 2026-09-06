package com.portfolio.invest.application.mcp;

import com.portfolio.invest.domain.mcp.AuthType;
import com.portfolio.invest.domain.mcp.McpProvider;
import java.util.List;

/** provider 视图：鉴权维度 + 其下已启用端点的 domain 列表。 */
public record McpProviderView(Long id, String code, String name, AuthType authType, String authHeader, List<String> domains) {
    public static McpProviderView from(McpProvider p, List<String> domains) {
        return new McpProviderView(p.id(), p.code(), p.name(), p.authType(), p.authHeader(), domains);
    }
}
