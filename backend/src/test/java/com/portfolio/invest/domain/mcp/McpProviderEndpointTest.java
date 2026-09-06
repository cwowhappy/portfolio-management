package com.portfolio.invest.domain.mcp;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

class McpProviderEndpointTest {
    private static final Instant NOW = Instant.parse("2026-09-06T08:00:00Z");

    private static McpProvider wind() {
        return McpProvider.reconstitute(3L, "wind", "Wind AIFin", AuthType.BEARER, null, "ak-secret", true, null, NOW);
    }

    @Test
    void provider重建保留字段() {
        var p = wind();
        assertThat(p.code()).isEqualTo("wind");
        assertThat(p.authType()).isEqualTo(AuthType.BEARER);
        assertThat(p.authSecretEnc()).isEqualTo("ak-secret");
    }

    @Test
    void endpoint重建保留字段() {
        var e = McpEndpoint.reconstitute(5L, 3L, "stock", "Wind 股票",
                "https://mcp.wind.com.cn/vserver_stock_data/mcp/", true, NOW);
        assertThat(e.providerId()).isEqualTo(3L);
        assertThat(e.domain()).isEqualTo("stock");
        assertThat(e.url()).isEqualTo("https://mcp.wind.com.cn/vserver_stock_data/mcp/");
    }
}
