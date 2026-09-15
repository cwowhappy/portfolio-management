package com.portfolio.invest.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.mcp.AuthType;
import com.portfolio.invest.domain.mcp.McpEndpoint;
import com.portfolio.invest.domain.mcp.McpProvider;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import reactor.core.publisher.Mono;

/**
 * MCP 客户端池单元语义：computeIfAbsent 按 endpoint.id 复用（不重复构建）、
 * HEADER/BEARER 两种鉴权头组装、构建失败向调用方传播且不缓存失败结果。
 * 纯 Mockito 桩定 agentscope 静态构建链，零网络零容器。
 */
class McpClientPoolTest {

    private static final Instant NOW = Instant.parse("2026-09-07T08:00:00Z");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /** 两步 mock 打桩嵌套配置（invest.mcp.connectTimeout=5s），避免 deep stubs。 */
    private static McpClientPool newPool() {
        InvestProperties.Mcp mcp = mock(InvestProperties.Mcp.class);
        when(mcp.getConnectTimeout()).thenReturn(CONNECT_TIMEOUT);
        InvestProperties props = mock(InvestProperties.class);
        when(props.getMcp()).thenReturn(mcp);
        return new McpClientPool(props);
    }

    private static McpProvider provider(AuthType authType, String authHeader) {
        return McpProvider.reconstitute(2L, "tushare", "Tushare", authType, authHeader, "token", true, null, NOW);
    }

    private static McpEndpoint endpoint(Long id) {
        return McpEndpoint.reconstitute(id, 2L, null, "Tushare 数据", "https://api.tushare.pro/mcp/", true, NOW);
    }

    /** wrapper 替身：initialize 即时完成（Mono.empty），不触碰真实网络。 */
    private static McpClientWrapper client() {
        McpClientWrapper wrapper = mock(McpClientWrapper.class);
        when(wrapper.initialize()).thenReturn(Mono.empty());
        return wrapper;
    }

    /** builder 替身：链式方法回自身；buildSync 的成败序列由各用例自行桩定。 */
    private static McpClientBuilder chainedBuilder() {
        McpClientBuilder builder = mock(McpClientBuilder.class);
        when(builder.streamableHttpTransport(anyString())).thenReturn(builder);
        when(builder.timeout(any(Duration.class))).thenReturn(builder);
        when(builder.header(anyString(), anyString())).thenReturn(builder);
        return builder;
    }

    @DisplayName("同 endpoint.id 两次 acquire 返回同一客户端（复用不重建）")
    @Test
    void givenSameEndpoint_whenAcquireTwice_thenSameWrapperReturned() {
        McpProvider provider = provider(AuthType.BEARER, null);
        McpEndpoint endpoint = endpoint(3L);
        try (MockedStatic<McpClientBuilder> mocked = mockStatic(McpClientBuilder.class)) {
            McpClientBuilder builder = chainedBuilder();
            McpClientWrapper wrapper = client();
            when(builder.buildSync()).thenReturn(wrapper);
            mocked.when(() -> McpClientBuilder.create("tushare")).thenReturn(builder);

            McpClientPool pool = newPool();
            McpClientWrapper first = pool.acquire(provider, endpoint, "tok");
            McpClientWrapper second = pool.acquire(provider, endpoint, "tok");

            assertThat(first).isSameAs(wrapper);
            assertThat(second).isSameAs(first);
            // 复用即只构建一次：静态入口与 buildSync 各命中一次，连接超时取自配置
            mocked.verify(() -> McpClientBuilder.create("tushare"), times(1));
            verify(builder, times(1)).buildSync();
            verify(builder).timeout(CONNECT_TIMEOUT);
        }
    }

    @DisplayName("不同 endpoint.id 各自构建，客户端引用不等")
    @Test
    void givenDifferentEndpoints_whenAcquire_thenDistinctWrappers() {
        McpProvider provider = provider(AuthType.BEARER, null);
        try (MockedStatic<McpClientBuilder> mocked = mockStatic(McpClientBuilder.class)) {
            McpClientBuilder builder = chainedBuilder();
            McpClientWrapper first = client();
            McpClientWrapper second = client();
            when(builder.buildSync()).thenReturn(first, second);
            mocked.when(() -> McpClientBuilder.create("tushare")).thenReturn(builder);

            McpClientPool pool = newPool();
            McpClientWrapper fromEndpointA = pool.acquire(provider, endpoint(3L), "tok");
            McpClientWrapper fromEndpointB = pool.acquire(provider, endpoint(4L), "tok");

            assertThat(fromEndpointA).isSameAs(first);
            assertThat(fromEndpointB).isSameAs(second);
            assertThat(fromEndpointB).isNotSameAs(fromEndpointA);
            mocked.verify(() -> McpClientBuilder.create("tushare"), times(2));
        }
    }

    @DisplayName("HEADER 鉴权：自定义鉴权头键值透传给 builder")
    @Test
    void givenHeaderAuth_whenBuild_thenCustomHeaderPropagated() {
        McpProvider provider = provider(AuthType.HEADER, "X-Custom");
        try (MockedStatic<McpClientBuilder> mocked = mockStatic(McpClientBuilder.class)) {
            McpClientBuilder builder = chainedBuilder();
            McpClientWrapper wrapper = client();
            when(builder.buildSync()).thenReturn(wrapper);
            mocked.when(() -> McpClientBuilder.create("tushare")).thenReturn(builder);

            newPool().acquire(provider, endpoint(3L), "tok");

            verify(builder).header("X-Custom", "tok");
        }
    }

    @DisplayName("BEARER 鉴权：Authorization: Bearer <token> 头透传给 builder")
    @Test
    void givenBearerAuth_whenBuild_thenAuthorizationHeaderPropagated() {
        McpProvider provider = provider(AuthType.BEARER, null);
        try (MockedStatic<McpClientBuilder> mocked = mockStatic(McpClientBuilder.class)) {
            McpClientBuilder builder = chainedBuilder();
            McpClientWrapper wrapper = client();
            when(builder.buildSync()).thenReturn(wrapper);
            mocked.when(() -> McpClientBuilder.create("tushare")).thenReturn(builder);

            newPool().acquire(provider, endpoint(3L), "tok");

            verify(builder).header("Authorization", "Bearer tok");
        }
    }

    @DisplayName("构建失败向调用方传播且不缓存，再次 acquire 重试构建")
    @Test
    void givenAcquireBuildFails_whenAcquire_thenExceptionSurfaced() {
        McpProvider provider = provider(AuthType.BEARER, null);
        McpEndpoint endpoint = endpoint(3L);
        try (MockedStatic<McpClientBuilder> mocked = mockStatic(McpClientBuilder.class)) {
            McpClientBuilder builder = chainedBuilder();
            McpClientWrapper wrapper = client();
            when(builder.buildSync()).thenThrow(new IllegalStateException("初始化失败"))
                    .thenReturn(wrapper);
            mocked.when(() -> McpClientBuilder.create("tushare")).thenReturn(builder);

            McpClientPool pool = newPool();
            assertThatThrownBy(() -> pool.acquire(provider, endpoint, "tok"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("初始化失败");

            // computeIfAbsent 映射函数抛错不落缓存：第二次 acquire 重走构建并成功
            assertThat(pool.acquire(provider, endpoint, "tok")).isSameAs(wrapper);
            mocked.verify(() -> McpClientBuilder.create("tushare"), times(2));
            verify(builder, times(2)).buildSync();
        }
    }
}
