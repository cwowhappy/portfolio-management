package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.mcp.McpEndpoint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "mcp_endpoint")
public class McpEndpointJpaEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "provider_id", nullable = false)
    private Long providerId;
    @Column(length = 32)
    private String domain;
    @Column(nullable = false, length = 64)
    private String name;
    @Column(nullable = false, length = 512)
    private String url;
    @Column(nullable = false)
    private boolean enabled;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected McpEndpointJpaEntity() {}

    public static McpEndpointJpaEntity fromDomain(McpEndpoint d) {
        McpEndpointJpaEntity e = new McpEndpointJpaEntity();
        e.id = d.id(); e.providerId = d.providerId(); e.domain = d.domain(); e.name = d.name();
        e.url = d.url(); e.enabled = d.enabled(); e.createdAt = d.createdAt();
        return e;
    }

    public McpEndpoint toDomain() {
        return McpEndpoint.reconstitute(id, providerId, domain, name, url, enabled, createdAt);
    }
}
