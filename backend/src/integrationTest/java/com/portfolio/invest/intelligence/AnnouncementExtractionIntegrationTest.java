package com.portfolio.invest.intelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.invest.application.intelligence.AnnouncementExtractionService;
import com.portfolio.invest.application.intelligence.AnnouncementPdfTextPort;
import com.portfolio.invest.application.intelligence.AnnouncementPdfTextPortException;
import com.portfolio.invest.application.intelligence.IntelligenceChatPort;
import com.portfolio.invest.application.intelligence.PdfFetcher;
import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.intelligence.AnnouncementRepository;
import com.portfolio.invest.support.PostgresTestSupport;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
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
 * 公告抽取批真库集成（Testcontainers PG16 + @MockitoBean 桩 LLM/PDF/下载器，绝不真调外网）：
 * 业绩类公告 PENDING→SUCCESS 置换（metrics/ann_types 并集/pdf_text 落库）、非业绩类 SUCCESS
 * 空抽取（NULL metrics + '[]' 标签，不下载不调 LLM）、PDF 解析异常 FAILED 终态——
 * findExtractedMajorSince（extracted_at 轴，Task 7 推送消费口径）按 SUCCESS ∧ major 命中。
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnnouncementExtractionIntegrationTest extends PostgresTestSupport {

    private static final String METRICS_JSON = """
            {"metrics":{"revenueYi":128.56,"netProfitYi":31.2,"netProfitYoyPct":25.3,
            "deductedProfitYi":30.05,"grossMarginPct":null,"dividendDesc":"每10股派2元",
            "undisclosed":["毛利率"]},"annTypes":["PERIODIC_REPORT","EQUITY_INCENTIVE"]}""";
    private static final String PDF_TEXT = "贵州茅台2026年半年度报告：营业收入128.56亿元，归母净利润31.2亿元，同比增加25.3%。";

    @Autowired
    AnnouncementExtractionService service;
    @Autowired
    AnnouncementRepository repository;
    @Autowired
    InvestProperties props;
    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    IntelligenceChatPort chatPort;
    @MockitoBean
    AnnouncementPdfTextPort pdfTextPort;
    @MockitoBean
    PdfFetcher pdfFetcher;

    @BeforeEach
    void cleanTables() {
        // extract 经 FK ON DELETE CASCADE 随 announcement 清除
        jdbc.update("DELETE FROM intelligence_announcement");
    }

    @Test
    @DisplayName("给定业绩类公告，when抽取批，thenSUCCESS置换metrics/并集标签/pdf_text落库且推送窗口命中")
    void givenPerformanceAnnouncement_whenExtractPending_thenSuccessWithMetricsAndPdfText() throws Exception {
        Long id = insertAnnouncement("i-perf", "贵州茅台2026年半年度报告", "半年度报告摘要",
                true, "https://static.cninfo.com.cn/finalpage/a.pdf");
        when(pdfFetcher.download("https://static.cninfo.com.cn/finalpage/a.pdf"))
                .thenReturn("fake-pdf".getBytes(StandardCharsets.UTF_8));
        when(pdfTextPort.extract(any(byte[].class))).thenReturn(PDF_TEXT);
        when(chatPort.complete(any(), any())).thenReturn(
                Optional.of(new IntelligenceChatPort.ChatOutcome(METRICS_JSON, 100)));

        service.extractPending();

        // 状态置换 SUCCESS，六字段契约 + 未披露标注 + 并集标签（栏目直判 ∪ LLM）+ pdf_text 留痕
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT metrics::text AS metrics, ann_types::text AS ann_types, pdf_text, status, model"
                        + " FROM intelligence_announcement_extract WHERE announcement_id=?", id);
        assertThat(row.get("status")).isEqualTo("SUCCESS");
        assertThat((String) row.get("metrics")).contains("128.56").contains("毛利率").contains("每10股派2元");
        assertThat((String) row.get("ann_types"))
                .contains("PERIODIC_REPORT").contains("EQUITY_INCENTIVE");
        assertThat(row.get("pdf_text")).isEqualTo(PDF_TEXT);
        assertThat(row.get("model")).isEqualTo(props.getLlm().getModel());

        // Task 7 推送消费口径：extracted_at 轴 ≥ 批起点（含边界）∧ SUCCESS ∧ major 命中
        List<Long> pushed = repository.findExtractedMajorSince(Instant.now().minusSeconds(60))
                .stream().map(r -> r.id()).toList();
        assertThat(pushed).contains(id);
    }

    @Test
    @DisplayName("给定非业绩类公告，when抽取批，thenSUCCESS空抽取不下载不调LLM")
    void givenNonPerformanceAnnouncement_whenExtractPending_thenSuccessEmptyWithoutLlm() throws Exception {
        Long id = insertAnnouncement("i-misc", "关于召开2026年第三次临时股东会的通知",
                "召开股东大会通知", false, "https://static.cninfo.com.cn/finalpage/b.pdf");

        service.extractPending();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT metrics, ann_types::text AS ann_types, pdf_text, status, model"
                        + " FROM intelligence_announcement_extract WHERE announcement_id=?", id);
        assertThat(row.get("status")).isEqualTo("SUCCESS");
        assertThat(row.get("metrics")).isNull();
        assertThat((String) row.get("ann_types")).isEqualTo("[]");
        assertThat(row.get("pdf_text")).isNull();
        assertThat(row.get("model")).isNull(); // 未送 LLM 的留痕标识
        verify(pdfFetcher, never()).download(any());
        verify(chatPort, never()).complete(any(), any());

        // 非 major：推送窗口不命中
        assertThat(repository.findExtractedMajorSince(Instant.now().minusSeconds(60)))
                .extracting(r -> r.id()).doesNotContain(id);
    }

    @Test
    @DisplayName("给定PDF解析异常，when抽取批，thenFAILED终态且分析字段空、推送窗口不命中")
    void givenPdfPortFailure_whenExtractPending_thenFailedTerminal() throws Exception {
        Long id = insertAnnouncement("i-enc", "某公司2026年半年度报告（加密扫描件）", null,
                true, "https://static.cninfo.com.cn/finalpage/enc.pdf");
        when(pdfFetcher.download(any())).thenReturn("locked".getBytes(StandardCharsets.UTF_8));
        when(pdfTextPort.extract(any(byte[].class)))
                .thenThrow(new AnnouncementPdfTextPortException("加密或损坏"));

        service.extractPending();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT metrics, ann_types::text AS ann_types, pdf_text, status, model"
                        + " FROM intelligence_announcement_extract WHERE announcement_id=?", id);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("metrics")).isNull();
        assertThat(row.get("pdf_text")).isNull();
        assertThat(row.get("model")).isEqualTo(props.getLlm().getModel()); // FAILED 留痕批配置模型
        verify(chatPort, never()).complete(any(), any());
        assertThat(repository.findExtractedMajorSince(Instant.now().minusSeconds(60)))
                .extracting(r -> r.id()).doesNotContain(id);
    }

    @Test
    @DisplayName("给定无pdf_url的业绩类公告，when抽取批，then凭标题降级抽取且pdf_text落空串留痕")
    void givenNoPdfUrl_whenExtractPending_thenTitleOnlyExtraction() throws Exception {
        Long id = insertAnnouncement("i-nourl", "某公司2026年年度业绩预告", null, true, null);
        when(chatPort.complete(any(), any())).thenReturn(
                Optional.of(new IntelligenceChatPort.ChatOutcome(METRICS_JSON, 100)));

        service.extractPending();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT pdf_text, status FROM intelligence_announcement_extract WHERE announcement_id=?", id);
        assertThat(row.get("status")).isEqualTo("SUCCESS");
        assertThat(row.get("pdf_text")).isEqualTo("");
        // 无 pdf_url：不发起下载，LLM 用户提示词标注仅凭标题
        verify(pdfFetcher, never()).download(any());
        verify(chatPort, times(1)).complete(any(), contains("无PDF正文"));
    }

    // ── fixture 助手 ───────────────────────────────────────────────

    /** 当日（fetched_at 默认 now()）入库公告，返回 id。 */
    private Long insertAnnouncement(String externalId, String title, String annTypeSource,
                                    boolean major, String pdfUrl) {
        jdbc.update("INSERT INTO intelligence_announcement"
                        + "(source, external_id, stock_code, stock_name, title, ann_type_source, major, published_at, pdf_url)"
                        + " VALUES('test', ?, '600519', '贵州茅台', ?, ?, ?, now(), ?)",
                externalId, title, annTypeSource, major, pdfUrl);
        return jdbc.queryForObject(
                "SELECT id FROM intelligence_announcement WHERE external_id=?", Long.class, externalId);
    }
}
