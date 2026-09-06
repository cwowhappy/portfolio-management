package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.mcp.AuthType;
import com.portfolio.invest.domain.mcp.McpProvider;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "mcp_provider")
public class McpProviderJpaEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 32)
    private String code;
    @Column(nullable = false, length = 64)
    private String name;
    @Enumerated(EnumType.STRING)
    @Column(name = "auth_type", nullable = false, length = 16)
    private AuthType authType;
    @Column(name = "auth_header", length = 64)
    private String authHeader;
    @Column(name = "auth_secret_enc", columnDefinition = "text")
    private String authSecretEnc;
    @Column(nullable = false)
    private boolean enabled;
    @Column(length = 255)
    private String remark;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected McpProviderJpaEntity() {}

    public static McpProviderJpaEntity fromDomain(McpProvider p) {
        McpProviderJpaEntity e = new McpProviderJpaEntity();
        e.id = p.id(); e.code = p.code(); e.name = p.name(); e.authType = p.authType();
        e.authHeader = p.authHeader(); e.authSecretEnc = p.authSecretEnc(); e.enabled = p.enabled();
        e.remark = p.remark(); e.createdAt = p.createdAt();
        return e;
    }

    public McpProvider toDomain() {
        return McpProvider.reconstitute(id, code, name, authType, authHeader, authSecretEnc, enabled, remark, createdAt);
    }
}
