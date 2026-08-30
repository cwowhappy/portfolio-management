package com.portfolio.invest.bdd.steps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.portfolio.invest.application.portfolio.PortfolioApplicationService;
import com.portfolio.invest.infrastructure.market.EastmoneyClient;
import io.cucumber.java.zh_cn.当;
import io.cucumber.java.zh_cn.那么;
import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 估值缓存步骤：直调 ApplicationService 查看持仓估值总览。
 * 估值依赖行情报价，报价经 CachedMarketDataService 的 TTL 缓存：
 * 同一交易日内重复查询应命中缓存，最外层行情客户端只被调用一次（mock verify 断言）。
 */
public class ValuationSteps {

    @Autowired
    PortfolioApplicationService portfolioService;

    @Autowired
    EastmoneyClient eastmoneyClient;

    @Autowired
    ScenarioContext ctx;

    @当("该用户查看持仓估值总览")
    public void 查看估值总览() {
        ctx.getOverviews().add(portfolioService.overview(ctx.getUserId()));
    }

    @那么("两次估值总览的总资产都应为 {bigdecimal} 元")
    public void 两次总览一致(BigDecimal totalAssets) {
        assertThat(ctx.getOverviews()).hasSize(2);
        for (var overview : ctx.getOverviews()) {
            assertThat(overview.totalAssets()).isEqualByComparingTo(totalAssets);
        }
    }

    @那么("行情客户端只应被请求 {int} 次")
    public void 客户端调用次数(int times) {
        verify(eastmoneyClient, times(times)).quote(anyString());
    }
}
