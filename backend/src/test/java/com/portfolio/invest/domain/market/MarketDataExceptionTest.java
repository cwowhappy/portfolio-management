package com.portfolio.invest.domain.market;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 领域异常。 */
class MarketDataExceptionTest {

    @DisplayName("携带错误码与消息")
    @Test
    void givenErrorCodeAndMessage_whenConstruct_thenCarryBoth() {
        MarketDataException e = new MarketDataException(MarketDataErrorCode.INVALID_CODE, "无效代码");
        assertThat(e.getCode()).isEqualTo(MarketDataErrorCode.INVALID_CODE);
        assertThat(e.getMessage()).isEqualTo("无效代码");
        assertThat(e.getCause()).isNull();
    }

    @DisplayName("携带原因链")
    @Test
    void givenCause_whenConstruct_thenCarryCauseChain() {
        Exception cause = new RuntimeException("root");
        MarketDataException e = new MarketDataException(MarketDataErrorCode.UPSTREAM_UNAVAILABLE, "挂了", cause);
        assertThat(e.getCode()).isEqualTo(MarketDataErrorCode.UPSTREAM_UNAVAILABLE);
        assertThat(e.getMessage()).isEqualTo("挂了");
        assertThat(e.getCause()).isSameAs(cause);
    }
}
