package com.portfolio.invest.domain.mcp;

import java.time.Instant;

/** 数据源 provider：鉴权/Token 维度，系统全局 Token（一期 seed 明文）。 */
public final class McpProvider {
    private final Long id;
    private final String code;
    private final String name;
    private final AuthType authType;
    private final String authHeader;
    private final String authSecretEnc;
    private final boolean enabled;
    private final String remark;
    private final Instant createdAt;

    private McpProvider(Long id, String code, String name, AuthType authType, String authHeader,
                        String authSecretEnc, boolean enabled, String remark, Instant createdAt) {
        this.id = id; this.code = code; this.name = name; this.authType = authType;
        this.authHeader = authHeader; this.authSecretEnc = authSecretEnc;
        this.enabled = enabled; this.remark = remark; this.createdAt = createdAt;
    }

    public static McpProvider reconstitute(Long id, String code, String name, AuthType authType,
                                           String authHeader, String authSecretEnc, boolean enabled,
                                           String remark, Instant createdAt) {
        return new McpProvider(id, code, name, authType, authHeader, authSecretEnc, enabled, remark, createdAt);
    }

    public Long id() { return id; }
    public String code() { return code; }
    public String name() { return name; }
    public AuthType authType() { return authType; }
    public String authHeader() { return authHeader; }
    public String authSecretEnc() { return authSecretEnc; }
    public boolean enabled() { return enabled; }
    public String remark() { return remark; }
    public Instant createdAt() { return createdAt; }
}
