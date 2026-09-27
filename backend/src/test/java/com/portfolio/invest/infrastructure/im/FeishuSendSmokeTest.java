package com.portfolio.invest.infrastructure.im;

import com.portfolio.invest.config.InvestProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** P1 真连冒烟（opt-in）：FEISHU_SMOKE=true 且 env 提供真实凭证时真发一条卡片到预警群。 */
@EnabledIfEnvironmentVariable(named = "FEISHU_SMOKE", matches = "true")
class FeishuSendSmokeTest {

    @Test
    @DisplayName("给定真实凭证，when发送预警样例卡片，then飞书受理code=0")
    void given真实凭证_when发送预警卡片_then成功() {
        InvestProperties props = new InvestProperties();
        props.getIm().setAppId(System.getenv("FEISHU_APP_ID"));
        props.getIm().setAppSecret(System.getenv("FEISHU_APP_SECRET"));
        FeishuClient client = new FeishuClient(props);
        boolean ok = client.sendCard(System.getenv("FEISHU_ALERT_CHAT_ID"), "⚠️ 投资原则预警", "red",
                List.of("**交易日**：冒烟", "- **单票仓位上限** 贵州茅台(600519)：0.2500 > 阈值 0.2000（样例，非真实巡检）"));
        assertThat(ok).as("真实发送应成功（失败看 FeishuClient WARN 日志里的 code/msg）").isTrue();
    }
}
