package com.portfolio.invest.application.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.invest.domain.mcp.AuthType;
import com.portfolio.invest.domain.mcp.McpConfigRepository;
import com.portfolio.invest.domain.mcp.McpEndpoint;
import com.portfolio.invest.domain.mcp.McpErrorCode;
import com.portfolio.invest.domain.mcp.McpException;
import com.portfolio.invest.domain.mcp.McpProvider;
import com.portfolio.invest.domain.mcp.McpUserConfig;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 配置用例服务：provider 级视图 + 用户配置保存/删除 + 工具清单与连接测试。 */
class McpConfigApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-06T08:00:00Z");

    private final McpConfigRepository repo = mock(McpConfigRepository.class);
    private final McpServerTester tester = mock(McpServerTester.class);
    private McpConfigApplicationService service;

    @BeforeEach
    void setUp() {
        service = new McpConfigApplicationService(repo, tester);
    }

    private static McpProvider provider() {
        return McpProvider.reconstitute(3L, "wind", "Wind AIFin", AuthType.BEARER, null, "ak-secret", true, null, NOW);
    }

    @DisplayName("保存新建配置版本为1且禁用工具落库")
    @Test
    void givenSave_whenNewConfig_thenVersionOneAndDisabledToolsStored() {
        when(repo.findProviderById(3L)).thenReturn(Optional.of(provider()));
        when(repo.findByUserIdAndProviderId(1L, 3L)).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        McpConfigView view = service.save(1L, 3L, true, List.of("get_stock_kline"));

        assertThat(view.configVersion()).isEqualTo(1);
        assertThat(view.enabled()).isTrue();
        assertThat(view.disabledTools()).containsExactly("get_stock_kline");
        verify(repo).save(argThat(c -> c.configVersion() == 1 && c.disabledTools().contains("get_stock_kline")));
    }

    @DisplayName("保存更新配置版本自增")
    @Test
    void givenSave_whenUpdateExisting_thenVersionIncremented() {
        McpUserConfig existing = McpUserConfig.reconstitute(9L, 1L, 3L, true, List.of("a"), 1, NOW, NOW);
        when(repo.findProviderById(3L)).thenReturn(Optional.of(provider()));
        when(repo.findByUserIdAndProviderId(1L, 3L)).thenReturn(Optional.of(existing));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        McpConfigView view = service.save(1L, 3L, false, List.of("b"));

        assertThat(view.configVersion()).isEqualTo(2);
        assertThat(view.enabled()).isFalse();
        assertThat(view.disabledTools()).containsExactly("b");
    }

    @DisplayName("保存数据源不存在抛PROVIDER_NOT_FOUND")
    @Test
    void givenSave_whenProviderMissing_thenThrowProviderNotFound() {
        when(repo.findProviderById(3L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.save(1L, 3L, true, List.of()))
                .isInstanceOfSatisfying(McpException.class,
                        e -> assertThat(e.code()).isEqualTo(McpErrorCode.PROVIDER_NOT_FOUND));
    }

    @DisplayName("删除不存在配置抛CONFIG_NOT_FOUND")
    @Test
    void givenDelete_whenConfigMissing_thenThrowConfigNotFound() {
        when(repo.findByUserIdAndProviderId(1L, 3L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(1L, 3L))
                .isInstanceOfSatisfying(McpException.class,
                        e -> assertThat(e.code()).isEqualTo(McpErrorCode.CONFIG_NOT_FOUND));
        verify(repo, never()).deleteByUserIdAndProviderId(1L, 3L);
    }

    @DisplayName("测试连接聚合端点工具")
    @Test
    void givenTest_whenEndpointHasTools_thenAggregateAndSucceed() {
        McpProvider p = provider();
        McpEndpoint endpoint = McpEndpoint.reconstitute(5L, 3L, "stock", "Wind 股票",
                "https://mcp.wind.com.cn/stock", true, NOW);
        when(repo.findProviderById(3L)).thenReturn(Optional.of(p));
        when(repo.findEnabledEndpointsByProviderId(3L)).thenReturn(List.of(endpoint));
        when(tester.testConnection(p, endpoint, "ak-secret"))
                .thenReturn(List.of(new McpToolDescriptor("get_stock_quote", "获取行情")));

        TestResult result = service.test(3L);

        assertThat(result.success()).isTrue();
        assertThat(result.tools()).extracting(McpToolDescriptor::name).containsExactly("get_stock_quote");
    }
}
