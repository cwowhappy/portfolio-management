package com.portfolio.invest.intelligence;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.domain.intelligence.AnnouncementExtractResult;
import com.portfolio.invest.domain.intelligence.AnnouncementMetrics;
import com.portfolio.invest.domain.intelligence.AnnouncementRecord;
import com.portfolio.invest.domain.intelligence.AnnouncementRepository;
import com.portfolio.invest.domain.intelligence.AnnouncementType;
import com.portfolio.invest.domain.intelligence.ExtractStatus;
import com.portfolio.invest.domain.intelligence.PageQuery;
import com.portfolio.invest.support.PostgresTestSupport;
import java.math.BigDecimal;
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
 * AnnouncementRepository 五方法真库契约（Testcontainers PG16 + V3 迁移）：标题 LIKE 子串 +
 * trgm 容错双臂中文检索、stock 直列等值、type 走 ann_types JSONB 包含（未抽取条目不命中）、
 * from/to 闭区间、major 过滤、分页 total 与夹紧回显、PENDING 待抽取语义（fetched_at 的
 * lookbackDays 自然日游标窗口 + 无抽取行或 PENDING，major 无关）、upsertExtract 首插与
 * 整体置换（metrics 六字段 BigDecimal/undisclosed 契约与 ann_types 枚举名 JSONB 往返）、
 * findExtractedMajorSince 的 extracted_at 时间轴 ∧ SUCCESS ∧ major 口径、existsExtract
 * 存在性、announcement 删行时 extract FK 级联清除。
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnnouncementRepositoryTest extends PostgresTestSupport {

    private static final ZoneOffset CST = ZoneOffset.ofHours(8);

    @Autowired
    AnnouncementRepository repository;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        // extract 经 FK ON DELETE CASCADE 随 announcement 清除
        jdbc.update("DELETE FROM intelligence_announcement");
    }

    @Test
    @DisplayName("search：关键词双臂命中——子串走 LIKE、一字之差走 trgm 容错，无关标题不命中")
    void givenAnnouncementsWithVariousTitles_whenSearchByChineseKeyword_thenBothArmsHitAndMissExcluded() {
        Long substringHit = insertAnnouncement("kw-sub", "600519", "关于回购公司股份的公告", true,
                "2026-09-28T21:00:00");
        Long trgmHit = insertAnnouncement("kw-trgm", "600519", "回购公司股份公告", true,
                "2026-09-28T20:00:00");
        insertAnnouncement("kw-miss", "300750", "日常经营情况公告", false, "2026-09-28T19:00:00");

        // 「回购」为子串（LIKE 臂命中；两字关键词对长标题 trgm 相似度为 0，命中只可能来自 LIKE）
        var bySubstring = repository.search(query("回购", null, null, null, null, null));
        assertThat(bySubstring.total()).isEqualTo(2);
        assertThat(bySubstring.items()).extracting(AnnouncementRecord::id)
                .containsExactly(substringHit, trgmHit); // published_at 倒序：21:00 在前

        // 「回购公司股票公告」非任何标题子串，仅凭 trgm 容错命中一字之差的「回购公司股份公告」
        var byTrgm = repository.search(query("回购公司股票公告", null, null, null, null, null));
        assertThat(byTrgm.total()).isEqualTo(1);
        assertThat(byTrgm.items()).extracting(AnnouncementRecord::id).containsExactly(trgmHit);
    }

    @Test
    @DisplayName("search：stock 直列等值、type 走 ann_types JSONB 包含（未抽取条目不命中）、major 过滤与组合")
    void givenExtractedAndRawAnnouncements_whenSearchByStockTypeMajor_thenFiltersApply() {
        Long buyback = insertAnnouncementWithExtract("f-buyback", "600519", "回购公告", true,
                "2026-09-28T21:00:00", "[\"BUYBACK\",\"EARNINGS_FLASH\"]", "SUCCESS", "2026-09-28T22:45:00");
        insertAnnouncementWithExtract("f-other", "300750", "定增公告", false,
                "2026-09-28T20:30:00", "[\"PLACEMENT\"]", "SUCCESS", "2026-09-28T22:45:00");
        insertAnnouncement("f-raw", "600519", "未抽取的增持公告", false, "2026-09-28T20:00:00");

        // stock：announcement 表直列等值（含未抽取条目）
        assertThat(repository.search(query(null, "600519", null, null, null, null)).total())
                .isEqualTo(2);
        // type：extract 侧 ann_types JSONB 包含；未抽取条目无标签不命中
        var byType = repository.search(query(null, null, AnnouncementType.BUYBACK, null, null, null));
        assertThat(byType.total()).isEqualTo(1);
        AnnouncementRecord first = byType.items().getFirst();
        assertThat(first.id()).isEqualTo(buyback);
        assertThat(first.stockCode()).isEqualTo("600519");
        assertThat(first.stockName()).isEqualTo("贵州茅台");
        assertThat(first.annTypeSource()).isEqualTo("季度报告");
        assertThat(first.pdfUrl()).endsWith("f-buyback.pdf");
        assertThat(first.major()).isTrue();
        assertThat(first.annTypes()).containsExactly(
                AnnouncementType.BUYBACK, AnnouncementType.EARNINGS_FLASH);
        // major：采集侧栏目映射预判的重大类型
        assertThat(repository.search(query(null, null, null, null, null, true)).total()).isEqualTo(1);
        assertThat(repository.search(query(null, null, null, null, null, false)).total()).isEqualTo(2);
        // 组合：stock + type
        assertThat(repository.search(query(null, "600519", AnnouncementType.BUYBACK, null, null, null))
                .total()).isEqualTo(1);
        // 无过滤：全量 published_at 倒序
        assertThat(repository.search(PageQuery.of(1, 20)).items())
                .extracting(AnnouncementRecord::externalId)
                .containsExactly("f-buyback", "f-other", "f-raw");
    }

    @Test
    @DisplayName("search：from/to 闭区间（Asia/Shanghai 自然日折算）过滤 published_at")
    void givenAnnouncementsAcrossDays_whenSearchByFromTo_thenInclusiveRangeHits() {
        insertAnnouncement("d-27", "600519", "27日公告", false, "2026-09-27T10:00:00");
        insertAnnouncement("d-28", "600519", "28日深夜公告", false, "2026-09-28T23:30:00");
        insertAnnouncement("d-29", "600519", "29日凌晨公告", false, "2026-09-29T00:05:00");
        insertAnnouncement("d-30", "600519", "30日公告", false, "2026-09-30T10:00:00");

        var page = repository.search(query(null, null, null,
                LocalDate.of(2026, 9, 28), LocalDate.of(2026, 9, 29), null));

        assertThat(page.items()).extracting(AnnouncementRecord::externalId)
                .containsExactly("d-29", "d-28"); // 28 日含当日深夜、29 日含凌晨
    }

    @Test
    @DisplayName("search：分页 total/条目数/页码回显，page 与 pageSize 夹紧（page≥1、1..100）")
    void givenFiveAnnouncements_whenSearchPaged_thenTotalAndClampedEchoReturned() {
        for (int i = 1; i <= 5; i++) {
            insertAnnouncement("p-" + i, "600519", "第" + i + "号公告", false, "2026-09-2" + i + "T09:00:00");
        }

        var page2 = repository.search(PageQuery.of(2, 2));

        assertThat(page2.total()).isEqualTo(5);
        assertThat(page2.items()).hasSize(2);
        assertThat(page2.page()).isEqualTo(2);
        assertThat(page2.pageSize()).isEqualTo(2);
        assertThat(page2.items()).extracting(AnnouncementRecord::externalId)
                .containsExactly("p-3", "p-2"); // 倒序第 3、2 新

        // page=0 → 1；pageSize=500 → 100（夹紧后仍回显夹紧值）
        var clamped = repository.search(PageQuery.of(0, 500));
        assertThat(clamped.page()).isEqualTo(1);
        assertThat(clamped.pageSize()).isEqualTo(100);
        assertThat(clamped.items()).hasSize(5);
    }

    @Test
    @DisplayName("findPendingForExtraction：3 日游标窗口内无抽取行或 PENDING 可选中（major 无关），终态与窗口外陈年不取，limit 截断")
    void givenMixedExtractStatusAndDays_whenFindPendingForExtraction_thenWindowPendingCappedByLimit() {
        LocalDate day = LocalDate.of(2026, 9, 28);
        Long noneMajor = insertAnnouncement("e-none", "600519", "无抽取行公告", true, "2026-09-28T08:00:00");
        setFetchedAt(noneMajor, "2026-09-28T09:00:00");
        Long pendingMinor = insertAnnouncementWithExtract("e-pending", "600519", "待抽取公告", false,
                "2026-09-28T08:00:00", "[]", "PENDING", null);
        setFetchedAt(pendingMinor, "2026-09-28T10:00:00");
        Long success = insertAnnouncementWithExtract("e-success", "600519", "已成功公告", true,
                "2026-09-28T08:00:00", "[]", "SUCCESS", "2026-09-28T10:30:00");
        setFetchedAt(success, "2026-09-28T11:00:00");
        Long failed = insertAnnouncementWithExtract("e-failed", "600519", "已失败公告", true,
                "2026-09-28T08:00:00", "[]", "FAILED", "2026-09-28T10:40:00");
        setFetchedAt(failed, "2026-09-28T12:00:00");
        // 前日（27 日）PENDING：3 日窗口（26~28）内，续抽可选中
        Long prevDay = insertAnnouncementWithExtract("e-prev-day", "600519", "前日积压待抽取公告", true,
                "2026-09-27T08:00:00", "[]", "PENDING", null);
        setFetchedAt(prevDay, "2026-09-27T09:00:00");
        // 4 天前（25 日）PENDING：窗口外陈年条目，不再重试
        Long tooOld = insertAnnouncementWithExtract("e-too-old", "600519", "四天前陈年待抽取公告", true,
                "2026-09-25T08:00:00", "[]", "PENDING", null);
        setFetchedAt(tooOld, "2026-09-25T09:00:00");

        List<AnnouncementRecord> pendingList = repository.findPendingForExtraction(day, 3, 10);

        // major 无关（major=true 无行与 major=false PENDING 均选中）；id 升序稳定
        assertThat(pendingList).extracting(AnnouncementRecord::id)
                .containsExactly(noneMajor, pendingMinor, prevDay);
        assertThat(pendingList.getFirst().status()).isNull(); // 无抽取行
        assertThat(pendingList.get(1).status()).isEqualTo(ExtractStatus.PENDING);
        assertThat(pendingList.get(2).status()).isEqualTo(ExtractStatus.PENDING);
        // FAILED 为终态不重试、SUCCESS 不重复抽取、窗口外陈年条目不取
        assertThat(repository.findPendingForExtraction(day, 3, 1)).hasSize(1);
        // lookback=1 收窄回仅当日（窗口下界算术：day-（lookback-1））
        assertThat(repository.findPendingForExtraction(day, 1, 10))
                .extracting(AnnouncementRecord::id).containsExactly(noneMajor, pendingMinor);
    }

    @Test
    @DisplayName("upsertExtract：首次即插入并全量往返（metrics 六字段/undisclosed 契约、ann_types 枚举名、pdf_text）；重复调用无论旧状态整体置换")
    void givenPriorExtractOrNone_whenUpsertExtract_thenInsertOrReplaceLatest() {
        Long noRow = insertAnnouncement("u-norow", "600519", "2026年半年度报告摘要", true, "2026-09-28T21:00:00");
        Long hasPending = insertAnnouncementWithExtract("u-pending", "600519", "占位行公告", true,
                "2026-09-28T21:00:00", "[]", "PENDING", null);
        Instant extractedAt = at("2026-09-28T22:45:00").toInstant();
        AnnouncementMetrics metrics = new AnnouncementMetrics(
                new BigDecimal("128.56"), new BigDecimal("31.20"), new BigDecimal("25.30"),
                new BigDecimal("30.05"), null, "每10股派2元", List.of("毛利率"));

        repository.upsertExtract(noRow, new AnnouncementExtractResult(metrics,
                List.of(AnnouncementType.PERIODIC_REPORT, AnnouncementType.EARNINGS_FLASH),
                "营业收入 128.56 亿元，净利润 31.20 亿元……", ExtractStatus.SUCCESS,
                "deepseek-chat", extractedAt));

        AnnouncementRecord saved = repository.search(query("半年度报告", null, null, null, null, null))
                .items().getFirst();
        assertThat(saved.status()).isEqualTo(ExtractStatus.SUCCESS);
        assertThat(saved.model()).isEqualTo("deepseek-chat");
        assertThat(saved.extractedAt()).isEqualTo(extractedAt);
        assertThat(saved.pdfText()).startsWith("营业收入 128.56 亿元");
        assertThat(saved.annTypes()).containsExactly(
                AnnouncementType.PERIODIC_REPORT, AnnouncementType.EARNINGS_FLASH);
        assertThat(saved.metrics().revenueYi()).isEqualByComparingTo("128.56");
        assertThat(saved.metrics().netProfitYi()).isEqualByComparingTo("31.20");
        assertThat(saved.metrics().netProfitYoyPct()).isEqualByComparingTo("25.30");
        assertThat(saved.metrics().deductedProfitYi()).isEqualByComparingTo("30.05");
        assertThat(saved.metrics().grossMarginPct()).as("未披露字段为 null（严禁编造）").isNull();
        assertThat(saved.metrics().dividendDesc()).isEqualTo("每10股派2元");
        assertThat(saved.metrics().undisclosed()).containsExactly("毛利率");

        // 占位 PENDING 行 → SUCCESS → FAILED：无论旧状态一律置换，分析字段清空（metrics 落 NULL 列）
        repository.upsertExtract(hasPending, new AnnouncementExtractResult(metrics,
                List.of(AnnouncementType.PERIODIC_REPORT), "文本", ExtractStatus.SUCCESS,
                "deepseek-chat", extractedAt));
        repository.upsertExtract(hasPending, new AnnouncementExtractResult(null, List.of(),
                null, ExtractStatus.FAILED, "deepseek-chat", extractedAt));

        AnnouncementRecord replaced = repository.search(query("占位行", null, null, null, null, null))
                .items().getFirst();
        assertThat(replaced.status()).isEqualTo(ExtractStatus.FAILED);
        assertThat(replaced.metrics()).isNull();
        assertThat(replaced.pdfText()).isNull();
        assertThat(replaced.annTypes()).isEmpty();
        // 仍是一行（UNIQUE(announcement_id)），两次 upsert 不增行
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM intelligence_announcement_extract WHERE announcement_id=?",
                Integer.class, hasPending)).isEqualTo(1);
    }

    @Test
    @DisplayName("findExtractedMajorSince：extracted_at≥since（含边界）∧ SUCCESS ∧ major=true；时间轴按抽取而非发布（盘后抽取的当日公告命中）")
    void givenMixedMajorStatusAndTimes_whenFindExtractedMajorSince_thenOnlyMajorSuccessSinceExtractedAt() {
        Instant since = at("2026-09-28T22:40:00").toInstant();
        // 发布于 since 之前、抽取于 since 之后：按 extracted_at 时间轴命中（推送消费口径）
        Long late = insertAnnouncement("m-late", "600519", "盘后完成的重大抽取公告", true, "2026-09-28T20:00:00");
        // 恰在 since 边界完成抽取：>= 含边界
        Long boundary = insertAnnouncement("m-boundary", "600519", "边界时刻抽取公告", true, "2026-09-28T19:00:00");
        // 非重大（采集侧未预判）：即使 SUCCESS 也不推送
        insertAnnouncementWithExtract("m-minor", "600519", "非重大公告", false,
                "2026-09-28T20:00:00", "[\"OTHER\"]", "SUCCESS", "2026-09-28T22:45:00");
        // FAILED 终态 / since 之前完成抽取 / PENDING 占位：均不命中
        insertAnnouncementWithExtract("m-failed", "600519", "抽取失败的重大公告", true,
                "2026-09-28T20:00:00", "[\"BUYBACK\"]", "FAILED", "2026-09-28T22:45:00");
        insertAnnouncementWithExtract("m-before", "600519", "早于窗口完成抽取的重大公告", true,
                "2026-09-28T18:00:00", "[\"BUYBACK\"]", "SUCCESS", "2026-09-28T22:30:00");
        insertAnnouncementWithExtract("m-pending", "600519", "待抽取的重大公告", true,
                "2026-09-28T20:00:00", "[\"BUYBACK\"]", "PENDING", null);

        AnnouncementMetrics metrics = new AnnouncementMetrics(
                new BigDecimal("50.00"), null, null, null, null, null,
                List.of("净利润", "同比增速", "扣非净利润", "毛利率"));
        repository.upsertExtract(late, new AnnouncementExtractResult(metrics,
                List.of(AnnouncementType.BUYBACK), "回购文本", ExtractStatus.SUCCESS,
                "deepseek-chat", at("2026-09-28T22:45:00").toInstant()));
        repository.upsertExtract(boundary, new AnnouncementExtractResult(metrics,
                List.of(AnnouncementType.BUYBACK), "回购文本", ExtractStatus.SUCCESS,
                "deepseek-chat", since));

        List<AnnouncementRecord> majors = repository.findExtractedMajorSince(since);

        // extracted_at 倒序：22:45 在前、边界 22:40 在后
        assertThat(majors).extracting(AnnouncementRecord::externalId)
                .containsExactly("m-late", "m-boundary");
        assertThat(majors.getFirst().metrics().revenueYi()).isEqualByComparingTo("50.00");
        assertThat(majors.getFirst().annTypes()).containsExactly(AnnouncementType.BUYBACK);
        assertThat(repository.findExtractedMajorSince(at("2026-09-28T22:50:00").toInstant())).isEmpty();
    }

    @Test
    @DisplayName("existsExtract：无抽取行 false；有抽取行（含 PENDING 占位与仓库 upsert 建行）true")
    void givenAnnouncementWithAndWithoutExtract_whenExistsExtract_thenReflectsExtractRowPresence() {
        Long noRow = insertAnnouncement("x-none", "600519", "无抽取行公告", true, "2026-09-28T09:00:00");
        Long pending = insertAnnouncementWithExtract("x-pending", "600519", "占位行公告", true,
                "2026-09-28T09:00:00", "[]", "PENDING", null);

        assertThat(repository.existsExtract(noRow)).isFalse();
        assertThat(repository.existsExtract(pending)).isTrue();

        // 仓库 upsert 建行后即存在（任一状态）
        repository.upsertExtract(noRow, new AnnouncementExtractResult(
                null, List.of(), null, ExtractStatus.PENDING, null, null));
        assertThat(repository.existsExtract(noRow)).isTrue();
    }

    @Test
    @DisplayName("删除 announcement 行时 extract 经 FK ON DELETE CASCADE 级联清除，其余公告的抽取行保留")
    void givenAnnouncementWithExtract_whenDeleteAnnouncementRow_thenExtractCascades() {
        Long withExtract = insertAnnouncementWithExtract("c-both", "600519", "级联验证公告", true,
                "2026-09-28T09:00:00", "[\"BUYBACK\"]", "SUCCESS", "2026-09-28T22:45:00");
        insertAnnouncementWithExtract("c-keep", "600519", "保留公告", true,
                "2026-09-28T09:00:00", "[\"BUYBACK\"]", "SUCCESS", "2026-09-28T22:45:00");

        jdbc.update("DELETE FROM intelligence_announcement WHERE id=?", withExtract);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM intelligence_announcement_extract", Integer.class))
                .as("被删 announcement 的 extract 级联清除，其余保留").isEqualTo(1);
    }

    // ── fixture 助手 ───────────────────────────────────────────────

    /** published_at/fetched_at/extracted_at 均为上海时区（CST+8）字符串。 */
    private static OffsetDateTime at(String cstLiteral) {
        return OffsetDateTime.parse(cstLiteral + "+08:00");
    }

    /** 公告检索过滤器便捷构造（industryCode/minImportance 为新闻侧字段，公告侧不用）。 */
    private static PageQuery query(String keyword, String stock, AnnouncementType type,
                                   LocalDate from, LocalDate to, Boolean major) {
        return new PageQuery(1, 20, keyword, stock, null, from, to, null, type, major);
    }

    private Long insertAnnouncement(String externalId, String stockCode, String title,
                                    boolean major, String publishedCst) {
        jdbc.update("INSERT INTO intelligence_announcement"
                        + "(source, external_id, stock_code, stock_name, title, ann_type_source,"
                        + " major, published_at, pdf_url)"
                        + " VALUES('cninfo', ?, ?, ?, ?, '季度报告', ?, ?, ?)",
                externalId, stockCode, stockNameOf(stockCode), title, major, at(publishedCst),
                "https://static.cninfo.com.cn/" + externalId + ".pdf");
        return jdbc.queryForObject(
                "SELECT id FROM intelligence_announcement WHERE external_id=?", Long.class, externalId);
    }

    /** 既有抽取行（ann_types 为 JSON 数组文本；extracted_at 可空——PENDING 占位行无值）。 */
    private Long insertAnnouncementWithExtract(String externalId, String stockCode, String title,
                                               boolean major, String publishedCst,
                                               String annTypesJson, String status, String extractedAtCst) {
        Long id = insertAnnouncement(externalId, stockCode, title, major, publishedCst);
        jdbc.update("INSERT INTO intelligence_announcement_extract"
                        + "(announcement_id, ann_types, status, model, extracted_at)"
                        + " VALUES(?, ?::jsonb, ?, 'test-model', ?)",
                id, annTypesJson, status, extractedAtCst == null ? null : at(extractedAtCst));
        return id;
    }

    private static String stockNameOf(String stockCode) {
        return "600519".equals(stockCode) ? "贵州茅台" : "宁德时代";
    }

    private void setFetchedAt(Long id, String fetchedAtCst) {
        jdbc.update("UPDATE intelligence_announcement SET fetched_at=? WHERE id=?", at(fetchedAtCst), id);
    }
}
