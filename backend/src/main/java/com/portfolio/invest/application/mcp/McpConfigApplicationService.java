package com.portfolio.invest.application.mcp;

import com.portfolio.invest.domain.mcp.McpConfigRepository;
import com.portfolio.invest.domain.mcp.McpEndpoint;
import com.portfolio.invest.domain.mcp.McpErrorCode;
import com.portfolio.invest.domain.mcp.McpException;
import com.portfolio.invest.domain.mcp.McpProvider;
import com.portfolio.invest.domain.mcp.McpUserConfig;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 配置用例：provider 级视图 + 用户配置保存/删除 + 工具清单与连接测试。 */
@Service
public class McpConfigApplicationService {
    private final McpConfigRepository repository;
    private final McpServerTester tester;
    private final Clock clock;

    /** 主构造器（@Autowired：存在测试专用重载构造器时需显式指定注入入口）。 */
    @Autowired
    public McpConfigApplicationService(McpConfigRepository repository, McpServerTester tester) {
        // A3：application 层禁止直调 System 时钟，连接测试耗时经注入时钟测量
        this(repository, tester, Clock.systemUTC());
    }

    /** 测试注入：自定义时钟（耗时测量可确定性）。 */
    McpConfigApplicationService(McpConfigRepository repository, McpServerTester tester, Clock clock) {
        this.repository = repository;
        this.tester = tester;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<McpProviderView> providers() {
        return repository.findEnabledProviders().stream().map(p -> {
            List<String> domains = repository.findEnabledEndpointsByProviderId(p.id()).stream()
                    .map(McpEndpoint::domain).filter(Objects::nonNull).toList();
            return McpProviderView.from(p, domains);
        }).toList();
    }

    @Transactional(readOnly = true)
    public List<McpConfigView> myConfigs(Long userId) {
        return repository.findByUserId(userId).stream().map(McpConfigView::from).toList();
    }

    @Transactional
    public McpConfigView save(Long userId, Long providerId, Boolean enabled, List<String> disabledTools) {
        requireProvider(providerId);
        McpUserConfig existing = repository.findByUserIdAndProviderId(userId, providerId).orElse(null);
        boolean nextEnabled = enabled != null ? enabled : (existing == null || existing.enabled());
        List<String> nextDisabled = disabledTools != null ? disabledTools
                : (existing == null ? List.of() : existing.disabledTools());
        McpUserConfig toSave = existing == null
                ? McpUserConfig.create(userId, providerId, nextDisabled, Instant.now())
                : existing.update(nextEnabled, nextDisabled, Instant.now());
        return McpConfigView.from(repository.save(toSave));
    }

    @Transactional
    public void delete(Long userId, Long providerId) {
        if (repository.findByUserIdAndProviderId(userId, providerId).isEmpty()) {
            throw new McpException(McpErrorCode.CONFIG_NOT_FOUND, "配置不存在");
        }
        repository.deleteByUserIdAndProviderId(userId, providerId);
    }

    @Transactional(readOnly = true)
    public List<ToolView> tools(Long userId, Long providerId) {
        McpUserConfig config = repository.findByUserIdAndProviderId(userId, providerId)
                .orElseThrow(() -> new McpException(McpErrorCode.CONFIG_NOT_FOUND, "配置不存在"));
        McpProvider provider = requireProvider(providerId);
        return listTools(provider).stream()
                .map(t -> new ToolView(t.name(), t.description(), !config.disabledTools().contains(t.name())))
                .toList();
    }

    @Transactional(readOnly = true)
    public TestResult test(Long providerId) {
        McpProvider provider = requireProvider(providerId);
        try {
            long start = clock.millis();
            List<McpToolDescriptor> tools = listTools(provider);
            return TestResult.ok(tools, clock.millis() - start);
        } catch (McpException e) {
            return TestResult.fail(e.getMessage());
        }
    }

    private List<McpToolDescriptor> listTools(McpProvider provider) {
        List<McpToolDescriptor> all = new ArrayList<>();
        for (McpEndpoint endpoint : repository.findEnabledEndpointsByProviderId(provider.id())) {
            all.addAll(tester.testConnection(provider, endpoint, provider.authSecretEnc()));
        }
        return all;
    }

    private McpProvider requireProvider(Long providerId) {
        McpProvider p = repository.findProviderById(providerId)
                .orElseThrow(() -> new McpException(McpErrorCode.PROVIDER_NOT_FOUND, "数据源不存在"));
        if (!p.enabled()) throw new McpException(McpErrorCode.PROVIDER_NOT_FOUND, "数据源不可用");
        return p;
    }
}
