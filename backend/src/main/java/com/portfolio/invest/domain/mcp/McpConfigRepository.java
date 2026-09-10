package com.portfolio.invest.domain.mcp;

import java.util.List;
import java.util.Optional;

public interface McpConfigRepository {
    List<McpProvider> findEnabledProviders();
    Optional<McpProvider> findProviderById(Long providerId);
    List<McpEndpoint> findEnabledEndpointsByProviderId(Long providerId);

    List<McpUserConfig> findByUserId(Long userId);
    Optional<McpUserConfig> findByUserIdAndProviderId(Long userId, Long providerId);
    McpUserConfig save(McpUserConfig config);
    void deleteByUserIdAndProviderId(Long userId, Long providerId);

    // e2e 种子用（HitlE2eSeedRunner，env 门控）：按自然键幂等
    Optional<McpProvider> findProviderByCode(String code);
    void upsertSeedProvider(McpProvider provider);
    void upsertSeedEndpoint(Long providerId, String url);
}
