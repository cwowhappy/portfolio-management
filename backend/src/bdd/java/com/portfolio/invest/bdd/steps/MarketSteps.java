package com.portfolio.invest.bdd.steps;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.invest.domain.market.MarketDataErrorCode;
import com.portfolio.invest.domain.market.MarketDataException;
import com.portfolio.invest.domain.market.StockRef;
import com.portfolio.invest.infrastructure.market.EastmoneyClient;
import com.portfolio.invest.infrastructure.market.SinaClient;
import io.cucumber.java.zh_cn.假如;
import io.cucumber.java.zh_cn.当;
import io.cucumber.java.zh_cn.那么;
import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 行情降级步骤：走真实 HTTP（/api/market/** 为匿名放行端点），
 * 主源（东财）与备源（新浪）均为 {@code @MockitoBean} mock，不打真实网络。
 */
public class MarketSteps {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    EastmoneyClient eastmoneyClient;

    @Autowired
    SinaClient sinaClient;

    @Autowired
    ScenarioContext ctx;

    @假如("东方财富行情源发生故障")
    public void 东财故障() {
        when(eastmoneyClient.quote(anyString())).thenThrow(
                new MarketDataException(MarketDataErrorCode.UPSTREAM_UNAVAILABLE, "主源不可用（模拟故障）"));
    }

    // 正则而非 cucumber 表达式：{string} 无法匹配括号内不带引号的中文名称
    @假如("^新浪行情源中 \"([^\"]+)\"（([^）]+)）的最新价为 ([\\d.]+) 元、昨收为 ([\\d.]+) 元$")
    public void 新浪报价(String code, String name, BigDecimal price, BigDecimal prevClose) {
        StockRef ref = StockRef.from(code);
        // 新浪行情线格式：var hq_str_sh600519="名称,今开,昨收,最新价,最高,最低,...,成交量,成交额";
        String raw = "var hq_str_%s%s=\"%s,%s,%s,%s,%s,%s,0,0,1000000,1680000000\";"
                .formatted(ref.sinaPrefix(), ref.code(), name, prevClose, prevClose, price, price, price);
        when(sinaClient.rawQuote(ref.sinaPrefix(), ref.code())).thenReturn(raw);
    }

    @当("查询股票 {string} 的实时行情")
    public void 查询实时行情(String code) throws Exception {
        ctx.setLastQueriedCode(code);
        ctx.setLastResponse(mockMvc.perform(get("/api/market/quote/{code}", code)).andReturn());
    }

    @那么("应返回最新价 {bigdecimal} 元的行情数据")
    public void 行情断言(BigDecimal price) throws Exception {
        // ResultMatcher 支持对保存的 MvcResult 重放断言
        status().isOk().match(ctx.getLastResponse());
        jsonPath("$.price").value(price.doubleValue()).match(ctx.getLastResponse());
    }

    @那么("行情实际由备源新浪提供")
    public void 备源断言() {
        StockRef ref = StockRef.from(ctx.getLastQueriedCode());
        // 主源被请求过一次（失败后降级），备源被请求过一次（真正提供了数据）
        verify(eastmoneyClient, times(1)).quote(ref.secid());
        verify(sinaClient, times(1)).rawQuote(ref.sinaPrefix(), ref.code());
    }
}
