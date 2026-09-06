package com.portfolio.invest.domain.mcp;

import java.time.Instant;

/** provider 端点：一个 provider 下 1..N 个 domain（Wind 6 域；单端点 provider domain=NULL）。 */
public final class McpEndpoint {
    private final Long id;
    private final Long providerId;
    private final String domain;
    private final String name;
    private final String url;
    private final boolean enabled;
    private final Instant createdAt;

    private McpEndpoint(Long id, Long providerId, String domain, String name, String url,
                        boolean enabled, Instant createdAt) {
        this.id = id; this.providerId = providerId; this.domain = domain; this.name = name;
        this.url = url; this.enabled = enabled; this.createdAt = createdAt;
    }

    public static McpEndpoint reconstitute(Long id, Long providerId, String domain, String name,
                                           String url, boolean enabled, Instant createdAt) {
        return new McpEndpoint(id, providerId, domain, name, url, enabled, createdAt);
    }

    public Long id() { return id; }
    public Long providerId() { return providerId; }
    public String domain() { return domain; }
    public String name() { return name; }
    public String url() { return url; }
    public boolean enabled() { return enabled; }
    public Instant createdAt() { return createdAt; }
}
