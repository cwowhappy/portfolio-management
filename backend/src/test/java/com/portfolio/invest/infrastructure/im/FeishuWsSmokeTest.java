package com.portfolio.invest.infrastructure.im;

import com.portfolio.invest.application.im.ImInboundMessage;
import com.portfolio.invest.application.im.ImMessageListener;
import com.portfolio.invest.config.InvestProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;

/** P2 真连冒烟（opt-in）：FEISHU_WS_SMOKE=true 且真实凭证时验证长连接 bootstrap+握手。 */
@EnabledIfEnvironmentVariable(named = "FEISHU_WS_SMOKE", matches = "true")
class FeishuWsSmokeTest {

    @Test
    @DisplayName("给定真实凭证，when启动长连接，then握手就绪（isRunning=true）")
    void given真实凭证_when启动_then就绪() throws Exception {
        InvestProperties props = new InvestProperties();
        props.getIm().setAppId(System.getenv("FEISHU_APP_ID"));
        props.getIm().setAppSecret(System.getenv("FEISHU_APP_SECRET"));
        props.getIm().setDialogueEnabled(true);
        FeishuWsClient client = new FeishuWsClient(props, m -> { }, Runnable::run);
        client.start();
        Thread.sleep(3000); // start 为异步线程——等握手（awaitReady 在内部线程，此处轮询 isRunning）
        for (int i = 0; i < 10 && !client.isRunning(); i++) {
            Thread.sleep(1000);
        }
        assertThat(client.isRunning()).as("长连接应就绪（失败看 FeishuWsClient ERROR 日志）").isTrue();
        client.stop();
    }
}
