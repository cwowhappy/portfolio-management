package com.portfolio.invest.intelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.portfolio.invest.domain.intelligence.ExtractStatus;
import com.portfolio.invest.domain.intelligence.PolicyConfidence;
import com.portfolio.invest.domain.intelligence.PolicyDirection;
import com.portfolio.invest.domain.intelligence.PolicyEvent;
import com.portfolio.invest.domain.intelligence.PolicyExtractResult;
import com.portfolio.invest.domain.intelligence.PolicyRecord;
import com.portfolio.invest.domain.intelligence.PolicyRepository;
import com.portfolio.invest.domain.intelligence.PolicyStrength;
import com.portfolio.invest.domain.intelligence.PageQuery;
import com.portfolio.invest.domain.intelligence.PageResult;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PolicyRepository 三方法真库契约（Testcontainers PG16 + V3 迁移）：PENDING 待抽取语义
 * （lookbackDays 自然日游标窗口按 published_at——raw 表无 fetched_at 列）、upsertExtract
 * 首插/整体置换、searchEvents 的 trgm 中文关键词/direction/from/to 过滤与仅 SUCCESS 口径、
 * isPolicy 由哨兵 summary 派生（表无 is_policy 列）、affected_areas JSONB 读回、
 * FK 无级联删除的长期保留语义（有事件行的 raw 删除被数据库拒绝）。
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PolicyRepositoryTest extends PostgresTestSupport {

    private static final ZoneOffset CST = ZoneOffset.ofHours(8);

    @Autowired
    PolicyRepository repository;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        // 政策长期保留无级联：清理须先删事件行再删 raw（与新闻的 CASCADE 清理相反）
        jdbc.update("DELETE FROM intelligence_policy_event");
        jdbc.update("DELETE FROM intelligence_policy_raw");
    }

    @Test
    @DisplayName("findPendingForExtraction：3 日游标窗口（published_at 口径）内无抽取行或 PENDING 可选中，SUCCESS/FAILED 终态与窗口外陈年条目不取，limit 截断")
    void givenMixedExtractStatusAndDays_whenFindPendingForExtraction_thenWindowPendingCappedByLimit() {
        LocalDate day = LocalDate.of(2026, 10, 3);
        Long none = insertRaw("e-none", "无抽取行的政策通知", at("2026-10-03T08:00:00"));
        Long pending = insertRawWithStatus("e-pending", "占位待抽取的政策通知", "2026-10-03T08:30:00", "PENDING");
        Long success = insertRawWithStatus("e-success", "已抽取成功的政策通知", "2026-10-03T09:00:00", "SUCCESS");
        Long failed = insertRawWithStatus("e-failed", "已失败终态的政策通知", "2026-10-03T09:30:00", "FAILED");
        // 前日（10-02）PENDING：3 日窗口（10-01~03）内，续抽可选中
        Long prevDay = insertRawWithStatus("e-prev-day", "前日积压待抽取的政策通知", "2026-10-02T09:00:00", "PENDING");
        // 4 天前（09-29）PENDING：窗口外陈年条目，不再重试
        Long tooOld = insertRawWithStatus("e-too-old", "四天前陈年待抽取的政策通知", "2026-09-29T09:00:00", "PENDING");

        List<PolicyRecord> pendingList = repository.findPendingForExtraction(day, 3, 10);

        assertThat(pendingList).extracting(PolicyRecord::id).containsExactly(none, pending, prevDay);
        assertThat(pendingList.getFirst().status()).isNull(); // 无抽取行
        assertThat(pendingList.get(1).status()).isEqualTo(ExtractStatus.PENDING);
        assertThat(pendingList.get(2).status()).isEqualTo(ExtractStatus.PENDING);
        assertThat(pendingList.getFirst().contentText()).contains("正文内容"); // raw 行读模型带正文
        // FAILED 为终态不重试、SUCCESS 不重复抽取、窗口外陈年条目不取
        assertThat(repository.findPendingForExtraction(day, 3, 1)).hasSize(1);
        // lookback=1 收窄回仅当日（窗口下界算术：day-（lookback-1））
        assertThat(repository.findPendingForExtraction(day, 1, 10))
                .extracting(PolicyRecord::id).containsExactly(none, pending);
    }

    @Test
    @DisplayName("upsertExtract：首次即插入；重复调用整体置换（含 PENDING→SUCCESS→FAILED 与字段覆盖）")
    void givenPriorExtractOrNone_whenUpsertExtract_thenInsertOrReplaceLatest() {
        Long noRow = insertRaw("u-norow", "首次抽取的降准通知", at("2026-10-03T08:00:00"));
        Long hasPending = insertRawWithStatus("u-pending", "占位行的货币政策通知", "2026-10-03T08:30:00", "PENDING");
        Instant extractedAt = at("2026-10-03T09:10:00").toInstant();

        repository.upsertExtract(noRow, PolicyExtractResult.success(PolicyDirection.EASING,
                PolicyStrength.HIGH, List.of("利率", "房地产"), "央行降准0.5个百分点释放长期流动性",
                PolicyConfidence.HIGH, "deepseek-chat", extractedAt));

        var first = repository.searchEvents(new PageQuery(1, 20, "首次抽取", null, null, null, null, null));
        assertThat(first.total()).isEqualTo(1);
        PolicyEvent saved = first.items().getFirst();
        assertThat(saved.status()).isEqualTo(ExtractStatus.SUCCESS);
        assertThat(saved.direction()).isEqualTo(PolicyDirection.EASING);
        assertThat(saved.strength()).isEqualTo(PolicyStrength.HIGH);
        assertThat(saved.affectedAreas()).containsExactly("利率", "房地产");
        assertThat(saved.summary()).isEqualTo("央行降准0.5个百分点释放长期流动性");
        assertThat(saved.confidence()).isEqualTo(PolicyConfidence.HIGH);
        assertThat(saved.isPolicy()).isTrue();
        assertThat(saved.model()).isEqualTo("deepseek-chat");
        assertThat(saved.extractedAt()).isEqualTo(extractedAt);
        assertThat(saved.title()).isEqualTo("首次抽取的降准通知"); // 合并视图带 raw 侧标题
        assertThat(saved.url()).isNotBlank();

        // 占位 PENDING 行：覆盖为 SUCCESS；再覆盖为 FAILED（分析字段清空）——无论旧状态一律置换
        repository.upsertExtract(hasPending, PolicyExtractResult.success(PolicyDirection.TIGHTENING,
                PolicyStrength.MEDIUM, List.of("资本市场"), "规范金融市场秩序",
                PolicyConfidence.LOW, "deepseek-chat", extractedAt));
        repository.upsertExtract(hasPending, new PolicyExtractResult(null, null, List.of(),
                null, null, ExtractStatus.FAILED, "deepseek-chat", extractedAt));

        // FAILED 行不出 searchEvents（仅 SUCCESS 口径），直接查表断言置换结果
        String status = jdbc.queryForObject(
                "SELECT status FROM intelligence_policy_event WHERE policy_raw_id=?", String.class, hasPending);
        assertThat(status).isEqualTo("FAILED");
        Integer nullDirection = jdbc.queryForObject(
                "SELECT count(*) FROM intelligence_policy_event WHERE policy_raw_id=? AND direction IS NULL",
                Integer.class, hasPending);
        assertThat(nullDirection).isEqualTo(1); // 置换后旧分析字段清空
        // 仍是一行（UNIQUE(policy_raw_id)）
        Integer rowCount = jdbc.queryForObject(
                "SELECT count(*) FROM intelligence_policy_event WHERE policy_raw_id=?", Integer.class, hasPending);
        assertThat(rowCount).isEqualTo(1);
    }

    @Test
    @DisplayName("searchEvents：中文关键词「存款准备金率」命中相关标题，无关标题不命中")
    void givenPoliciesWithVariousTitles_whenSearchByChineseKeyword_thenOnlyRelevantTitleHits() {
        Long hit = insertRawWithEvent("kw-hit", "关于下调金融机构存款准备金率的通知",
                "2026-10-02T09:00:00", "EASING", "SUCCESS");
        insertRawWithEvent("kw-miss", "某某领导出席金融论坛并发表讲话",
                "2026-10-02T10:00:00", "NEUTRAL", "SUCCESS");

        var page = repository.searchEvents(new PageQuery(1, 20, "存款准备金率", null, null, null, null, null));

        assertThat(page.total()).isEqualTo(1);
        // id 为事件行主键，断言对齐 raw 外键（fixture 捕获 raw id）
        assertThat(page.items()).extracting(PolicyEvent::policyRawId).containsExactly(hit);
        assertThat(page.items().getFirst().title()).contains("存款准备金率");
    }

    @Test
    @DisplayName("searchEvents：direction 过滤、from/to 闭区间、仅 SUCCESS 行可见、分页 total 回显")
    void givenEventsAcrossDirectionsAndDays_whenSearchEvents_thenFiltersAndPagingApply() {
        Long easing = insertRawWithEvent("f-easing", "央行降息的通知", "2026-10-01T09:00:00",
                "EASING", "SUCCESS");
        Long tightening = insertRawWithEvent("f-tightening", "加强监管的意见", "2026-10-02T09:00:00",
                "TIGHTENING", "SUCCESS");
        Long easingLate = insertRawWithEvent("f-easing-late", "减税降费的政策措施", "2026-10-03T09:00:00",
                "EASING", "SUCCESS");
        // FAILED/PENDING 行与无事件 raw 不进检索面
        insertRawWithEvent("f-failed", "抽取失败的某政策", "2026-10-02T10:00:00",
                "EASING", "FAILED");
        insertRawWithEvent("f-pending", "占位未抽取的某政策", "2026-10-02T11:00:00",
                "EASING", "PENDING");
        insertRaw("f-no-event", "尚无事件的某政策", at("2026-10-02T12:00:00"));

        // direction 过滤：仅 EASING 且按 published_at 倒序（断言对齐 raw 外键）
        var easingPage = repository.searchEvents(new PageQuery(1, 20, null, null, null,
                null, null, null, null, null, PolicyDirection.EASING));
        assertThat(easingPage.total()).isEqualTo(2);
        assertThat(easingPage.items()).extracting(PolicyEvent::policyRawId)
                .containsExactly(easingLate, easing);

        // from/to 闭区间（Asia/Shanghai 自然日折算）：10-01~10-02 命中前两日（10-03 与中间态行排除）
        var rangePage = repository.searchEvents(new PageQuery(1, 20, null, null, null,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2), null, null, null, null));
        assertThat(rangePage.items()).extracting(PolicyEvent::policyRawId)
                .containsExactly(tightening, easing);

        // 分页：SUCCESS 共 3 行，pageSize=2 第 1 页 total=3、条目 2
        PageResult<PolicyEvent> page1 = repository.searchEvents(PageQuery.of(1, 2));
        assertThat(page1.total()).isEqualTo(3);
        assertThat(page1.items()).hasSize(2);
        assertThat(page1.pageSize()).isEqualTo(2);
    }

    @Test
    @DisplayName("searchEvents：isPolicy=false 兜底行（哨兵 summary）派生标记且低置信可见（F12 误收录标注）")
    void givenNonPolicyFallbackRow_whenSearchEvents_thenIsPolicyDerivedFromSentinelSummary() {
        insertRawWithFullEvent("fb-policy", "关于促进民间投资发展的若干意见", "2026-10-02T09:00:00",
                "EASING", "MEDIUM", "[\"民间投资\"]", "鼓励民间资本参与重大项目建设",
                "HIGH", "SUCCESS");
        insertRawWithFullEvent("fb-non-policy", "某领导出席金融论坛并发表讲话", "2026-10-02T10:00:00",
                "NEUTRAL", "LOW", "[]", PolicyEvent.NON_POLICY_SUMMARY, "LOW", "SUCCESS");

        var page = repository.searchEvents(PageQuery.of(1, 20));

        assertThat(page.items()).hasSize(2);
        PolicyEvent nonPolicy = page.items().stream()
                .filter(e -> e.externalId().equals("fb-non-policy")).findFirst().orElseThrow();
        assertThat(nonPolicy.isPolicy()).isFalse();
        assertThat(nonPolicy.confidence()).isEqualTo(PolicyConfidence.LOW);
        assertThat(nonPolicy.summary()).isEqualTo(PolicyEvent.NON_POLICY_SUMMARY);
        PolicyEvent policy = page.items().stream()
                .filter(e -> e.externalId().equals("fb-policy")).findFirst().orElseThrow();
        assertThat(policy.isPolicy()).isTrue();
        assertThat(policy.affectedAreas()).containsExactly("民间投资");
    }

    @Test
    @DisplayName("长期保留：有事件行的 raw 删除被 FK 拒绝（无级联），事件行仍在——政策不滚动清理")
    void givenEventRowExists_whenDeleteRaw_thenRejectedByFkAndEventRetained() {
        Long rawId = insertRawWithEvent("keep-1", "长期保留的政策通知", "2026-10-02T09:00:00",
                "EASING", "SUCCESS");

        // 无 ON DELETE CASCADE：删除被数据库拒绝（长期保留语义由 FK 默认 RESTRICT 兜底）
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> jdbc.update("DELETE FROM intelligence_policy_raw WHERE id=?", rawId));
        Integer eventCount = jdbc.queryForObject(
                "SELECT count(*) FROM intelligence_policy_event WHERE policy_raw_id=?", Integer.class, rawId);
        assertThat(eventCount).isEqualTo(1);
    }

    // ── fixture 助手 ───────────────────────────────────────────────

    /** published_at 为上海时区（CST+8）字符串。 */
    private static OffsetDateTime at(String cstLiteral) {
        return OffsetDateTime.parse(cstLiteral + "+08:00");
    }

    private Long insertRaw(String externalId, String title, OffsetDateTime publishedAt) {
        jdbc.update("INSERT INTO intelligence_policy_raw(source, external_id, title, url, published_at, content_text)"
                        + " VALUES('pboc', ?, ?, ?, ?, '某政策的正文内容：为进一步支持实体经济发展，制定本通知。')",
                externalId, title, "https://example.gov.cn/" + externalId, publishedAt);
        return jdbc.queryForObject(
                "SELECT id FROM intelligence_policy_raw WHERE external_id=?", Long.class, externalId);
    }

    /** 带事件行（affected_areas 默认 []、summary 固定文案、confidence 默认 HIGH）。 */
    private Long insertRawWithEvent(String externalId, String title, String publishedCst,
                                    String direction, String status) {
        return insertRawWithFullEvent(externalId, title, publishedCst, direction, "MEDIUM",
                "[]", "用于过滤测试的政策摘要", "HIGH", status);
    }

    private Long insertRawWithFullEvent(String externalId, String title, String publishedCst,
                                        String direction, String strength, String affectedAreasJson,
                                        String summary, String confidence, String status) {
        Long id = insertRaw(externalId, title, at(publishedCst));
        jdbc.update("INSERT INTO intelligence_policy_event"
                        + "(policy_raw_id, direction, strength, affected_areas, summary, confidence,"
                        + " status, model, extracted_at)"
                        + " VALUES(?, ?::varchar, ?::varchar, ?::jsonb, ?, ?::varchar, ?, 'test-model', now())",
                id, direction, strength, affectedAreasJson, summary, confidence, status);
        return id;
    }

    /** PENDING 占位行（extracted_at 为 NULL）。 */
    private Long insertRawWithStatus(String externalId, String title, String publishedCst, String status) {
        Long id = insertRaw(externalId, title, at(publishedCst));
        jdbc.update("INSERT INTO intelligence_policy_event(policy_raw_id, status)"
                + " VALUES(?, ?)", id, status);
        return id;
    }
}
