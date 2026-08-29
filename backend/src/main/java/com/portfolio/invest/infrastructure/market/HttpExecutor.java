package com.portfolio.invest.infrastructure.market;

import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.market.MarketDataErrorCode;
import com.portfolio.invest.domain.market.MarketDataException;
import java.util.function.Supplier;
import org.springframework.web.client.HttpClientErrorException;

/**
 * 统一的「限流 + 可重试错误重试」执行器。
 * 每次真实 HTTP 尝试前获取令牌，使上游 QPS 受控于 rate-limit-per-second；
 * 仅对传输层/5xx 等可重试错误退避重试，4xx 与已分类的领域错误不重试。
 */
final class HttpExecutor {

    private final RateLimiter limiter;
    private final int maxAttempts;
    private final long backoffBaseMillis;
    private final long acquireTimeoutMillis;
    private final String upstreamName;

    HttpExecutor(
            RateLimiter limiter,
            int maxAttempts,
            long backoffBaseMillis,
            long acquireTimeoutMillis,
            String upstreamName) {
        this.limiter = limiter;
        this.maxAttempts = maxAttempts;
        this.backoffBaseMillis = backoffBaseMillis;
        this.acquireTimeoutMillis = acquireTimeoutMillis;
        this.upstreamName = upstreamName;
    }

    /** 生产配置：从 invest.market.* 读取重试与等待参数。 */
    static HttpExecutor fromProps(RateLimiter limiter, InvestProperties props, String upstreamName) {
        InvestProperties.Market m = props.getMarket();
        return new HttpExecutor(
                limiter, m.getMaxAttempts(), m.getRetryBackoffMillis(), m.getAcquireTimeoutMillis(), upstreamName);
    }

    /** 测试用：不限流、退避为 0（消除真实 sleep），保留 3 次重试语义与上游名。 */
    static HttpExecutor forTests(RateLimiter limiter, String upstreamName) {
        return new HttpExecutor(limiter, 3, 0, 2000, upstreamName);
    }

    <T> T execute(Supplier<T> action) {
        Exception last = null;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            if (!limiter.tryAcquire(acquireTimeoutMillis)) {
                throw new MarketDataException(MarketDataErrorCode.RATE_LIMITED, "行情请求过于频繁，请稍后再试");
            }
            try {
                return action.get();
            } catch (MarketDataException e) {
                throw e; // 已分类的领域错误（如空响应）不重试，直接上抛
            } catch (Exception e) {
                last = e;
                if (!isRetryable(e) || attempt == maxAttempts - 1) {
                    break;
                }
                try {
                    Thread.sleep(backoffBaseMillis * (attempt + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw new MarketDataException(
                MarketDataErrorCode.UPSTREAM_UNAVAILABLE,
                upstreamName + "接口不可用: " + (last == null ? "未知错误" : last.getMessage()),
                last);
    }

    /** 4xx 客户端错误（参数/鉴权问题）重试无益；其余 IO/超时/5xx 可重试。 */
    private static boolean isRetryable(Exception e) {
        return !(e instanceof HttpClientErrorException);
    }
}
