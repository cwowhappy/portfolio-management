package com.portfolio.invest.intelligence;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.domain.intelligence.Direction;
import com.portfolio.invest.domain.intelligence.ExtractStatus;
import com.portfolio.invest.domain.intelligence.NewsExtractResult;
import com.portfolio.invest.domain.intelligence.NewsRecord;
import com.portfolio.invest.domain.intelligence.NewsRepository;
import com.portfolio.invest.domain.intelligence.PageQuery;
import com.portfolio.invest.support.PostgresTestSupport;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * NewsRepository 六方法真库契约（Testcontainers PG16 + V3 迁移）：trgm 中文关键词检索、
 * stock/industry JSONB 包含与 minImportance 过滤、分页 total/夹紧回显、PENDING 待抽取
 * 语义（lookbackDays 自然日游标窗口的 fetched_at + 无行或 PENDING）、upsertExtract
 * 首插/整体置换、deleteRawBefore 的 published_at 口径与 extract 级联、findMajorSince
 * 阈值/状态/时间窗、countExtractedByDate 的 Asia/Shanghai 自然日口径。
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NewsRepositoryTest extends PostgresTestSupport {

    private static final ZoneOffset CST = ZoneOffset.ofHours(8);

    @Autowired
    NewsRepository repository;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        // extract 经 FK ON DELETE CASCADE 随 raw 清除
        jdbc.update("DELETE FROM intelligence_news_raw");
    }

    @Test
    @DisplayName("search：中文关键词「政策利率」命中「下调政策利率」标题，无关标题不命中")
    void givenNewsWithVariousTitles_whenSearchByChineseKeyword_thenOnlyRelevantTitleHits() {
        Long hit = insertRaw("kw-hit", "央行宣布下调政策利率10个基点", at("2026-09-28T09:00:00"));
        insertRaw("kw-miss", "某上市公司发布年度业绩预告", at("2026-09-28T10:00:00"));

        var page = repository.search(new PageQuery(1, 20, "政策利率", null, null, null, null, null));

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).extracting(NewsRecord::id).containsExactly(hit);
        assertThat(page.items().getFirst().title()).contains("下调政策利率");
    }

    @Test
    @DisplayName("search：stockCode/industryCode 走 JSONB 包含、minImportance 为下限过滤")
    void givenExtractedNews_whenSearchByStockIndustryImportance_thenFiltersApply() {
        insertRawWithExtract("f-moutai", "白酒龙头提价", "2026-09-28T09:00:00",
                "[\"600519\"]", "[\"801250\"]", 85, "SUCCESS");
        insertRawWithExtract("f-catl", "电池新贵扩产", "2026-09-28T10:00:00",
                "[\"300750\"]", "[\"801730\"]", 40, "SUCCESS");

        assertThat(repository.search(new PageQuery(1, 20, null, "600519", null, null, null, null)).total())
                .isEqualTo(1);
        assertThat(repository.search(new PageQuery(1, 20, null, null, "801730", null, null, null)).total())
                .isEqualTo(1);
        var majorOnly = repository.search(new PageQuery(1, 20, null, null, null, null, null, 80));
        assertThat(majorOnly.total()).isEqualTo(1);
        assertThat(majorOnly.items().getFirst().stockCodes()).containsExactly("600519");
        assertThat(majorOnly.items().getFirst().importance()).isEqualTo(85);
        assertThat(majorOnly.items().getFirst().direction()).isEqualTo(Direction.BULLISH);
        // 无过滤：全量命中且 published_at 倒序
        assertThat(repository.search(PageQuery.of(1, 20)).items())
                .extracting(NewsRecord::externalId).containsExactly("f-catl", "f-moutai");
    }

    @Test
    @DisplayName("search：分页 total/条目数/页码回显，page 与 pageSize 夹紧（page≥1、1..100）")
    void givenFiveNews_whenSearchPaged_thenTotalAndClampedEchoReturned() {
        for (int i = 1; i <= 5; i++) {
            insertRaw("p-" + i, "第" + i + "条新闻", at("2026-09-2" + i + "T09:00:00"));
        }

        var page2 = repository.search(PageQuery.of(2, 2));

        assertThat(page2.total()).isEqualTo(5);
        assertThat(page2.items()).hasSize(2);
        assertThat(page2.page()).isEqualTo(2);
        assertThat(page2.pageSize()).isEqualTo(2);
        assertThat(page2.items()).extracting(NewsRecord::externalId)
                .containsExactly("p-3", "p-2"); // 倒序第 3、2 新

        // page=0 → 1；pageSize=500 → 100（夹紧后仍回显夹紧值）
        var clamped = repository.search(PageQuery.of(0, 500));
        assertThat(clamped.page()).isEqualTo(1);
        assertThat(clamped.pageSize()).isEqualTo(100);
        assertThat(clamped.items()).hasSize(5);
    }

    @Test
    @DisplayName("search：from/to 闭区间（Asia/Shanghai 自然日折算）过滤 published_at")
    void givenNewsAcrossDays_whenSearchByFromTo_thenInclusiveRangeHits() {
        insertRaw("d-27", "27日新闻", at("2026-09-27T10:00:00"));
        insertRaw("d-28", "28日新闻", at("2026-09-28T23:30:00"));
        insertRaw("d-29", "29日凌晨新闻", at("2026-09-29T00:05:00"));
        insertRaw("d-30", "30日新闻", at("2026-09-30T10:00:00"));

        var page = repository.search(new PageQuery(1, 20, null, null, null,
                LocalDate.of(2026, 9, 28), LocalDate.of(2026, 9, 29), null));

        assertThat(page.items()).extracting(NewsRecord::externalId)
                .containsExactly("d-29", "d-28"); // 28 日含当日深夜、29 日含凌晨
    }

    @Test
    @DisplayName("findPendingForExtraction：3 日游标窗口内无抽取行或 PENDING 可选中（含前日），SUCCESS/FAILED 终态与 4 日前陈年条目不取，limit 截断")
    void givenMixedExtractStatusAndDays_whenFindPendingForExtraction_thenWindowPendingCappedByLimit() {
        LocalDate day = LocalDate.of(2026, 9, 28);
        Long none = insertRaw("e-none", "无抽取行", at("2026-09-28T08:00:00"));
        setFetchedAt(none, "2026-09-28T09:00:00");
        Long pending = insertRawWithStatus("e-pending", "待抽取", "2026-09-28T08:00:00", "PENDING");
        setFetchedAt(pending, "2026-09-28T10:00:00");
        Long success = insertRawWithStatus("e-success", "已成功", "2026-09-28T08:00:00", "SUCCESS");
        setFetchedAt(success, "2026-09-28T11:00:00");
        Long failed = insertRawWithStatus("e-failed", "已失败", "2026-09-28T08:00:00", "FAILED");
        setFetchedAt(failed, "2026-09-28T12:00:00");
        // 前日（27 日）PENDING：3 日窗口（26~28）内，续抽可选中
        Long prevDay = insertRawWithStatus("e-prev-day", "前日积压待抽取", "2026-09-28T08:00:00", "PENDING");
        setFetchedAt(prevDay, "2026-09-27T09:00:00");
        // 4 天前（25 日）PENDING：窗口外陈年条目，不再重试
        Long tooOld = insertRawWithStatus("e-too-old", "四天前陈年待抽取", "2026-09-28T08:00:00", "PENDING");
        setFetchedAt(tooOld, "2026-09-25T09:00:00");

        List<NewsRecord> pendingList = repository.findPendingForExtraction(day, 3, 10);

        assertThat(pendingList).extracting(NewsRecord::id).containsExactly(none, pending, prevDay);
        assertThat(pendingList.getFirst().status()).isNull(); // 无抽取行
        assertThat(pendingList.get(1).status()).isEqualTo(ExtractStatus.PENDING);
        assertThat(pendingList.get(2).status()).isEqualTo(ExtractStatus.PENDING);
        // FAILED 为终态不重试、SUCCESS 不重复抽取、窗口外陈年条目不取
        assertThat(repository.findPendingForExtraction(day, 3, 1)).hasSize(1);
        // lookback=1 收窄回仅当日（窗口下界算术：day-（lookback-1））
        assertThat(repository.findPendingForExtraction(day, 1, 10))
                .extracting(NewsRecord::id).containsExactly(none, pending);
    }

    @Test
    @DisplayName("upsertExtract：首次即插入；重复调用整体置换（含 PENDING→SUCCESS→FAILED 与字段覆盖）")
    void givenPriorExtractOrNone_whenUpsertExtract_thenInsertOrReplaceLatest() {
        Long noRow = insertRaw("u-norow", "首次抽取新闻", at("2026-09-28T09:00:00"));
        Long hasPending = insertRawWithStatus("u-pending", "占位行新闻", "2026-09-28T09:00:00", "PENDING");
        Instant extractedAt = at("2026-09-28T16:40:00").toInstant();

        repository.upsertExtract(noRow, NewsExtractResult.success("RATE_CUT",
                List.of("600519"), List.of("801250"), "降息利好",
                Direction.BULLISH, List.of("10bp"), 85, "deepseek-chat", extractedAt));

        var first = repository.search(new PageQuery(1, 20, "首次抽取", null, null, null, null, null));
        assertThat(first.total()).isEqualTo(1);
        NewsRecord saved = first.items().getFirst();
        assertThat(saved.status()).isEqualTo(ExtractStatus.SUCCESS);
        assertThat(saved.eventType()).isEqualTo("RATE_CUT");
        assertThat(saved.extractSummary()).isEqualTo("降息利好");
        assertThat(saved.keyNumbers()).containsExactly("10bp");
        assertThat(saved.extractedAt()).isEqualTo(extractedAt);
        assertThat(repository.countExtractedByDate(LocalDate.of(2026, 9, 28))).isEqualTo(1);

        // 占位 PENDING 行：覆盖为 SUCCESS；再覆盖为 FAILED（importance 清空）——无论旧状态一律置换
        repository.upsertExtract(hasPending, NewsExtractResult.success("EARNINGS", List.of(),
                List.of(), "业绩预增", null, List.of(), 60, "deepseek-chat", extractedAt));
        repository.upsertExtract(hasPending, new NewsExtractResult(null, null, null, null,
                null, null, null, ExtractStatus.FAILED, "deepseek-chat", extractedAt));

        var replaced = repository.search(new PageQuery(1, 20, "占位行", null, null, null, null, null));
        assertThat(replaced.items().getFirst().status()).isEqualTo(ExtractStatus.FAILED);
        assertThat(replaced.items().getFirst().importance()).isNull();
        assertThat(replaced.items().getFirst().extractSummary()).isNull();
        // 仍是一行（UNIQUE(news_raw_id)），两次 upsert 计数不变
        assertThat(repository.countExtractedByDate(LocalDate.of(2026, 9, 28))).isEqualTo(2);
    }

    @Test
    @DisplayName("deleteRawBefore：只删 published_at 早于 cutoff 的 raw 且 extract 级联清除，返回删除行数")
    void givenOldAndFreshNews_whenDeleteRawBefore_thenOnlyOlderDeletedAndExtractCascades() {
        Instant now = Instant.now();
        insertRawWithExtract("c-old", "九十一天前的旧闻", minusDaysIso(now, 91),
                "[\"600000\"]", "[\"801010\"]", 70, "SUCCESS");
        insertRawWithExtract("c-fresh", "昨天的新闻", minusDaysIso(now, 1),
                "[\"600000\"]", "[\"801010\"]", 70, "SUCCESS");

        long deleted = repository.deleteRawBefore(now.minus(java.time.Duration.ofDays(90)));

        assertThat(deleted).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM intelligence_news_raw", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM intelligence_news_extract", Integer.class))
                .as("旧 raw 的 extract 应级联删除").isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM intelligence_news_raw WHERE external_id='c-old'",
                Integer.class)).isZero();
    }

    @Test
    @DisplayName("findMajorSince：只取 since 起、SUCCESS 且 importance≥majorAt 的新闻")
    void givenNewsWithVariousImportance_whenFindMajorSince_thenOnlySuccessAtOrAboveThresholdSince() {
        Instant since = at("2026-09-28T00:00:00").toInstant();
        insertRawWithExtract("m-in", "盘后大利好", "2026-09-28T21:00:00",
                "[\"600519\"]", "[\"801250\"]", 85, "SUCCESS");
        insertRawWithExtract("m-low", "小动静", "2026-09-28T21:00:00",
                "[]", "[]", 40, "SUCCESS");
        insertRawWithExtract("m-failed", "抽取失败的高分新闻", "2026-09-28T21:00:00",
                "[]", "[]", 95, "FAILED");
        insertRawWithExtract("m-before", "since 之前的大利好", "2026-09-27T23:00:00",
                "[\"600519\"]", "[\"801250\"]", 95, "SUCCESS");

        List<NewsRecord> majors = repository.findMajorSince(since, 80);

        assertThat(majors).extracting(NewsRecord::externalId).containsExactly("m-in");
        assertThat(majors.getFirst().importance()).isEqualTo(85);
        assertThat(repository.findMajorSince(since, 90)).isEmpty();
    }

    @Test
    @DisplayName("countExtractedByDate：按 Asia/Shanghai 自然日统计 extracted_at")
    void givenExtractsWithTimestamps_whenCountExtractedByDate_thenCountsByShanghaiDay() {
        // 上海 09-29 02:00（UTC 28 日 18:00）→ 计入 29 日：跨 UTC 日界仍按上海口径
        insertRawWithExtractAt("cnt-a", "甲", "2026-09-28T09:00:00", "2026-09-28T18:00:00Z");
        // 上海 09-30 01:00（UTC 29 日 17:00）→ 不计入 29 日
        insertRawWithExtractAt("cnt-b", "乙", "2026-09-28T09:00:00", "2026-09-29T17:00:00Z");
        // 上海 09-29 10:00 → 计入 29 日
        insertRawWithExtractAt("cnt-c", "丙", "2026-09-28T09:00:00", "2026-09-29T02:00:00Z");

        assertThat(repository.countExtractedByDate(LocalDate.of(2026, 9, 29))).isEqualTo(2);
        assertThat(repository.countExtractedByDate(LocalDate.of(2026, 9, 30))).isEqualTo(1);
    }

    @Test
    @DisplayName("countPendingInWindow：同 findPendingForExtraction 口径计 PENDING 数（无行/PENDING 计入，终态与窗口外不计）")
    void givenMixedStatusAndDays_whenCountPendingInWindow_thenCountsWindowPendingOnly() {
        LocalDate day = LocalDate.of(2026, 9, 28);
        Long none = insertRaw("cp-none", "窗口内无抽取行的新闻", at("2026-09-28T08:00:00"));
        setFetchedAt(none, "2026-09-28T09:00:00");
        Long pending = insertRawWithStatus("cp-pending", "窗口内占位待抽取新闻", "2026-09-28T08:00:00", "PENDING");
        setFetchedAt(pending, "2026-09-28T10:00:00");
        Long success = insertRawWithStatus("cp-success", "窗口内已抽取成功新闻", "2026-09-28T08:00:00", "SUCCESS");
        setFetchedAt(success, "2026-09-28T11:00:00");
        Long failed = insertRawWithStatus("cp-failed", "窗口内已失败终态新闻", "2026-09-28T08:00:00", "FAILED");
        setFetchedAt(failed, "2026-09-28T12:00:00");
        // 前日（27 日）PENDING：3 日窗口（26~28）内计入
        Long prevDay = insertRawWithStatus("cp-prev-day", "前日积压待抽取新闻", "2026-09-28T08:00:00", "PENDING");
        setFetchedAt(prevDay, "2026-09-27T09:00:00");

        // 无抽取行 + PENDING + 前日积压 = 3（SUCCESS/FAILED 终态不计）
        assertThat(repository.countPendingInWindow(day, 3)).isEqualTo(3);
        // lookback=1 收窄回仅当日（窗口下界算术与 findPendingForExtraction 一致）
        assertThat(repository.countPendingInWindow(day, 1)).isEqualTo(2);
    }

    // ── fixture 助手 ───────────────────────────────────────────────

    /** published_at/fetched_at 均为上海时区（CST+8）字符串。 */
    private static OffsetDateTime at(String cstLiteral) {
        return OffsetDateTime.parse(cstLiteral + "+08:00");
    }

    private static String minusDaysIso(Instant base, int days) {
        return base.minus(java.time.Duration.ofDays(days))
                .atOffset(ZoneOffset.ofHours(8)).toLocalDateTime().toString();
    }

    private Long insertRaw(String externalId, String title, OffsetDateTime publishedAt) {
        jdbc.update("INSERT INTO intelligence_news_raw(source, external_id, title, published_at, stock_tags)"
                        + " VALUES('test', ?, ?, ?, '[{\"code\":\"600519\"}]'::jsonb)",
                externalId, title, publishedAt);
        return jdbc.queryForObject(
                "SELECT id FROM intelligence_news_raw WHERE external_id=?", Long.class, externalId);
    }

    /** PENDING 占位行（extracted_at 为 NULL——生产流中占位行只可能由人工 SQL 产生）。 */
    private Long insertRawWithStatus(String externalId, String title, String publishedCst, String status) {
        return insertRawWithExtractInternal(externalId, title, publishedCst, null, null, null,
                status, null);
    }

    private Long insertRawWithExtract(String externalId, String title, String publishedCst,
                                      String stockCodesJson, String industryCodesJson,
                                      Integer importance, String status) {
        OffsetDateTime published = at(publishedCst);
        return insertRawWithExtractInternal(externalId, title, publishedCst, stockCodesJson,
                industryCodesJson, importance, status, published);
    }

    private Long insertRawWithExtractAt(String externalId, String title, String publishedCst,
                                        String extractedAtUtcLiteral) {
        return insertRawWithExtractInternal(externalId, title, publishedCst, "[]", "[]", 50,
                "SUCCESS", OffsetDateTime.parse(extractedAtUtcLiteral));
    }

    private Long insertRawWithExtractInternal(String externalId, String title, String publishedCst,
                                              String stockCodesJson, String industryCodesJson,
                                              Integer importance, String status,
                                              OffsetDateTime extractedAt) {
        Long id = insertRaw(externalId, title, at(publishedCst));
        jdbc.update("INSERT INTO intelligence_news_extract"
                        + "(news_raw_id, stock_codes, industry_codes, summary, direction,"
                        + " importance, status, model, extracted_at)"
                        + " VALUES(?, ?::jsonb, ?::jsonb, '摘要', 'BULLISH', ?, ?, 'test-model', ?)",
                id, stockCodesJson == null ? "[]" : stockCodesJson,
                industryCodesJson == null ? "[]" : industryCodesJson,
                importance, status, extractedAt);
        return id;
    }

    private void setFetchedAt(Long id, String fetchedAtCst) {
        jdbc.update("UPDATE intelligence_news_raw SET fetched_at=? WHERE id=?", at(fetchedAtCst), id);
    }
}
