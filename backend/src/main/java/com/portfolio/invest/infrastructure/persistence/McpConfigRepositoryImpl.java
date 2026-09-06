package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.mcp.McpConfigRepository;
import com.portfolio.invest.domain.mcp.McpEndpoint;
import com.portfolio.invest.domain.mcp.McpProvider;
import com.portfolio.invest.domain.mcp.McpUserConfig;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class McpConfigRepositoryImpl implements McpConfigRepository {
    private final McpProviderJpaRepository providerJpa;
    private final McpEndpointJpaRepository endpointJpa;
    private final McpUserConfigJpaRepository configJpa;

    public McpConfigRepositoryImpl(McpProviderJpaRepository providerJpa,
                                   McpEndpointJpaRepository endpointJpa,
                                   McpUserConfigJpaRepository configJpa) {
        this.providerJpa = providerJpa;
        this.endpointJpa = endpointJpa;
        this.configJpa = configJpa;
    }

    @Override public List<McpProvider> findEnabledProviders() {
        return providerJpa.findByEnabledTrueOrderByIdAsc().stream().map(McpProviderJpaEntity::toDomain).toList();
    }
    @Override public Optional<McpProvider> findProviderById(Long providerId) {
        return providerJpa.findById(providerId).map(McpProviderJpaEntity::toDomain);
    }
    @Override public List<McpEndpoint> findEnabledEndpointsByProviderId(Long providerId) {
        return endpointJpa.findByProviderIdAndEnabledTrueOrderByIdAsc(providerId).stream()
                .map(McpEndpointJpaEntity::toDomain).toList();
    }
    @Override public List<McpUserConfig> findByUserId(Long userId) {
        return configJpa.findByUserId(userId).stream().map(McpUserConfigJpaEntity::toDomain).toList();
    }
    @Override public Optional<McpUserConfig> findByUserIdAndProviderId(Long userId, Long providerId) {
        return configJpa.findByUserIdAndProviderId(userId, providerId).map(McpUserConfigJpaEntity::toDomain);
    }
    @Override public McpUserConfig save(McpUserConfig config) {
        return configJpa.save(McpUserConfigJpaEntity.fromDomain(config)).toDomain();
    }
    @Override public void deleteByUserIdAndProviderId(Long userId, Long providerId) {
        configJpa.deleteByUserIdAndProviderId(userId, providerId);
    }
}
