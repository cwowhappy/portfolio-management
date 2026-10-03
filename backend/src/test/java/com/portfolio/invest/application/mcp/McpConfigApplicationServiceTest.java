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
import com.portfolio.invest.domain.mcp.McpSecretCodec;
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
    private final McpSecretCodec codec = mock(McpSecretCodec.class);
    private McpConfigApplicationService service;

    @BeforeEach
    void setUp() {
        // 默认明文直读 codec（存量值原样返回），密文路径由专项用例覆写
        when(codec.decrypt(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new McpConfigApplicationService(repo, tester, codec);
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

    @DisplayName("管理员设置 token：加密后落库（密文不含明文）")
    @Test
    void givenSetToken_whenValid_thenEncryptedStored() {
        when(repo.findProviderByCode("wind")).thenReturn(Optional.of(provider()));
        when(codec.encrypt("new-token")).thenReturn("v1:ENCRYPTED");

        service.setProviderToken("wind", "new-token");

        verify(repo).updateProviderSecret(3L, "v1:ENCRYPTED");
    }

    @DisplayName("管理员设置 token：provider 不存在抛 PROVIDER_NOT_FOUND")
    @Test
    void givenSetToken_whenProviderMissing_thenThrowProviderNotFound() {
        when(repo.findProviderByCode("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setProviderToken("nope", "t"))
                .isInstanceOfSatisfying(McpException.class,
                        e -> assertThat(e.code()).isEqualTo(McpErrorCode.PROVIDER_NOT_FOUND));
        verify(repo, never()).updateProviderSecret(any(), any());
    }

    @DisplayName("管理员设置 token：空白/超长（>512）抛 INVALID_INPUT")
    @Test
    void givenSetToken_whenBlankOrTooLong_thenInvalidInput() {
        assertThatThrownBy(() -> service.setProviderToken("wind", " "))
                .isInstanceOfSatisfying(McpException.class,
                        e -> assertThat(e.code()).isEqualTo(McpErrorCode.INVALID_INPUT));
        assertThatThrownBy(() -> service.setProviderToken("wind", "x".repeat(513)))
                .isInstanceOfSatisfying(McpException.class,
                        e -> assertThat(e.code()).isEqualTo(McpErrorCode.INVALID_INPUT));
    }

    @DisplayName("管理员设置 token：密钥未配置时透传 SECRET_KEY_MISSING（提示配置 MCP_SECRET_KEY）")
    @Test
    void givenSetToken_whenKeyMissing_thenPropagate() {
        when(repo.findProviderByCode("wind")).thenReturn(Optional.of(provider()));
        when(codec.encrypt("t")).thenThrow(new McpException(McpErrorCode.SECRET_KEY_MISSING, "MCP_SECRET_KEY 未配置"));

        assertThatThrownBy(() -> service.setProviderToken("wind", "t"))
                .isInstanceOfSatisfying(McpException.class,
                        e -> assertThat(e.code()).isEqualTo(McpErrorCode.SECRET_KEY_MISSING));
    }

    @DisplayName("连通测试：库内密文经解密后传入测试器")
    @Test
    void givenTest_whenSecretEncrypted_thenTesterReceivesDecrypted() {
        McpProvider p = McpProvider.reconstitute(3L, "wind", "Wind AIFin", AuthType.BEARER, null, "v1:ENC", true, null, NOW);
        McpEndpoint endpoint = McpEndpoint.reconstitute(5L, 3L, null, "Wind 数据",
                "https://mcp.wind.com.cn/vserver_stock_data/mcp/", true, NOW);
        when(repo.findProviderById(3L)).thenReturn(Optional.of(p));
        when(repo.findEnabledEndpointsByProviderId(3L)).thenReturn(List.of(endpoint));
        when(codec.decrypt("v1:ENC")).thenReturn("ak-secret");
        when(tester.testConnection(p, endpoint, "ak-secret"))
                .thenReturn(List.of(new McpToolDescriptor("get_stock_quote", "获取行情")));

        TestResult result = service.test(3L);

        assertThat(result.success()).isTrue();
        verify(tester).testConnection(p, endpoint, "ak-secret");
    }

    @DisplayName("连通测试：解密失败落入 TestResult.fail（不冒泡异常）")
    @Test
    void givenTest_whenDecryptFails_thenTestResultFail() {
        McpProvider p = McpProvider.reconstitute(3L, "wind", "Wind AIFin", AuthType.BEARER, null, "v1:broken", true, null, NOW);
        McpEndpoint endpoint = McpEndpoint.reconstitute(5L, 3L, null, "Wind 数据",
                "https://mcp.wind.com.cn/vserver_stock_data/mcp/", true, NOW);
        when(repo.findProviderById(3L)).thenReturn(Optional.of(p));
        when(repo.findEnabledEndpointsByProviderId(3L)).thenReturn(List.of(endpoint));
        when(codec.decrypt("v1:broken"))
                .thenThrow(new McpException(McpErrorCode.SECRET_DECRYPT_FAILED, "token 解密失败"));

        TestResult result = service.test(3L);

        assertThat(result.success()).isFalse();
    }

    @DisplayName("provider 视图带 hasToken 标记（已设=true 未设=false，不泄露密文）")
    @Test
    void givenProviders_whenList_thenHasTokenFlags() {
        McpProvider withSecret = McpProvider.reconstitute(2L, "tushare", "Tushare", AuthType.BEARER, null, "v1:ENC", true, null, NOW);
        McpProvider withoutSecret = McpProvider.reconstitute(3L, "wind", "Wind AIFin", AuthType.BEARER, null, null, true, null, NOW);
        when(repo.findEnabledProviders()).thenReturn(List.of(withSecret, withoutSecret));
        when(repo.findEnabledEndpointsByProviderId(2L)).thenReturn(List.of());
        when(repo.findEnabledEndpointsByProviderId(3L)).thenReturn(List.of());

        var views = service.providers();

        assertThat(views).extracting(McpProviderView::hasToken).containsExactly(true, false);
    }

    @DisplayName("工具清单读路径：解密失败降级空列表（不裸 500 打到用户设置页）")
    @Test
    void givenTools_whenDecryptFails_thenEmptyList() {
        McpProvider p = McpProvider.reconstitute(3L, "wind", "Wind AIFin", AuthType.BEARER, null, "v1:broken", true, null, NOW);
        McpUserConfig config = McpUserConfig.reconstitute(9L, 1L, 3L, true, List.of(), 1, NOW, NOW);
        when(repo.findProviderById(3L)).thenReturn(Optional.of(p));
        when(repo.findByUserIdAndProviderId(1L, 3L)).thenReturn(Optional.of(config));
        when(codec.decrypt("v1:broken"))
                .thenThrow(new McpException(McpErrorCode.SECRET_DECRYPT_FAILED, "token 解密失败"));

        var tools = service.tools(1L, 3L);

        assertThat(tools).isEmpty();
    }
}
