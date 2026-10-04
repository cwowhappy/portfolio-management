package com.portfolio.invest.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.invest.application.mcp.McpTokenRotatedEvent;
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
    private static final Duration TOOL_TIMEOUT = Duration.ofSeconds(30);

    /** 两步 mock 打桩嵌套配置（invest.mcp.connectTimeout=5s / toolTimeout=30s），避免 deep stubs。 */
    private static McpClientPool newPool() {
        InvestProperties.Mcp mcp = mock(InvestProperties.Mcp.class);
        when(mcp.getConnectTimeout()).thenReturn(CONNECT_TIMEOUT);
        when(mcp.getToolTimeout()).thenReturn(TOOL_TIMEOUT);
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

    @DisplayName("token 轮换事件：该 provider 全部 endpoint 的旧 wrapper 被 close，再次 acquire 用新 token 重建")
    @Test
    void givenTokenRotatedEvent_whenAcquire_thenOldClosedAndRebuiltWithNewToken() {
        McpProvider provider = provider(AuthType.BEARER, null);
        McpEndpoint endpointA = endpoint(3L);
        McpEndpoint endpointB = endpoint(4L);
        try (MockedStatic<McpClientBuilder> mocked = mockStatic(McpClientBuilder.class)) {
            McpClientBuilder builder = chainedBuilder();
            McpClientWrapper wrapperA = client();
            McpClientWrapper wrapperB = client();
            McpClientWrapper wrapperANew = client();
            when(builder.buildSync()).thenReturn(wrapperA, wrapperB, wrapperANew);
            mocked.when(() -> McpClientBuilder.create("tushare")).thenReturn(builder);

            McpClientPool pool = newPool();
            pool.acquire(provider, endpointA, "old-token");
            pool.acquire(provider, endpointB, "old-token");

            pool.onTokenRotated(new McpTokenRotatedEvent(2L));

            // 该 provider 两个 endpoint 的缓存客户端均被驱逐并关闭
            verify(wrapperA).close();
            verify(wrapperB).close();
            // 下一次 acquire 重建：新 token 进入鉴权头，且拿到全新 wrapper
            McpClientWrapper rebuilt = pool.acquire(provider, endpointA, "new-token");
            assertThat(rebuilt).isSameAs(wrapperANew).isNotSameAs(wrapperA);
            verify(builder).header("Authorization", "Bearer new-token");
            mocked.verify(() -> McpClientBuilder.create("tushare"), times(3));
        }
    }

    @DisplayName("token 轮换事件：其他 provider 的缓存客户端不受影响")
    @Test
    void givenTokenRotatedEventForOtherProvider_whenEvent_thenThisProviderClientsUntouched() {
        McpProvider providerA = provider(AuthType.BEARER, null); // id=2
        McpProvider providerB = McpProvider.reconstitute(5L, "wind", "Wind", AuthType.BEARER, null, "t", true, null, NOW);
        try (MockedStatic<McpClientBuilder> mocked = mockStatic(McpClientBuilder.class)) {
            McpClientBuilder builder = chainedBuilder();
            McpClientWrapper wrapperA = client();
            McpClientWrapper wrapperB = client();
            when(builder.buildSync()).thenReturn(wrapperA, wrapperB);
            mocked.when(() -> McpClientBuilder.create("tushare")).thenReturn(builder);
            mocked.when(() -> McpClientBuilder.create("wind")).thenReturn(builder);

            McpClientPool pool = newPool();
            pool.acquire(providerA, endpoint(3L), "tok");
            pool.acquire(providerB, endpoint(6L), "tok");

            pool.onTokenRotated(new McpTokenRotatedEvent(5L));

            verify(wrapperA, never()).close();
            verify(wrapperB).close();
            // providerA 的缓存仍在：acquire 复用不重建
            assertThat(pool.acquire(providerA, endpoint(3L), "tok")).isSameAs(wrapperA);
        }
    }

    @DisplayName("池销毁：@PreDestroy 关闭全部 wrapper，销毁后 acquire 重新构建")
    @Test
    void givenPreDestroy_whenShutdown_thenAllClientsClosed() {
        McpProvider provider = provider(AuthType.BEARER, null);
        try (MockedStatic<McpClientBuilder> mocked = mockStatic(McpClientBuilder.class)) {
            McpClientBuilder builder = chainedBuilder();
            McpClientWrapper first = client();
            McpClientWrapper second = client();
            when(builder.buildSync()).thenReturn(first, second);
            mocked.when(() -> McpClientBuilder.create("tushare")).thenReturn(builder);

            McpClientPool pool = newPool();
            pool.acquire(provider, endpoint(3L), "tok");
            pool.acquire(provider, endpoint(4L), "tok");

            pool.shutdown();

            verify(first).close();
            verify(second).close();
            // 全量驱逐后再次 acquire 视为冷启动：重新构建而非复用已关闭实例
            assertThat(pool.acquire(provider, endpoint(3L), "tok")).isNotSameAs(first);
            mocked.verify(() -> McpClientBuilder.create("tushare"), times(3));
        }
    }

    @DisplayName("initialize 阻塞带 toolTimeout 兜底（transport 超时之外的挂死防线）")
    @Test
    @SuppressWarnings("unchecked")
    void givenBuild_whenInitialize_thenBlockedWithToolTimeout() {
        McpProvider provider = provider(AuthType.BEARER, null);
        try (MockedStatic<McpClientBuilder> mocked = mockStatic(McpClientBuilder.class)) {
            McpClientBuilder builder = chainedBuilder();
            McpClientWrapper wrapper = mock(McpClientWrapper.class);
            Mono<Void> initialize = mock(Mono.class);
            when(wrapper.initialize()).thenReturn(initialize);
            when(builder.buildSync()).thenReturn(wrapper);
            mocked.when(() -> McpClientBuilder.create("tushare")).thenReturn(builder);

            newPool().acquire(provider, endpoint(3L), "tok");

            verify(initialize).block(TOOL_TIMEOUT);
        }
    }
}
