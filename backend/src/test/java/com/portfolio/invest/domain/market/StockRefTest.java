package com.portfolio.invest.domain.market;

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

    @Test
    void exchange分类统一规则() {
        assertThat(StockRef.Exchange.of("600519", null)).isEqualTo(StockRef.Exchange.SH);
        assertThat(StockRef.Exchange.of("900901", null)).isEqualTo(StockRef.Exchange.SH); // 沪B
        assertThat(StockRef.Exchange.of("000858", null)).isEqualTo(StockRef.Exchange.SZ);
        assertThat(StockRef.Exchange.of("830799", null)).isEqualTo(StockRef.Exchange.BJ);
        assertThat(StockRef.Exchange.of("000001", "1")).isEqualTo(StockRef.Exchange.SH); // 东财 mkt 号兜底
        assertThat(StockRef.Exchange.BJ.displayName()).isEqualTo("北交所");
    }
}
