package com.portfolio.invest.application.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.invest.domain.eval.PromptAssetVersion;
import com.portfolio.invest.domain.eval.PromptAssetVersionRepository;
import com.portfolio.invest.domain.observability.ObservabilityQueryRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * 看板查询用例单测（MS-30 B5）：days→since 截断口径（固定 Clock）、trace 参数透传、
 * 版本链分组（按 (asset_type, asset_key) 分组、版本倒序最新居首——current 标记由 web 层
 * 按「组内首元素」渲染，本层保证分组序）。仓库端口全 mock（SQL 契约归集成测试）。
 */
class ObservabilityApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-10T01:00:00Z");

    private final ObservabilityQueryRepository queries = Mockito.mock(ObservabilityQueryRepository.class);
    private final PromptAssetVersionRepository assetVersions = Mockito.mock(PromptAssetVersionRepository.class);
    private final ObservabilityApplicationService service = new ObservabilityApplicationService(
            queries, assetVersions, Clock.fixed(NOW, ZoneId.of("Asia/Shanghai")));

    @DisplayName("给定days窗口，when查询cost，then截断点=时钟now-days且两路聚合同截断")
    @Test
    void givenDaysWindow_whenCost_thenCutoffIsClockMinusDays() {
        service.cost(7);

        verify(queries).tokenUsageByDay(NOW.minus(Duration.ofDays(7)));
        verify(queries).toolCallStats(NOW.minus(Duration.ofDays(7)));
    }

    @DisplayName("给定trace筛选，when查询，then参数原样透传")
    @Test
    void givenTraceFilters_whenTrace_thenPassedThrough() {
        Instant from = NOW.minus(Duration.ofHours(3));
        Instant to = NOW.minus(Duration.ofHours(1));

        service.trace(from, to, "get_quote", true, 1, 25);

        verify(queries).findTrace(from, to, "get_quote", true, 1, 25);
    }

    @DisplayName("给定days窗口，when查询latency，then三路聚合同截断")
    @Test
    void givenDaysWindow_whenLatency_thenAllThreeQueriesWithSameCutoff() {
        service.latency(7);

        verify(queries).turnLatency(NOW.minus(Duration.ofDays(7)));
        verify(queries).turnLatencyByDay(NOW.minus(Duration.ofDays(7)));
        verify(queries).toolLatencyByTool(NOW.minus(Duration.ofDays(7)));
    }

    @DisplayName("给定多资产多版本，when版本链，then按类型与键分组且版本倒序最新居首")
    @Test
    void givenFlatVersions_whenAssetChains_thenGroupedByVersionDesc() {
        when(assetVersions.findVersionChain()).thenReturn(List.of(
                version("SKILL", "skill.tushare_data", 2),
                version("SKILL", "skill.tushare_data", 1),
                version("TOOL_DESC", "tool.get_quote", 1)));

        List<ObservabilityApplicationService.PromptAssetChain> chains = service.assetChains();

        assertThat(chains).hasSize(2);
        assertThat(chains.get(0).assetType()).isEqualTo("SKILL");
        assertThat(chains.get(0).assetKey()).isEqualTo("skill.tushare_data");
        assertThat(chains.get(0).versions()).extracting(PromptAssetVersion::version)
                .containsExactly(2, 1);
        assertThat(chains.get(1).assetType()).isEqualTo("TOOL_DESC");
        assertThat(chains.get(1).versions()).hasSize(1);
    }

    private static PromptAssetVersion version(String type, String key, int version) {
        return new PromptAssetVersion((long) version, type, key, version, "hash-" + version,
                null, NOW);
    }
}
