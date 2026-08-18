package com.portfolio.invest.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class StockRefTest {

    @Test
    void normalizesShanghai() {
        StockRef r = StockRef.from("600519");
        assertThat(r.market()).isEqualTo("1");
        assertThat(r.secid()).isEqualTo("1.600519");
        assertThat(r.sinaPrefix()).isEqualTo("sh");
        assertThat(r.secuCode()).isEqualTo("600519.SH");
    }

    @Test
    void normalizesShenzhen() {
        StockRef r = StockRef.from("sz000858");
        assertThat(r.market()).isEqualTo("0");
        assertThat(r.secid()).isEqualTo("0.000858");
        assertThat(r.sinaPrefix()).isEqualTo("sz");
    }

    @Test
    void normalizesDotted() {
        StockRef r = StockRef.from("600519.SH");
        assertThat(r.code()).isEqualTo("600519");
    }

    @Test
    void rejectsInvalid() {
        assertThatThrownBy(() -> StockRef.from("abc"))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("无效的股票代码");
    }
}
