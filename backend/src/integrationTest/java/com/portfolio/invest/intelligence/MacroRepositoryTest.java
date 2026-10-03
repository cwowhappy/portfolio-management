package com.portfolio.invest.intelligence;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.domain.intelligence.MacroCalendarEntry;
import com.portfolio.invest.domain.intelligence.MacroPoint;
import com.portfolio.invest.domain.intelligence.MacroRepository;
import com.portfolio.invest.support.PostgresTestSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * MacroRepository 五方法真库契约（Testcontainers PG16 + V1/V3 迁移）：
 * findLatestPerIndicator 的每指标最新一期 + 国债收益率跨表只读合成
 * （treasury_yield_curve 1Y/10Y 各自最新交易日、部分采集日单期限不丢点）、
 * findSeries 的 period 倒序与 limit 截断、findCalendarBetween 对 V3 日历种子
 * （75 行，2026Q4~2027 五指标）的闭区间/同日多指标排序/月末推导、
 * insertSourceSwitch 落库与 findLatestSwitch 的最新行方向口径。
 *
 * <p>共享容器卫生：macro_series / source_switch 仅本类读写（整表清）；
 * treasury_yield_curve 只清本查询读的 1Y/10Y 两期限（他测试种下的行不动的
 * 先例照 RiskFreeRateAdapterIntegrationTest）；macro_calendar 对种子<b>只读不写</b>
 * ——IntelligenceMigrationTest 断言其精确 75 行，写入（即便事后清理）会引入跨类顺序耦合。
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MacroRepositoryTest extends PostgresTestSupport {

    @Autowired
    MacroRepository repository;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM intelligence_macro_series");
        jdbc.update("DELETE FROM intelligence_source_switch");
        // TY 合成只读 1Y/10Y 两期限——清这两期限即全确定性（e2e 种子等残留 10Y 行不干扰）
        jdbc.update("DELETE FROM treasury_yield_curve WHERE term IN ('1Y', '10Y')");
    }

    @Test
    @DisplayName("findLatestPerIndicator：多指标多期各取最新一期，指标码升序；国债表空时不追加 TY 点")
    void givenMultiIndicatorSeries_whenFindLatestPerIndicator_thenLatestPerIndicatorAsc() {
        insertSeries("CPI", "2026-07", "MONTH", "102.1000", "0.4", null, null);
        insertSeries("CPI", "2026-08", "MONTH", "102.2000", "0.5", null, null);
        insertSeries("CPI", "2026-09", "MONTH", "102.3000", "0.6", null, null);
        insertSeries("LPR", "2026-08", "MONTH", "3.0500", null, null, null);
        insertSeries("LPR", "2026-09", "MONTH", "3.0000", null, null, null);
        insertSeries("PMI", "2026-09", "MONTH", "50.2000", null, null, null);

        List<MacroPoint> latest = repository.findLatestPerIndicator();

        assertThat(latest).extracting(MacroPoint::indicator).containsExactly("CPI", "LPR", "PMI");
        assertThat(latest).extracting(MacroPoint::period)
                .containsExactly("2026-09", "2026-09", "2026-09");
        assertThat(latest).extracting(MacroPoint::value)
                .usingRecursiveComparison()
                .isEqualTo(List.of(new BigDecimal("102.3000"), new BigDecimal("3.0000"),
                        new BigDecimal("50.2000")));
    }

    @Test
    @DisplayName("findLatestPerIndicator：treasury 1Y/10Y 各自最新交易日合成 TY1Y/TY10Y（DAY、yield 为值、无 yoy/源字段）")
    void givenTreasuryCurveRows_whenFindLatestPerIndicator_thenSynthesizesTyPoints() {
        insertTreasury(LocalDate.of(2026, 9, 28), "1Y", "1.8500");
        insertTreasury(LocalDate.of(2026, 9, 28), "10Y", "2.9000");
        insertTreasury(LocalDate.of(2026, 9, 29), "1Y", "1.8600");
        insertTreasury(LocalDate.of(2026, 9, 29), "10Y", "2.9100");
        insertSeries("CPI", "2026-09", "MONTH", "102.3000", "0.6", null, null);

        List<MacroPoint> latest = repository.findLatestPerIndicator();

        // macro 指标在前，TY 两点追加在尾
        assertThat(latest).extracting(MacroPoint::indicator).containsExactly("CPI", "TY1Y", "TY10Y");
        MacroPoint ty1y = latest.get(1);
        MacroPoint ty10y = latest.get(2);
        assertThat(ty1y.period()).isEqualTo("2026-09-29");
        assertThat(ty1y.periodType()).isEqualTo("DAY");
        assertThat(ty1y.value()).isEqualByComparingTo("1.86");
        assertThat(ty10y.period()).isEqualTo("2026-09-29");
        assertThat(ty10y.value()).isEqualByComparingTo("2.91");
        // 合成点无同比/源字段——缺席为 null 不编造
        assertThat(ty1y.yoy()).isNull();
        assertThat(ty1y.sourceUrl()).isNull();
        assertThat(ty1y.sourceNote()).isNull();
        assertThat(ty10y.yoy()).isNull();
    }

    @Test
    @DisplayName("findLatestPerIndicator：部分采集日仅 1Y 有行——TY1Y 前移最新日，TY10Y 仍取自身最新交易日")
    void givenPartialTreasuryDay_whenFindLatestPerIndicator_thenEachTermTakesOwnLatest() {
        insertTreasury(LocalDate.of(2026, 9, 29), "1Y", "1.8600");
        insertTreasury(LocalDate.of(2026, 9, 29), "10Y", "2.9100");
        // 09-30 采集只落了 1Y（部分日）
        insertTreasury(LocalDate.of(2026, 9, 30), "1Y", "1.8700");

        List<MacroPoint> latest = repository.findLatestPerIndicator();

        assertThat(latest).extracting(MacroPoint::indicator).containsExactly("TY1Y", "TY10Y");
        assertThat(latest.get(0).period()).isEqualTo("2026-09-30");
        assertThat(latest.get(0).value()).isEqualByComparingTo("1.87");
        assertThat(latest.get(1).period()).isEqualTo("2026-09-29");
        assertThat(latest.get(1).value()).isEqualByComparingTo("2.91");
    }

    @Test
    @DisplayName("findSeries：period 倒序取前 limit 期，limit 超总数时全量；字段（periodType/yoy/源两栏）原样回读")
    void givenIndicatorHistory_whenFindSeries_thenDescOrderCappedByLimit() {
        insertSeries("CPI", "2026-07", "MONTH", "102.1000", "0.4", null, null);
        insertSeries("CPI", "2026-08", "MONTH", "102.2000", "0.5",
                "https://data.eastmoney.com/cpi", "东方财富-CPI月度");
        insertSeries("CPI", "2026-09", "MONTH", "102.3000", "0.6", null, null);

        List<MacroPoint> top2 = repository.findSeries("CPI", 2);

        assertThat(top2).extracting(MacroPoint::period).containsExactly("2026-09", "2026-08");
        MacroPoint august = top2.get(1);
        assertThat(august.periodType()).isEqualTo("MONTH");
        assertThat(august.value()).isEqualByComparingTo("102.2");
        assertThat(august.yoy()).isEqualByComparingTo("0.5");
        assertThat(august.sourceUrl()).isEqualTo("https://data.eastmoney.com/cpi");
        assertThat(august.sourceNote()).isEqualTo("东方财富-CPI月度");
        // limit 超总数：全量三期
        assertThat(repository.findSeries("CPI", 60)).hasSize(3);
        // 其他指标不串
        assertThat(repository.findSeries("LPR", 10)).isEmpty();
    }

    @Test
    @DisplayName("findCalendarBetween：V3 种子 2026-10 全月五指标齐（同日 CPI/PPI 稳定排序、月末 PMI），区间两端闭")
    void givenSeededCalendar_whenFindCalendarBetweenOctober2026_thenFiveEntriesInclusiveBounds() {
        List<MacroCalendarEntry> october =
                repository.findCalendarBetween(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));

        assertThat(october).extracting(MacroCalendarEntry::indicator)
                .containsExactly("CPI", "PPI", "AFMI", "LPR", "PMI");
        assertThat(october).extracting(e -> e.expectedDate().toString())
                .containsExactly("2026-10-09", "2026-10-09", "2026-10-12", "2026-10-20", "2026-10-31");
        MacroCalendarEntry cpi = october.getFirst();
        assertThat(cpi.frequency()).isEqualTo("MONTH");
        assertThat(cpi.sourceSite()).isEqualTo("国家统计局");
        assertThat(cpi.updatedAt()).isNotNull();
        assertThat(october.get(3).sourceSite()).isEqualTo("中国人民银行"); // LPR 央行口径

        // 单日闭区间：CPI/PPI 同日两行
        assertThat(repository.findCalendarBetween(
                LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 9)))
                .extracting(MacroCalendarEntry::indicator).containsExactly("CPI", "PPI");
        // 种子外区间为空
        assertThat(repository.findCalendarBetween(
                LocalDate.of(2028, 1, 1), LocalDate.of(2028, 1, 31))).isEmpty();
    }

    @Test
    @DisplayName("findCalendarBetween：PMI 月末推导（2027-02 取 28 日，不存在 29+ 行）")
    void givenSeededCalendar_whenFindCalendarBetweenFebruary2027_thenPmiMonthEndOnly() {
        List<MacroCalendarEntry> february =
                repository.findCalendarBetween(LocalDate.of(2027, 2, 1), LocalDate.of(2027, 2, 28));

        assertThat(february).extracting(MacroCalendarEntry::indicator)
                .containsExactly("CPI", "PPI", "AFMI", "LPR", "PMI");
        assertThat(february.getLast().expectedDate()).isEqualTo(LocalDate.of(2027, 2, 28));
        // 月末推导不出非法日期：2027 非闰年，2 月最后一天只有 PMI 一行（无 29+ 行）
        assertThat(repository.findCalendarBetween(
                LocalDate.of(2027, 2, 28), LocalDate.of(2027, 2, 28)))
                .extracting(MacroCalendarEntry::indicator).containsExactly("PMI");
    }

    @Test
    @DisplayName("insertSourceSwitch：落库默认 switched_at；findLatestSwitch 取最新行含方向，未知指标 empty")
    void givenSourceSwitchInserts_whenFindLatestSwitch_thenLatestRowWithDirection() {
        repository.insertSourceSwitch("AFMI", "socfin", "m2", "社融主源连续2个发布期失败（降级）");
        repository.insertSourceSwitch("AFMI", "m2", "socfin", "回切验证最新行方向口径");
        repository.insertSourceSwitch("CPI", "stats", "eastmoney", "统计局接口限流切换");

        // 第一行 AFMI 回拨到确定的历史时刻：两行同指标时 findLatestSwitch 必须取较新的第二行
        jdbc.update("UPDATE intelligence_source_switch SET switched_at = '2026-09-01 08:00:00+08'"
                + " WHERE indicator='AFMI' AND to_source='m2'");

        var afmi = repository.findLatestSwitch("AFMI");
        assertThat(afmi).isPresent();
        // 方向语义（T7 告警恰一次的判定基准）：最新行 to=m2 即降级态——此处最新行为回切行
        assertThat(afmi.get().fromSource()).isEqualTo("m2");
        assertThat(afmi.get().toSource()).isEqualTo("socfin");
        assertThat(afmi.get().switchedAt()).isAfter(java.time.Instant.parse("2026-09-01T00:00:00Z"));
        assertThat(repository.findLatestSwitch("CPI")).isPresent();
        // 无留痕指标：empty（巡检以「从未切换」= 健康态起步）
        assertThat(repository.findLatestSwitch("PMI")).isEmpty();
        // 行数核验：三行留痕（重复切换即多行，幂等由服务层「状态变化才插」保证）
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM intelligence_source_switch", Integer.class)).isEqualTo(3);
    }

    // ── fixture 助手 ───────────────────────────────────────────────

    private void insertSeries(String indicator, String period, String periodType,
                              String value, String yoy, String sourceUrl, String sourceNote) {
        jdbc.update("""
                INSERT INTO intelligence_macro_series
                    (indicator, period, period_type, value, yoy, source_url, source_note)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                indicator, period, periodType, new BigDecimal(value),
                yoy == null ? null : new BigDecimal(yoy), sourceUrl, sourceNote);
    }

    private void insertTreasury(LocalDate tradingDay, String term, String yield) {
        jdbc.update("INSERT INTO treasury_yield_curve (trading_day, term, yield) VALUES (?, ?, ?)",
                tradingDay, term, new BigDecimal(yield));
    }
}
