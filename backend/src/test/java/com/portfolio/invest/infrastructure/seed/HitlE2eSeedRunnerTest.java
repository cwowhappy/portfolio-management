package com.portfolio.invest.infrastructure.seed;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.invest.domain.mcp.AuthType;
import com.portfolio.invest.domain.mcp.McpConfigRepository;
import com.portfolio.invest.domain.mcp.McpProvider;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HitlE2eSeedRunnerTest {

    @DisplayName("未配置 E2E_HITL_MCP_URL 时零副作用")
    @Test
    void givenEnvUrlMissing_whenRun_thenNoSeed() throws Exception {
        McpConfigRepository repo = mock(McpConfigRepository.class);
        new HitlE2eSeedRunner(repo, null).run(null);
        verify(repo, never()).upsertSeedProvider(any());
        verify(repo, never()).upsertSeedEndpoint(anyLong(), anyString());
    }

    @DisplayName("已配置且 provider 已存在时只补 endpoint（幂等）")
    @Test
    void givenEnvUrlSet_whenRun_thenIdempotentSeed() throws Exception {
        McpConfigRepository repo = mock(McpConfigRepository.class);
        when(repo.findProviderByCode("hitl-e2e"))
                .thenReturn(Optional.of(McpProvider.reconstitute(
                        99L, "hitl-e2e", "HITL e2e", AuthType.NONE, null, null, true, null, null)));
        new HitlE2eSeedRunner(repo, "http://127.0.0.1:8765/mcp").run(null);
        verify(repo).upsertSeedEndpoint(99L, "http://127.0.0.1:8765/mcp");
        verify(repo, never()).upsertSeedProvider(any());
    }
}
