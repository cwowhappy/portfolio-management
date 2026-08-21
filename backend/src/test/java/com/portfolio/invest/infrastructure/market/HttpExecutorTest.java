package com.portfolio.invest.infrastructure.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.invest.domain.market.MarketDataException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

/** 统一「限流 + 重试」执行器：每次真实 HTTP 尝试前限流，重试语义与领域错误处理。 */
class HttpExecutorTest {

    @Test
    void 可重试错误重试至上限且每次尝试都取令牌() {
        RateLimiter limiter = mock(RateLimiter.class);
        when(limiter.tryAcquire(anyLong())).thenReturn(true);
        HttpExecutor executor = new HttpExecutor(limiter, 3, 0, 2000, "上游");
        assertThatThrownBy(() -> executor.execute(() -> {
            throw new IllegalStateException("boom");
        }))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("上游接口不可用");
        verify(limiter, times(3)).tryAcquire(anyLong()); // 每次真实 HTTP 尝试前都限流
    }

    @Test
    void 领域错误不重试直接上抛() {
        RateLimiter limiter = mock(RateLimiter.class);
        when(limiter.tryAcquire(anyLong())).thenReturn(true);
        HttpExecutor executor = new HttpExecutor(limiter, 3, 0, 2000, "上游");
        assertThatThrownBy(() -> executor.execute(() -> {
            throw new MarketDataException("BAD_RESPONSE", "格式异常");
        }))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("格式异常");
        verify(limiter, times(1)).tryAcquire(anyLong());
    }

    @Test
    void 客户端4xx错误不重试() {
        RateLimiter limiter = mock(RateLimiter.class);
        when(limiter.tryAcquire(anyLong())).thenReturn(true);
        HttpExecutor executor = new HttpExecutor(limiter, 3, 0, 2000, "上游");
        HttpClientErrorException e = new HttpClientErrorException(HttpStatus.BAD_REQUEST, "bad");
        assertThatThrownBy(() -> executor.execute(() -> {
            throw e;
        })).isInstanceOf(MarketDataException.class);
        verify(limiter, times(1)).tryAcquire(anyLong());
    }

    @Test
    void 拿不到令牌抛RATE_LIMITED() {
        RateLimiter limiter = mock(RateLimiter.class);
        when(limiter.tryAcquire(anyLong())).thenReturn(false);
        HttpExecutor executor = new HttpExecutor(limiter, 3, 0, 2000, "上游");
        assertThatThrownBy(() -> executor.execute(() -> "ok"))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("行情请求过于频繁")
                .extracting(e -> ((MarketDataException) e).getCode())
                .isEqualTo("RATE_LIMITED");
    }
}
