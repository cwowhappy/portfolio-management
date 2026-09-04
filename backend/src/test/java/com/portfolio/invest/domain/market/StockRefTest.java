package com.portfolio.invest.domain.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StockRefTest {

    @DisplayName("沪市代码归一化沪市字段")
    @Test
    void givenShanghaiStockCode_whenStockRefFrom_thenNormalizeShanghaiFields() {
        StockRef r = StockRef.from("600519");
        assertThat(r.market()).isEqualTo("1");
        assertThat(r.secid()).isEqualTo("1.600519");
        assertThat(r.sinaPrefix()).isEqualTo("sh");
        assertThat(r.secuCode()).isEqualTo("600519.SH");
    }

    @DisplayName("深市代码归一化深市字段")
    @Test
    void givenShenzhenStockCode_whenStockRefFrom_thenNormalizeShenzhenFields() {
        StockRef r = StockRef.from("sz000858");
        assertThat(r.market()).isEqualTo("0");
        assertThat(r.secid()).isEqualTo("0.000858");
        assertThat(r.sinaPrefix()).isEqualTo("sz");
    }

    @DisplayName("带后缀代码归一化为纯代码")
    @Test
    void givenDottedStockCode_whenStockRefFrom_thenNormalizeCode() {
        StockRef r = StockRef.from("600519.SH");
        assertThat(r.code()).isEqualTo("600519");
    }

    @DisplayName("非法股票代码抛异常")
    @Test
    void givenInvalidStockCode_whenStockRefFrom_thenThrowInvalidCode() {
        assertThatThrownBy(() -> StockRef.from("abc"))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("无效的股票代码");
    }

    @DisplayName("exchange分类统一规则")
    @Test
    void givenCodeAndMktNum_whenExchangeOf_thenClassifyByUnifiedRules() {
        assertThat(StockRef.Exchange.of("600519", null)).isEqualTo(StockRef.Exchange.SH);
        assertThat(StockRef.Exchange.of("900901", null)).isEqualTo(StockRef.Exchange.SH); // 沪B
        assertThat(StockRef.Exchange.of("000858", null)).isEqualTo(StockRef.Exchange.SZ);
        assertThat(StockRef.Exchange.of("830799", null)).isEqualTo(StockRef.Exchange.BJ);
        assertThat(StockRef.Exchange.of("000001", "1")).isEqualTo(StockRef.Exchange.SH); // 东财 mkt 号兜底
        assertThat(StockRef.Exchange.BJ.displayName()).isEqualTo("北交所");
    }
}
