package com.portfolio.invest.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * invest.report 配置组绑定（MS-32 B1，设计规格 §八 / Global Constraints #1）：
 * 执行/配额/校验/陈旧度四组 + 草稿字节上限，代码默认值逐项钉死，B7~B12 编排器消费；
 * distinct 值绑定区分「绑定生效」与「静默回退默认」（沿 InvestPropertiesTrustBindingTest 纪律）。
 */
class InvestPropertiesReportTest {

    @DisplayName("未配置时：execution/quota/validation/staleness/draft 共 14 项代码默认值逐项生效（Global Constraints #1）")
    @Test
    void givenNoReportConfig_whenBind_thenDefaultsApply() {
        InvestProperties props = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("invest", InvestProperties.class);

        var execution = props.getReport().getExecution();
        assertThat(execution.roundTimeout()).isEqualTo(Duration.ofSeconds(240));
        assertThat(execution.totalTimeout()).isEqualTo(Duration.ofMinutes(15));
        assertThat(execution.maxItersPerRound()).isEqualTo(15);
        assertThat(execution.poolSize()).isEqualTo(2);
        var quota = props.getReport().getQuota();
        assertThat(quota.dailyTokenBudget()).isEqualTo(500_000L);
        assertThat(quota.perTaskTokenCap()).isEqualTo(150_000L);
        var validation = props.getReport().getValidation();
        assertThat(validation.relative()).isEqualByComparingTo("0.01");
        assertThat(validation.absolute()).isEqualByComparingTo("0.03");
        assertThat(validation.price()).isEqualByComparingTo("0.005");
        assertThat(validation.unverifiedRatioMax()).isEqualTo(0.10);
        assertThat(validation.maxRegenRounds()).isEqualTo(1);
        var staleness = props.getReport().getStaleness();
        assertThat(staleness.recheckTtl()).isEqualTo(Duration.ofMinutes(5));
        assertThat(staleness.changedRelative()).isEqualByComparingTo("0.02");
        assertThat(props.getReport().getDraftMaxBytes()).isEqualTo(131072);
    }

    @DisplayName("全量绑定：invest.report.* 14 键字面量逐键命中（与 application.yml 钉死值一致）")
    @Test
    void givenFullReportConfig_whenBind_thenAllValuesBound() {
        InvestProperties props = new Binder(new MapConfigurationPropertySource(Map.ofEntries(
                Map.entry("invest.report.execution.round-timeout", "240s"),
                Map.entry("invest.report.execution.total-timeout", "15m"),
                Map.entry("invest.report.execution.max-iters-per-round", "15"),
                Map.entry("invest.report.execution.pool-size", "2"),
                Map.entry("invest.report.quota.daily-token-budget", "500000"),
                Map.entry("invest.report.quota.per-task-token-cap", "150000"),
                Map.entry("invest.report.validation.relative", "0.01"),
                Map.entry("invest.report.validation.absolute", "0.03"),
                Map.entry("invest.report.validation.price", "0.005"),
                Map.entry("invest.report.validation.unverified-ratio-max", "0.10"),
                Map.entry("invest.report.validation.max-regen-rounds", "1"),
                Map.entry("invest.report.staleness.recheck-ttl", "5m"),
                Map.entry("invest.report.staleness.changed-relative", "0.02"),
                Map.entry("invest.report.draft-max-bytes", "131072"))))
                .bindOrCreate("invest", InvestProperties.class);

        var execution = props.getReport().getExecution();
        assertThat(execution.roundTimeout()).isEqualTo(Duration.ofSeconds(240));
        assertThat(execution.totalTimeout()).isEqualTo(Duration.ofMinutes(15));
        assertThat(execution.maxItersPerRound()).isEqualTo(15);
        assertThat(execution.poolSize()).isEqualTo(2);
        var quota = props.getReport().getQuota();
        assertThat(quota.dailyTokenBudget()).isEqualTo(500_000L);
        assertThat(quota.perTaskTokenCap()).isEqualTo(150_000L);
        var validation = props.getReport().getValidation();
        assertThat(validation.relative()).isEqualByComparingTo("0.01");
        assertThat(validation.absolute()).isEqualByComparingTo("0.03");
        assertThat(validation.price()).isEqualByComparingTo("0.005");
        assertThat(validation.unverifiedRatioMax()).isEqualTo(0.10);
        assertThat(validation.maxRegenRounds()).isEqualTo(1);
        var staleness = props.getReport().getStaleness();
        assertThat(staleness.recheckTtl()).isEqualTo(Duration.ofMinutes(5));
        assertThat(staleness.changedRelative()).isEqualByComparingTo("0.02");
        assertThat(props.getReport().getDraftMaxBytes()).isEqualTo(131072);
    }

    @DisplayName("distinct 值绑定覆盖：invest.report.* 全键以非默认值绑定（区分绑定成功与静默回退默认）")
    @Test
    void givenDistinctReportConfig_whenBind_thenValuesOverridden() {
        InvestProperties props = new Binder(new MapConfigurationPropertySource(Map.ofEntries(
                Map.entry("invest.report.execution.round-timeout", "60s"),
                Map.entry("invest.report.execution.total-timeout", "30m"),
                Map.entry("invest.report.execution.max-iters-per-round", "8"),
                Map.entry("invest.report.execution.pool-size", "5"),
                Map.entry("invest.report.quota.daily-token-budget", "123456"),
                Map.entry("invest.report.quota.per-task-token-cap", "65432"),
                Map.entry("invest.report.validation.relative", "0.05"),
                Map.entry("invest.report.validation.absolute", "0.07"),
                Map.entry("invest.report.validation.price", "0.02"),
                Map.entry("invest.report.validation.unverified-ratio-max", "0.50"),
                Map.entry("invest.report.validation.max-regen-rounds", "3"),
                Map.entry("invest.report.staleness.recheck-ttl", "15m"),
                Map.entry("invest.report.staleness.changed-relative", "0.10"),
                Map.entry("invest.report.draft-max-bytes", "2048"))))
                .bindOrCreate("invest", InvestProperties.class);

        var execution = props.getReport().getExecution();
        assertThat(execution.roundTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(execution.totalTimeout()).isEqualTo(Duration.ofMinutes(30));
        assertThat(execution.maxItersPerRound()).isEqualTo(8);
        assertThat(execution.poolSize()).isEqualTo(5);
        var quota = props.getReport().getQuota();
        assertThat(quota.dailyTokenBudget()).isEqualTo(123_456L);
        assertThat(quota.perTaskTokenCap()).isEqualTo(65_432L);
        var validation = props.getReport().getValidation();
        assertThat(validation.relative()).isEqualByComparingTo("0.05");
        assertThat(validation.absolute()).isEqualByComparingTo("0.07");
        assertThat(validation.price()).isEqualByComparingTo("0.02");
        assertThat(validation.unverifiedRatioMax()).isEqualTo(0.50);
        assertThat(validation.maxRegenRounds()).isEqualTo(3);
        var staleness = props.getReport().getStaleness();
        assertThat(staleness.recheckTtl()).isEqualTo(Duration.ofMinutes(15));
        assertThat(staleness.changedRelative()).isEqualByComparingTo("0.10");
        assertThat(props.getReport().getDraftMaxBytes()).isEqualTo(2048);
    }
}
