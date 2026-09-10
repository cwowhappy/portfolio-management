package com.portfolio.invest.infrastructure.seed;

import com.portfolio.invest.domain.mcp.AuthType;
import com.portfolio.invest.domain.mcp.McpConfigRepository;
import com.portfolio.invest.domain.mcp.McpProvider;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * e2e 专用 MCP provider 种子：E2E_HITL_MCP_URL 已配置时幂等写入 hitl-e2e provider（NONE 鉴权）
 * 与指向该 URL 的 endpoint，供 Playwright 真实浏览器用例触发写工具审批（issue #25 回归钉）。
 * 未配置时零副作用（与 AdminSeedRunner 同型的启动幂等种子）。
 */
@Component
public class HitlE2eSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(HitlE2eSeedRunner.class);
    static final String CODE = "hitl-e2e";

    private final McpConfigRepository repository;
    private final String mcpUrl;

    public HitlE2eSeedRunner(McpConfigRepository repository,
                             @Value("${E2E_HITL_MCP_URL:}") String mcpUrl) {
        this.repository = repository;
        this.mcpUrl = (mcpUrl == null || mcpUrl.isBlank()) ? null : mcpUrl;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (mcpUrl == null) return;
        Optional<McpProvider> existing = repository.findProviderByCode(CODE);
        Long providerId;
        if (existing.isPresent()) {
            providerId = existing.orElseThrow().id();
        } else {
            repository.upsertSeedProvider(McpProvider.reconstitute(
                    null, CODE, "HITL e2e", AuthType.NONE, null, null, true, "Playwright e2e 专用", Instant.now()));
            providerId = repository.findProviderByCode(CODE).orElseThrow().id();
            log.info("已种子 e2e MCP provider {} -> {}", CODE, mcpUrl);
        }
        repository.upsertSeedEndpoint(providerId, mcpUrl);
    }
}
