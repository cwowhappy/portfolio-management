package com.portfolio.invest.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** FR-7：系统提示须约束「工具被拒后不原样重试」，防止拒绝→重试→反复弹卡拉锯。 */
class InvestSystemPromptTest {

    @DisplayName("系统提示包含工具被拒后不重试的约束（FR-7）")
    @Test
    void givenSystemPrompt_whenCheck_thenContainsNoRetryAfterDenyRule() {
        assertThat(InvestSystemPrompt.TEXT)
                .contains("拒绝")
                .contains("不要原样重试");
    }

    @DisplayName("工具规范含五个新工具映射与私有数据口径（MS-12）")
    @Test
    void whenPromptText_thenContainsNewToolGuidance() {
        assertThat(InvestSystemPrompt.TEXT)
                .contains("screen_stocks").contains("analyze_portfolio")
                .contains("suggest_allocation").contains("analyze_financials")
                .contains("analyze_industry")
                .contains("私有").contains("MCP");
    }
}
