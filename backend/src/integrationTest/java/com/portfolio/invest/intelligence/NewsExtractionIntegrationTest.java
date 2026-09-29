package com.portfolio.invest.intelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.invest.application.intelligence.IntelligenceChatPort;
import com.portfolio.invest.application.intelligence.NewsExtractionService;
import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.intelligence.NewsRepository;
import com.portfolio.invest.support.PostgresTestSupport;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;
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
 * 新闻抽取批真库集成（Testcontainers PG16 + @MockitoBean 桩 LLM 端口，绝不真调 DeepSeek）：
 * PENDING→SUCCESS/FAILED 置换、既有 SUCCESS 幂等跳过（不重复抽取）、解析失败重试 1 次后
 * FAILED、PENDING 占位行覆盖、LLM 不可用整批留 PENDING——extracted_at / model 留痕可查。
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NewsExtractionIntegrationTest extends PostgresTestSupport {

    private static final ZoneId CST = ZoneId.of("Asia/Shanghai");

    private static final String EARNINGS_JSON = """
            {"event_type":"EARNINGS","stock_codes":["600519"],"industry_codes":["801250"],
            "summary":"茅台业绩超预期","direction":"BULLISH","key_numbers":["营收 12.34 亿元"],"importance":85}""";
    private static final String PLACEHOLDER_JSON = """
            {"event_type":"OTHER","stock_codes":[],"industry_codes":[],
            "summary":"占位新闻完成抽取","direction":null,"key_numbers":[],"importance":40}""";
    private static final String EXPANSION_JSON = """
            {"event_type":"M&A","stock_codes":["300750"],"industry_codes":["801730"],
            "summary":"宁德时代海外扩产","direction":"BULLISH","key_numbers":["投资 100 亿元"],"importance":70}""";
    private static final String INVALID_JSON = "抱歉这不是 JSON{{{";

    @Autowired
    NewsExtractionService service;
    @Autowired
    NewsRepository repository;
    @Autowired
    InvestProperties props;
    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    IntelligenceChatPort chatPort;

    @BeforeEach
    void cleanTables() {
        // extract 经 FK ON DELETE CASCADE 随 raw 清除
        jdbc.update("DELETE FROM intelligence_news_raw");
    }

    @Test
    @DisplayName("给定5条raw其中1条已SUCCESS，when抽取批，then4条置换SUCCESS/FAILED、已成功条幂等跳过")
    void givenFiveRawWithOneAlreadyExtracted_whenExtractPending_thenFourReplacedOneSkipped() {
        Long ok1 = insertRaw("i-ok1", "茅台三季度业绩超预期");
        Long pending = insertRawWithStatus("i-pending", "占位待抽取的公告类新闻", "PENDING");
        Long bad = insertRaw("i-bad", "完全无法结构化的碎片消息");
        // 26h 前：严格落在前一个上海自然日，countExtractedByDate（当日口径）不被旧留痕干扰
        OffsetDateTime doneExtractedAt = OffsetDateTime.now(CST).minusHours(26);
        Long done = insertRawWithSuccess("i-done", "早已抽取完成的旧新闻", doneExtractedAt);
        Long ok3 = insertRaw("i-ok3", "宁德时代海外扩产落地");
        stubPortByTitle();

        service.extractPending();

        // 4 条置换：ok1/pending/ok3 → SUCCESS，bad（两次非法 JSON）→ FAILED
        assertThat(statusOf(ok1)).isEqualTo("SUCCESS");
        assertThat(statusOf(pending)).isEqualTo("SUCCESS");
        assertThat(statusOf(bad)).isEqualTo("FAILED");
        assertThat(statusOf(ok3)).isEqualTo("SUCCESS");

        // 既有 SUCCESS 幂等跳过：不重复抽取、extracted_at 原值不动
        assertThat(statusOf(done)).isEqualTo("SUCCESS");
        assertThat(extractedAtOf(done)).isEqualTo(doneExtractedAt.toInstant());
        verify(chatPort, never()).complete(any(), argThat(user -> user.contains("早已抽取完成的旧新闻")));

        // LLM 调用 = 3 成功条各 1 次 + 解析失败条重试 1 次 = 5
        verify(chatPort, times(5)).complete(any(), any());

        // 分析字段与留痕完整落库
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT summary, direction, importance, model, extracted_at, stock_codes"
                        + " FROM intelligence_news_extract WHERE news_raw_id=?", ok1);
        assertThat(row.get("summary")).isEqualTo("茅台业绩超预期");
        assertThat(row.get("direction")).isEqualTo("BULLISH");
        assertThat(row.get("importance")).isEqualTo(85);
        assertThat(row.get("stock_codes").toString()).contains("600519");
        assertThat(row.get("model")).isEqualTo(props.getLlm().getModel());
        assertThat(row.get("extracted_at")).isNotNull();

        // FAILED 条：仅状态与留痕，分析字段为空
        Map<String, Object> badRow = jdbc.queryForMap(
                "SELECT summary, importance, model, extracted_at"
                        + " FROM intelligence_news_extract WHERE news_raw_id=?", bad);
        assertThat(badRow.get("summary")).isNull();
        assertThat(badRow.get("importance")).isNull();
        assertThat(badRow.get("model")).isEqualTo(props.getLlm().getModel());
        assertThat(badRow.get("extracted_at")).isNotNull();

        // 当日完成抽取计数（SUCCESS+FAILED）= 4
        assertThat(repository.countExtractedByDate(LocalDate.now(CST))).isEqualTo(4);
    }

    @Test
    @DisplayName("给定LLM通道未配置，when抽取批，then0次置换全部留PENDING下批可续")
    void givenLlmUnconfigured_whenExtractPending_thenAllStayPending() {
        Long a = insertRaw("i-down-a", "通道失效时的新闻甲");
        Long b = insertRaw("i-down-b", "通道失效时的新闻乙");
        when(chatPort.complete(any(), any())).thenReturn(Optional.empty());

        service.extractPending();

        // 首条即跳批：无 extract 行（或保持原状）、无 LLM 之外的任何写入
        assertThat(extractRowCount()).isZero();
        verify(chatPort, times(1)).complete(any(), any());
        assertThat(repository.findPendingForExtraction(LocalDate.now(CST), 10))
                .extracting(r -> r.id()).containsExactlyInAnyOrder(a, b); // 全部仍待抽取
    }

    // ── fixture 助手 ───────────────────────────────────────────────

    /** 按标题关键词路由桩回复：占位/碎片新闻各自固定输出，其余合法 JSON。 */
    private void stubPortByTitle() {
        when(chatPort.complete(any(), any())).thenAnswer(inv -> {
            String userPrompt = inv.getArgument(1);
            if (userPrompt.contains("占位待抽取")) {
                return Optional.of(new IntelligenceChatPort.ChatOutcome(PLACEHOLDER_JSON, 100));
            }
            if (userPrompt.contains("无法结构化")) {
                return Optional.of(new IntelligenceChatPort.ChatOutcome(INVALID_JSON, 100));
            }
            if (userPrompt.contains("宁德时代")) {
                return Optional.of(new IntelligenceChatPort.ChatOutcome(EXPANSION_JSON, 100));
            }
            return Optional.of(new IntelligenceChatPort.ChatOutcome(EARNINGS_JSON, 100));
        });
    }

    /** 当日（Asia/Shanghai）入库 raw，返回 id。 */
    private Long insertRaw(String externalId, String title) {
        jdbc.update("INSERT INTO intelligence_news_raw(source, external_id, title, summary, published_at)"
                        + " VALUES('test', ?, ?, ?, now())",
                externalId, title, title + "的源站摘要");
        return idOf(externalId);
    }

    /** 当日 raw + 指定状态占位 extract 行。 */
    private Long insertRawWithStatus(String externalId, String title, String status) {
        Long id = insertRaw(externalId, title);
        jdbc.update("INSERT INTO intelligence_news_extract(news_raw_id, status, model)"
                + " VALUES(?, ?, 'test-model')", id, status);
        return id;
    }

    /** 当日 raw + 既有 SUCCESS extract（固定 extracted_at，验证幂等不动）。 */
    private Long insertRawWithSuccess(String externalId, String title, OffsetDateTime extractedAt) {
        Long id = insertRaw(externalId, title);
        jdbc.update("INSERT INTO intelligence_news_extract"
                        + "(news_raw_id, summary, importance, status, model, extracted_at)"
                        + " VALUES(?, '旧摘要', 60, 'SUCCESS', 'old-model', ?)", id, extractedAt);
        return id;
    }

    private Long idOf(String externalId) {
        return jdbc.queryForObject(
                "SELECT id FROM intelligence_news_raw WHERE external_id=?", Long.class, externalId);
    }

    private String statusOf(Long newsRawId) {
        return jdbc.queryForObject(
                "SELECT status FROM intelligence_news_extract WHERE news_raw_id=?", String.class, newsRawId);
    }

    private java.time.Instant extractedAtOf(Long newsRawId) {
        OffsetDateTime at = jdbc.queryForObject(
                "SELECT extracted_at FROM intelligence_news_extract WHERE news_raw_id=?",
                OffsetDateTime.class, newsRawId);
        return at == null ? null : at.toInstant();
    }

    private int extractRowCount() {
        return jdbc.queryForObject("SELECT count(*) FROM intelligence_news_extract", Integer.class);
    }
}
