package com.portfolio.invest.application.mcp;

import com.portfolio.invest.domain.mcp.McpUserConfig;
import java.util.List;

/** 用户对某 provider 的配置视图。 */
public record McpConfigView(Long providerId, boolean enabled, List<String> disabledTools, int configVersion) {
    public static McpConfigView from(McpUserConfig c) {
        return new McpConfigView(c.providerId(), c.enabled(), c.disabledTools(), c.configVersion());
    }
}
