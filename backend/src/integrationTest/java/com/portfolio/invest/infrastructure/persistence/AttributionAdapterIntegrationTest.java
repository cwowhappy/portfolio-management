package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.analytics.BenchmarkIndustryWeightPort;
import com.portfolio.invest.domain.analytics.IndustryMappingPort;
import com.portfolio.invest.support.PostgresTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// 同 RiskFreeRateAdapterIntegrationTest：@DataJpaTest 切片 + 真实 PG（继承 PostgresTestSupport 的 JVM 单例容器
// @DynamicPropertySource）；Boot 4 切片不含 Flyway 自动配置需 @ImportAutoConfiguration 显式引入；
// 两个纯 JdbcTemplate 读侧 Adapter 不在切片扫描范围内，用 @Import 显式装配。
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import({IndustryMappingAdapter.class, BenchmarkIndustryWeightAdapter.class})
class AttributionAdapterIntegrationTest extends PostgresTestSupport {

    /** 自有指数 code：避免污染真实 000300 成分（index_constituent 凭 index_code 隔离测试造数）。 */
    private static final String TEST_INDEX = "ATTR_TEST_IDX";

    @Autowired
    private IndustryMappingPort industryMapping;

    @Autowired
    private BenchmarkIndustryWeightPort benchmarkIndustryWeight;

    // 沿 RiskFreeRateAdapterIntegrationTest 的 @Autowired JdbcTemplate 方式声明（PostgresTestSupport 无该注入）
    @Autowired
    private JdbcTemplate jdbc;

    /** 共享容器还干净状态：删自有 index_code 的成分行 + 本测试插的映射行（600000 未插映射，无需清）。 */
    @AfterEach
    void cleanupSeededRows() {
        jdbc.update("DELETE FROM index_constituent WHERE index_code = ?", TEST_INDEX);
        jdbc.update("DELETE FROM shenwan_industry_mapping WHERE stock_code IN (?, ?, ?)",
                "600519", "000001", "600809");
    }

    @DisplayName("个股→申万行业映射全表读：返回 stock_code 到 IndustryRef 的映射")
    @Test
    void givenMappingRows_whenByStock_thenReturnIndustryRefs() {
        seedMapping("600519", "贵州茅台", "801120", "食品饮料");
        seedMapping("000001", "平安银行", "801780", "银行");

        Map<String, IndustryMappingPort.IndustryRef> byStock = industryMapping.byStock();

        assertThat(byStock).containsOnlyKeys("600519", "000001");
        assertThat(byStock.get("600519")).isEqualTo(new IndustryMappingPort.IndustryRef("801120", "食品饮料"));
        assertThat(byStock.get("000001")).isEqualTo(new IndustryMappingPort.IndustryRef("801780", "银行"));
    }

    @DisplayName("基准行业权重：成分百分数按行业聚合后归一化为小数，未映射成分并入 UNMAPPED（Σ=1）")
    @Test
    void givenConstituentsAndMapping_whenIndustryWeights_thenNormalizedDecimals() {
        seedMapping("600519", "贵州茅台", "801120", "食品饮料");
        seedMapping("000001", "平安银行", "801780", "银行");
        seedConstituent("600519", "30");
        seedConstituent("000001", "50");
        seedConstituent("600000", "20"); // 无映射行 → 并入 UNMAPPED

        Map<String, BigDecimal> weights = benchmarkIndustryWeight.industryWeights(TEST_INDEX);

        assertThat(weights).containsOnlyKeys("801120", "801780", BenchmarkIndustryWeightPort.UNMAPPED_KEY);
        assertThat(weights.get("801120")).isEqualByComparingTo("0.3");
        assertThat(weights.get("801780")).isEqualByComparingTo("0.5");
        assertThat(weights.get(BenchmarkIndustryWeightPort.UNMAPPED_KEY)).isEqualByComparingTo("0.2");
        assertThat(weights.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("1");
    }

    @DisplayName("空数据：byStock 与 industryWeights 均返回空 map")
    @Test
    void givenNoRows_whenQueries_thenEmptyMaps() {
        assertThat(industryMapping.byStock()).isEmpty();
        assertThat(benchmarkIndustryWeight.industryWeights(TEST_INDEX)).isEmpty();
    }

    @DisplayName("NULL weight 防护：全 NULL 行业组 COALESCE 兜底 0 不抛 NPE，部分 NULL 组由 SUM 忽略")
    @Test
    void givenNullWeightConstituents_whenIndustryWeights_thenCoalescedZeroNoNpe() {
        seedMapping("600519", "贵州茅台", "801120", "食品饮料");
        seedMapping("600809", "山西汾酒", "801120", "食品饮料"); // 同行业第二只：凑「部分 NULL 组」
        seedMapping("000001", "平安银行", "801780", "银行");
        seedConstituent("600519", null);  // 部分 NULL：组内 600809=10，SUM 忽略 NULL → 组权重 10
        seedConstituent("600809", "10");
        seedConstituent("000001", "40");
        seedConstituent("600000", null);  // 全 NULL 组（未映射 → UNMAPPED）：SUM 返回 SQL NULL → COALESCE 0

        Map<String, BigDecimal> weights = benchmarkIndustryWeight.industryWeights(TEST_INDEX);

        // 修复前 HashMap.merge(null) NPE → attribution 500；修复后 0 权重行业保留键（实现逐组入表）、
        // 归一化后值为 0，不污染其余行业权重（10/(10+40)=0.2、40/50=0.8）
        assertThat(weights).containsOnlyKeys("801120", "801780", BenchmarkIndustryWeightPort.UNMAPPED_KEY);
        assertThat(weights.get("801120")).isEqualByComparingTo("0.2");
        assertThat(weights.get("801780")).isEqualByComparingTo("0.8");
        assertThat(weights.get(BenchmarkIndustryWeightPort.UNMAPPED_KEY)).isEqualByComparingTo("0");
        assertThat(weights.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("1");
    }

    private void seedMapping(String stockCode, String stockName, String industryCode, String industryName) {
        jdbc.update("INSERT INTO shenwan_industry_mapping (stock_code, stock_name, industry_code, industry_name) "
                + "VALUES (?, ?, ?, ?)", stockCode, stockName, industryCode, industryName);
    }

    private void seedConstituent(String stockCode, String weightPercent) {
        jdbc.update("INSERT INTO index_constituent (index_code, stock_code, stock_name, weight) "
                + "VALUES (?, ?, ?, ?)", TEST_INDEX, stockCode, "",
                weightPercent == null ? null : new BigDecimal(weightPercent));
    }
}
