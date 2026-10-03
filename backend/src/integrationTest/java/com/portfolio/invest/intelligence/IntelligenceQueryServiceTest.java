package com.portfolio.invest.intelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.portfolio.invest.application.intelligence.AnnouncementFilter;
import com.portfolio.invest.application.intelligence.BriefFilter;
import com.portfolio.invest.application.intelligence.IntelligenceQueryService;
import com.portfolio.invest.application.intelligence.PolicyFilter;
import com.portfolio.invest.domain.intelligence.BriefStatus;
import com.portfolio.invest.domain.intelligence.IntelligenceErrorCode;
import com.portfolio.invest.domain.intelligence.IntelligenceException;
import com.portfolio.invest.domain.intelligence.PolicyDirection;
import com.portfolio.invest.support.PostgresTestSupport;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * P4 四区块查询服务真库集成（Testcontainers PG16 + V3 迁移）：简报档 top_stocks JSONB
 * contains 过滤（决策 #23 检索维度）与 content_md trgm 检索、trade_date 闭区间与分页
 * total、briefDetail 缺档 NOT_FOUND、政策 direction 过滤（服务→仓库全链）、标的聚合
 * 三路命中（新闻 stock_codes JSONB contains / 公告 stock_code 等值 / 政策路恒 0 带说明）
 * 与空态引导 flag、pageSize 夹紧 1..100 真库生效。
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IntelligenceQueryServiceTest extends PostgresTestSupport {

    private static final ZoneOffset CST = ZoneOffset.ofHours(8);

    @Autowired
    IntelligenceQueryService service;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        // extract 经 FK 级联清除；policy_event 对 raw 无级联（长期保留），须先删事件行
        jdbc.update("DELETE FROM intelligence_policy_event");
        jdbc.update("DELETE FROM intelligence_policy_raw");
        jdbc.update("DELETE FROM intelligence_daily_brief");
        jdbc.update("DELETE FROM intelligence_news_raw");
        jdbc.update("DELETE FROM intelligence_announcement");
    }

    @Test
    @DisplayName("给定三档不同 top_stocks 的简报，when按标的过滤，then仅命中 top_stocks contains 该标的的档")
    void givenBriefsWithVariousTopStocks_whenBriefsByStock_thenOnlyContainingHits() {
        saveBrief(LocalDate.of(2026, 9, 28), "# 速递 茅台与宁德齐涨", "[\"600519\",\"300750\"]");
        saveBrief(LocalDate.of(2026, 9, 25), "# 速递 宁德时代放量", "[\"300750\"]");
        saveBrief(LocalDate.of(2026, 9, 24), "今日无重大情报", null); // 空简版无快照

        var page = service.briefs(new BriefFilter(null, null, null, "600519", 1, 20));

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().tradeDate()).isEqualTo(LocalDate.of(2026, 9, 28));
        assertThat(page.items().getFirst().topStocks()).containsExactly("600519", "300750");
    }

    @Test
    @DisplayName("给定含关键词正文的简报，when按 q 检索，then content_md 子串命中、无关档不命中")
    void givenBriefsWithVariousContent_whenBriefsByKeyword_thenContentMdSubstringHits() {
        saveBrief(LocalDate.of(2026, 9, 28), "# 速递 机器人板块异动明显", "[\"300024\"]");
        saveBrief(LocalDate.of(2026, 9, 25), "# 速递 白酒板块承压", "[\"600519\"]");

        var page = service.briefs(new BriefFilter("机器人", null, null, null, 1, 20));

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items().getFirst().tradeDate()).isEqualTo(LocalDate.of(2026, 9, 28));
    }

    @Test
    @DisplayName("给定跨日简报与越界 pageSize，when简报检索，then trade_date 闭区间过滤且 pageSize 夹紧 100")
    void givenBriefsAcrossDaysAndHugePageSize_whenBriefs_thenRangeFilterAndClamp() {
        saveBrief(LocalDate.of(2026, 9, 24), "# 速递 9-24", "[\"600519\"]");
        saveBrief(LocalDate.of(2026, 9, 25), "# 速递 9-25", "[\"600519\"]");
        saveBrief(LocalDate.of(2026, 9, 28), "# 速递 9-28", "[\"600519\"]");

        var page = service.briefs(new BriefFilter(null, LocalDate.of(2026, 9, 25),
                LocalDate.of(2026, 9, 28), null, 0, 9999));

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items()).extracting(v -> v.tradeDate())
                .containsExactly(LocalDate.of(2026, 9, 28), LocalDate.of(2026, 9, 25)); // 倒序
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.pageSize()).isEqualTo(100); // 夹紧回显
    }

    @Test
    @DisplayName("给定已归档与未归档交易日，when简报详情，then归档全字段视图、未归档抛 NOT_FOUND")
    void givenArchivedAndMissingBrief_whenBriefDetail_thenFullViewOrNotFound() {
        saveBrief(LocalDate.of(2026, 9, 28), "# 速递 全文内容", "[\"600519\"]");

        var detail = service.briefDetail(LocalDate.of(2026, 9, 28));

        assertThat(detail.contentMd()).isEqualTo("# 速递 全文内容");
        assertThat(detail.topStocks()).containsExactly("600519");
        assertThat(detail.status()).isEqualTo(BriefStatus.GENERATED);
        assertThat(detail.generatedAt()).isNotNull();

        assertThatExceptionOfType(IntelligenceException.class)
                .isThrownBy(() -> service.briefDetail(LocalDate.of(2026, 10, 1)))
                .satisfies(e -> assertThat(e.code()).isEqualTo(IntelligenceErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("给定双向政策事件，when按 direction 过滤，then仅同向事件命中且九字段视图映射")
    void givenPoliciesAcrossDirections_whenPoliciesByDirection_thenOnlySameDirectionHits() {
        insertPolicyWithEvent("p-easing", "央行降息的通知", "2026-10-01T09:00:00", "EASING");
        insertPolicyWithEvent("p-tightening", "加强监管的意见", "2026-10-02T09:00:00", "TIGHTENING");

        var page = service.policies(new PolicyFilter(null, null, null, PolicyDirection.EASING, 1, 20));

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().title()).isEqualTo("央行降息的通知");
        assertThat(page.items().getFirst().direction()).isEqualTo(PolicyDirection.EASING);
    }

    @Test
    @DisplayName("给定新闻与公告两路命中，when标的聚合，then各路精确计数与最新5条、政策路恒0带说明")
    void givenNewsAndAnnouncementHits_whenStockIntel_thenTwoWayCountsAndPolicyNote() {
        insertNews("s-n1", "茅台三季报预增", "[\"600519\"]", "2026-09-28T13:00:00");
        insertNews("s-n2", "茅台获批新工艺", "[\"600519\"]", "2026-09-27T13:00:00");
        insertNews("s-n3", "宁德时代扩产", "[\"300750\"]", "2026-09-28T14:00:00");
        insertAnnouncement("s-a1", "600519", "茅台回购公告", "2026-09-28T21:00:00");
        insertAnnouncement("s-a2", "000001", "平安银行公告", "2026-09-28T20:00:00");
        insertPolicyWithEvent("s-p1", "某政策通知", "2026-10-01T09:00:00", "EASING");

        var intel = service.stockIntel("600519");

        assertThat(intel.newsTotal()).isEqualTo(2);
        assertThat(intel.news()).extracting(v -> v.title())
                .containsExactly("茅台三季报预增", "茅台获批新工艺"); // published_at 倒序
        assertThat(intel.announcementTotal()).isEqualTo(1);
        assertThat(intel.announcements()).extracting(v -> v.title()).containsExactly("茅台回购公告");
        assertThat(intel.policyTotal()).as("政策事件无标的维度——恒 0").isZero();
        assertThat(intel.policyNote()).isNotBlank();
        assertThat(intel.empty()).isFalse();
    }

    @Test
    @DisplayName("给定无任何命中的标的，when标的聚合，then三路全零且 empty=true 空态引导")
    void givenStockWithoutAnyHit_whenStockIntel_thenEmptyGuidance() {
        insertNews("s-other", "宁德时代扩产", "[\"300750\"]", "2026-09-28T14:00:00");

        var intel = service.stockIntel("600519");

        assertThat(intel.newsTotal()).isZero();
        assertThat(intel.announcementTotal()).isZero();
        assertThat(intel.policyTotal()).isZero();
        assertThat(intel.news()).isEmpty();
        assertThat(intel.announcements()).isEmpty();
        assertThat(intel.empty()).isTrue();
    }

    @Test
    @DisplayName("给定公告页过滤器，when公告分页查询，then stock 直列等值与 major 组合真库过滤")
    void givenAnnouncementFilters_whenAnnouncementsPage_thenStockAndMajorApply() {
        insertAnnouncement("w-1", "600519", "茅台重大公告", true, "2026-09-28T21:00:00");
        insertAnnouncement("w-2", "600519", "茅台日常公告", false, "2026-09-28T20:00:00");
        insertAnnouncement("w-3", "000001", "平安银行公告", true, "2026-09-28T19:00:00");

        var page = service.announcementsPage(new AnnouncementFilter(null, "600519", null,
                null, null, true, 1, 20));

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items().getFirst().title()).isEqualTo("茅台重大公告");
        assertThat(page.items().getFirst().stockName()).isEqualTo("贵州茅台");
    }

    // ── fixture 助手 ───────────────────────────────────────────────

    /** published_at 等时刻为上海时区（CST+8）字符串。 */
    private static OffsetDateTime at(String cstLiteral) {
        return OffsetDateTime.parse(cstLiteral + "+08:00");
    }

    /** topStocksJson 为 JSON 数组文本（如 ["600519","300750"]——元素须带引号，@> 按字符串包含）；null=无快照。 */
    private void saveBrief(LocalDate tradeDate, String contentMd, String topStocksJson) {
        jdbc.update("""
                INSERT INTO intelligence_daily_brief(trade_date, content_md, top_stocks, status, model, generated_at)
                VALUES(?, ?, ?::jsonb, 'GENERATED', 'test-model', ?)
                """, tradeDate, contentMd, topStocksJson, at("2026-09-28T00:30:00"));
    }

    /** SUCCESS 抽取的新闻（stock_codes 为 JSON 数组文本）。 */
    private void insertNews(String externalId, String title, String stockCodesJson, String publishedCst) {
        jdbc.update("""
                INSERT INTO intelligence_news_raw(source, external_id, title, summary, published_at, url)
                VALUES('test', ?, ?, ?, ?, ?)
                """, externalId, title, title + "的摘要", at(publishedCst), "https://x/" + externalId);
        Long id = jdbc.queryForObject(
                "SELECT id FROM intelligence_news_raw WHERE external_id=?", Long.class, externalId);
        jdbc.update("""
                INSERT INTO intelligence_news_extract
                    (news_raw_id, stock_codes, summary, status, model, extracted_at)
                VALUES(?, ?::jsonb, ?, 'SUCCESS', 'test-model', now())
                """, id, stockCodesJson, title + "的AI摘要");
    }

    private void insertAnnouncement(String externalId, String stockCode, String title,
                                    boolean major, String publishedCst) {
        jdbc.update("""
                INSERT INTO intelligence_announcement
                    (source, external_id, stock_code, stock_name, title, ann_type_source, major, published_at, pdf_url)
                VALUES('cninfo', ?, ?, ?, ?, '季度报告', ?, ?, ?)
                """, externalId, stockCode, "600519".equals(stockCode) ? "贵州茅台" : "平安银行",
                title, major, at(publishedCst), "https://x/" + externalId + ".pdf");
    }

    private void insertAnnouncement(String externalId, String stockCode, String title, String publishedCst) {
        insertAnnouncement(externalId, stockCode, title, false, publishedCst);
    }

    private void insertPolicyWithEvent(String externalId, String title, String publishedCst,
                                       String direction) {
        jdbc.update("""
                INSERT INTO intelligence_policy_raw(source, external_id, title, url, published_at, content_text)
                VALUES('pboc', ?, ?, ?, ?, '正文内容')
                """, externalId, title, "https://x/" + externalId, at(publishedCst));
        Long id = jdbc.queryForObject(
                "SELECT id FROM intelligence_policy_raw WHERE external_id=?", Long.class, externalId);
        jdbc.update("""
                INSERT INTO intelligence_policy_event
                    (policy_raw_id, direction, strength, affected_areas, summary, confidence, status, model, extracted_at)
                VALUES(?, ?::varchar, 'MEDIUM', '[]'::jsonb, ?, 'HIGH', 'SUCCESS', 'test-model', now())
                """, id, direction, title + "的政策摘要");
    }
}
