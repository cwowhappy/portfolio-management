package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.mcp.McpUserConfig;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "mcp_user_config")
public class McpUserConfigJpaEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(name = "provider_id", nullable = false)
    private Long providerId;
    @Column(nullable = false)
    private boolean enabled;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "disabled_tools", nullable = false, columnDefinition = "jsonb")
    private List<String> disabledTools;
    @Column(name = "config_version", nullable = false)
    private int configVersion;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected McpUserConfigJpaEntity() {}

    public static McpUserConfigJpaEntity fromDomain(McpUserConfig c) {
        McpUserConfigJpaEntity e = new McpUserConfigJpaEntity();
        e.id = c.id(); e.userId = c.userId(); e.providerId = c.providerId(); e.enabled = c.enabled();
        e.disabledTools = c.disabledTools(); e.configVersion = c.configVersion();
        e.createdAt = c.createdAt(); e.updatedAt = c.updatedAt();
        return e;
    }

    public McpUserConfig toDomain() {
        return McpUserConfig.reconstitute(id, userId, providerId, enabled, disabledTools, configVersion, createdAt, updatedAt);
    }
}
