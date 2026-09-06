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
}
