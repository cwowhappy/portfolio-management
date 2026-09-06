package com.portfolio.invest.domain.mcp;

import java.time.Instant;
import java.util.List;

/** 用户对某 provider 的配置：不可变，变更 update 返回新实例（config_version 自增）。无 Token 字段。 */
public final class McpUserConfig {
    private final Long id;
    private final Long userId;
    private final Long providerId;
    private final boolean enabled;
    private final List<String> disabledTools;
    private final int configVersion;
    private final Instant createdAt;
    private final Instant updatedAt;

    private McpUserConfig(Long id, Long userId, Long providerId, boolean enabled,
                          List<String> disabledTools, int configVersion, Instant createdAt, Instant updatedAt) {
        this.id = id; this.userId = userId; this.providerId = providerId; this.enabled = enabled;
        this.disabledTools = disabledTools; this.configVersion = configVersion;
        this.createdAt = createdAt; this.updatedAt = updatedAt;
    }

    public static McpUserConfig create(Long userId, Long providerId, List<String> disabledTools, Instant now) {
        if (userId == null || providerId == null) {
            throw new McpException(McpErrorCode.INVALID_INPUT, "用户与数据源不能为空");
        }
        if (disabledTools == null) {
            throw new McpException(McpErrorCode.INVALID_INPUT, "禁用工具清单不能为空");
        }
        return new McpUserConfig(null, userId, providerId, true, List.copyOf(disabledTools), 1, now, now);
    }

    public static McpUserConfig reconstitute(Long id, Long userId, Long providerId, boolean enabled,
                                             List<String> disabledTools, int configVersion,
                                             Instant createdAt, Instant updatedAt) {
        return new McpUserConfig(id, userId, providerId, enabled,
                disabledTools == null ? List.of() : List.copyOf(disabledTools),
                configVersion, createdAt, updatedAt);
    }

    public McpUserConfig update(boolean enabled, List<String> disabledTools, Instant now) {
        if (disabledTools == null) {
            throw new McpException(McpErrorCode.INVALID_INPUT, "禁用工具清单不能为空");
        }
        return new McpUserConfig(id, userId, providerId, enabled, List.copyOf(disabledTools),
                configVersion + 1, createdAt, now);
    }

    public Long id() { return id; }
    public Long userId() { return userId; }
    public Long providerId() { return providerId; }
    public boolean enabled() { return enabled; }
    public List<String> disabledTools() { return disabledTools; }
    public int configVersion() { return configVersion; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
}
