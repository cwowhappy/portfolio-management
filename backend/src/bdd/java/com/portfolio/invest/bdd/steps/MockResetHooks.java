package com.portfolio.invest.bdd.steps;

import com.portfolio.invest.application.eval.EvalScheduler;
import com.portfolio.invest.infrastructure.market.EastmoneyClient;
import com.portfolio.invest.infrastructure.market.SinaClient;
import com.portfolio.invest.infrastructure.market.TencentClient;
import io.cucumber.java.Before;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;

/** 每个场景开始前清空行情客户端与 eval 调度器 mock 的 stub 与调用记录，避免跨场景污染断言。 */
public class MockResetHooks {

    @Autowired
    EastmoneyClient eastmoneyClient;

    @Autowired
    SinaClient sinaClient;

    @Autowired
    TencentClient tencentClient;

    @Autowired
    EvalScheduler evalScheduler;

    @Before
    public void resetMarketClientMocks() {
        Mockito.reset(eastmoneyClient, sinaClient, tencentClient, evalScheduler);
    }
}
