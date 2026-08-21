package com.portfolio.invest.domain.market;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 领域异常。 */
class MarketDataExceptionTest {

    @Test
    void 携带错误码与消息() {
        MarketDataException e = new MarketDataException("INVALID_CODE", "无效代码");
        assertThat(e.getCode()).isEqualTo("INVALID_CODE");
        assertThat(e.getMessage()).isEqualTo("无效代码");
        assertThat(e.getCause()).isNull();
    }

    @Test
    void 携带原因链() {
        Exception cause = new RuntimeException("root");
        MarketDataException e = new MarketDataException("UPSTREAM_UNAVAILABLE", "挂了", cause);
        assertThat(e.getCode()).isEqualTo("UPSTREAM_UNAVAILABLE");
        assertThat(e.getMessage()).isEqualTo("挂了");
        assertThat(e.getCause()).isSameAs(cause);
    }
}
