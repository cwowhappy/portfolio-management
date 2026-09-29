package com.portfolio.invest.intelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.invest.application.intelligence.BriefGenerationService;
import com.portfolio.invest.application.intelligence.IntelligenceChatPort;
import com.portfolio.invest.application.intelligence.TradingCalendarPort;
import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.intelligence.BriefRepository;
import com.portfolio.invest.domain.intelligence.BriefStatus;
import com.portfolio.invest.domain.intelligence.DailyBrief;
import com.portfolio.invest.support.PostgresTestSupport;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 盘前简报生成真库集成（Testcontainers PG16 + @MockitoBean 桩 LLM 端口，绝不真调 DeepSeek）：
 * seed 昨夜今晨抽取结果 → 生成 → BriefRepository.findByDate 断言三态路径
 * （GENERATED 六节/top_stocks JSONB 往返、EMPTY_SIMPLE 简版、FAILED 直接留档往返）、
 * 幂等重跑跳过、非交易日（日历可裁决）不生成；trading_calendar 为 collector Alembic
 * 跨服务契约表（backend Flyway 不建），由 fixture 按契约 DDL 建表——TradingCalendarPortImpl
 * 的有行/节假日权威 false/表空与表缺失降级周一~周五四路径一并以固定历史日期钉死。
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BriefGenerationIntegrationTest extends PostgresTestSupport {

    private static final ZoneId CST = ZoneId.of("Asia/Shanghai");

    @Autowired
    BriefGenerationService service;
    @Autowired
    BriefRepository briefRepository;
    @Autowired
    TradingCalendarPort tradingCalendar;
    @Autowired
    InvestProperties props;
    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    IntelligenceChatPort chatPort;

    @BeforeEach
    void cleanTables() {
        // trading_calendar：collector Alembic 契约表（trade_date DATE PRIMARY KEY），backend 测试库自建
        jdbc.execute("CREATE TABLE IF NOT EXISTS trading_calendar (trade_date DATE PRIMARY KEY)");
        jdbc.update("DELETE FROM intelligence_daily_brief");
        jdbc.update("DELETE FROM intelligence_news_raw"); // extract 经 FK 级联清除
        jdbc.update("DELETE FROM trading_calendar");
    }

    @Test
    @DisplayName("给定昨夜今晨抽取结果跨档，when交易日生成，thenGENERATED六节归档且top_stocks JSONB往返")
    void givenOvernightExtractedNews_whenGenerate_thenGeneratedArchivedWithTopStocks() {
        LocalDate today = LocalDate.now(CST);
        seedTradingDay(today);
        seedNews("i-macro", "央行下调政策利率", "MACRO", "[\"600519\"]", 90, "BULLISH");
        seedNews("i-earn", "茅台业绩超预期", "EARNINGS", "[\"600519\"]", 85, "BULLISH");
        seedNews("i-ma", "宁德时代并购落地", "M&A", "[\"300750\"]", 82, "BEARISH");
        seedNews("i-industry", "光伏板块排产回暖", "INDUSTRY", "[]", 60, "NEUTRAL");
        seedNews("i-liquid", "北向资金净流入", "LIQUIDITY", "[\"600519\"]", 55, "BULLISH");
        seedNews("i-noise", "低重要度碎片消息", "EARNINGS", "[]", 30, null); // 低于关注线不入池
        when(chatPort.complete(any(), any()))
                .thenReturn(Optional.of(new IntelligenceChatPort.ChatOutcome("导语。", 50)));

        service.generateBrief();

        Optional<DailyBrief> found = briefRepository.findByDate(today);
        assertThat(found).isPresent();
        DailyBrief brief = found.get();
        assertThat(brief.status()).isEqualTo(BriefStatus.GENERATED);
        assertThat(brief.failReason()).isNull();
        assertThat(brief.model()).isEqualTo(props.getLlm().getModel());
        assertThat(brief.generatedAt()).isNotNull();
        // 5+1 节固定顺序 + 节导语 + 行格式（IGNORE 档的碎片消息不入档）
        assertThat(brief.contentMd()).contains("## 宏观与政策", "## 行业与板块", "## 公司要闻与公告",
                "## 资金与市场", "## 海外与大宗", "## 其他要闻", "导语。");
        assertThat(brief.contentMd())
                .contains("- [央行下调政策利率](https://example.com/i-macro)（重要度 90 · 利好）");
        assertThat(brief.contentMd()).doesNotContain("低重要度碎片消息");
        // top_stocks 频次快照：600519×3 > 300750×1
        assertThat(brief.topStocks()).containsExactly("600519", "300750");
        // JSONB 原始列可查（决策 #23 检索快照落库形态）
        assertThat(jdbc.queryForObject(
                "SELECT top_stocks::text FROM intelligence_daily_brief WHERE trade_date = ?",
                String.class, today)).contains("600519");
        // 只为 4 个非空节生成导语
        verify(chatPort, times(4)).complete(any(), any());
    }

    @Test
    @DisplayName("给定当日已生成，when重跑生成，then幂等跳过档案不重复生成")
    void givenBriefAlreadyGenerated_whenGenerateAgain_thenSkipped() {
        LocalDate today = LocalDate.now(CST);
        seedTradingDay(today);
        seedNews("i-one", "单条重大情报", "MACRO", "[\"600519\"]", 90, "BULLISH");
        when(chatPort.complete(any(), any()))
                .thenReturn(Optional.of(new IntelligenceChatPort.ChatOutcome("导语。", 50)));

        service.generateBrief();
        OffsetDateTime generatedAt = jdbc.queryForObject(
                "SELECT generated_at FROM intelligence_daily_brief WHERE trade_date = ?",
                OffsetDateTime.class, today);
        service.generateBrief();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM intelligence_daily_brief WHERE trade_date = ?",
                Integer.class, today)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT generated_at FROM intelligence_daily_brief WHERE trade_date = ?",
                OffsetDateTime.class, today)).isEqualTo(generatedAt); // 原档未动
        verify(chatPort, times(1)).complete(any(), any()); // 重跑不再调 LLM
    }

    @Test
    @DisplayName("给定窗口内无候选，when交易日生成，thenEMPTY_SIMPLE简版留档今日无重大情报")
    void givenNoCandidates_whenGenerate_thenEmptySimpleArchived() {
        LocalDate today = LocalDate.now(CST);
        seedTradingDay(today);

        service.generateBrief();

        Optional<DailyBrief> found = briefRepository.findByDate(today);
        assertThat(found).isPresent();
        assertThat(found.get().status()).isEqualTo(BriefStatus.EMPTY_SIMPLE);
        assertThat(found.get().contentMd()).contains("今日无重大情报").contains("数据截止");
        assertThat(found.get().topStocks()).isEmpty();
        assertThat(found.get().failReason()).isNull();
        verify(chatPort, never()).complete(any(), any());
    }

    @Test
    @DisplayName("给定生成失败留档，when仓库按日读取，thenFAILED状态与fail_reason真库往返")
    void givenFailedBriefSaved_whenFindByDate_thenRoundTrippedWithReason() {
        LocalDate day = LocalDate.now(CST);
        briefRepository.save(DailyBrief.failed(day, "db down", "test-model",
                Instant.parse("2026-09-29T00:00:00Z")));

        Optional<DailyBrief> found = briefRepository.findByDate(day);
        assertThat(found).isPresent();
        assertThat(found.get().status()).isEqualTo(BriefStatus.FAILED);
        assertThat(found.get().failReason()).isEqualTo("db down");
        assertThat(found.get().contentMd()).isNotBlank(); // content_md NOT NULL：失败同留档
        assertThat(found.get().generatedAt()).isEqualTo(Instant.parse("2026-09-29T00:00:00Z"));
    }

    @Test
    @DisplayName("给定日历可裁决而当日无行（节假日），when生成，then非交易日不生成不落档")
    void givenCalendarCoveredButNotTradingDay_whenGenerate_thenNoBriefArchived() {
        seedTradingDay(LocalDate.now(CST).plusDays(40)); // 日历覆盖当下，但当日无行=非交易日

        service.generateBrief();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM intelligence_daily_brief", Integer.class)).isZero();
        verify(chatPort, never()).complete(any(), any());
    }

    @Test
    @DisplayName("给定日历有行/节假日/表空/表缺失四情形，when端口判交易日，then各路径语义正确")
    void givenVariousCalendarStates_whenIsTradingDay_thenSemanticsHold() {
        // 有行 → 交易日
        jdbc.update("INSERT INTO trading_calendar VALUES (DATE '2026-09-29'), (DATE '2026-10-09')");
        assertThat(tradingCalendar.isTradingDay(LocalDate.of(2026, 9, 29))).isTrue();
        // 2026-10-01（周四国庆）：日历覆盖当下（有 ≥ 该日的行）而当日无行 → 权威非交易日
        // （若走了周一~周五降级该周四会判 true，断言 false 即钉死裁决来自日历）
        assertThat(LocalDate.of(2026, 10, 1).getDayOfWeek().getValue()).isEqualTo(4);
        assertThat(tradingCalendar.isTradingDay(LocalDate.of(2026, 10, 1))).isFalse();

        // 表空（collector 首刷前冷启动）→ 降级周一~周五近似
        jdbc.update("DELETE FROM trading_calendar");
        assertThat(tradingCalendar.isTradingDay(LocalDate.of(2026, 10, 7))).isTrue();  // 周三
        assertThat(tradingCalendar.isTradingDay(LocalDate.of(2026, 10, 10))).isFalse(); // 周六

        // 表缺失（跨服务契约未落）→ 同降级且不抛
        jdbc.execute("DROP TABLE trading_calendar");
        try {
            assertThat(tradingCalendar.isTradingDay(LocalDate.of(2026, 10, 7))).isTrue(); // 周三降级
        } finally {
            jdbc.execute("CREATE TABLE IF NOT EXISTS trading_calendar (trade_date DATE PRIMARY KEY)");
        }
    }

    // ── fixture 助手 ───────────────────────────────────────────────

    /** 当日 raw（published_at = 1 小时前，恒落「昨日 15:00 → 今日 08:00」窗口内）+ SUCCESS 抽取行。 */
    private void seedNews(String externalId, String title, String eventType,
                          String stockCodesJson, int importance, String direction) {
        jdbc.update("""
                INSERT INTO intelligence_news_raw(source, external_id, title, summary, published_at, url)
                VALUES('test', ?, ?, ?, now() - interval '1 hour', ?)
                """, externalId, title, title + "的摘要", "https://example.com/" + externalId);
        Long id = jdbc.queryForObject(
                "SELECT id FROM intelligence_news_raw WHERE external_id = ?", Long.class, externalId);
        jdbc.update("""
                INSERT INTO intelligence_news_extract
                    (news_raw_id, event_type, stock_codes, summary, direction, importance, status, model, extracted_at)
                VALUES(?, ?, ?::jsonb, ?, ?, ?, 'SUCCESS', 'test-model', now())
                """, id, eventType, stockCodesJson, title + "的AI摘要", direction, importance);
    }

    private void seedTradingDay(LocalDate date) {
        jdbc.update("INSERT INTO trading_calendar VALUES (?)", java.sql.Date.valueOf(date));
    }
}
