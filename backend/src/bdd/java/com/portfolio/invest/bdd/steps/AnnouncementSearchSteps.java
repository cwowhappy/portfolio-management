package com.portfolio.invest.bdd.steps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.agent.UserInvestTools;
import com.portfolio.invest.application.allocation.AllocationApplicationService;
import com.portfolio.invest.application.intelligence.IntelligenceQueryService;
import com.portfolio.invest.application.portfolio.PortfolioApplicationService;
import io.cucumber.java.After;
import io.cucumber.java.zh_cn.假如;
import io.cucumber.java.zh_cn.当;
import io.cucumber.java.zh_cn.那么;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 公告情报检索步骤（MS-21 Task 9 验收链路）：照 {@link IntelligenceNewsSteps} 先例 fixture
 * 直插 intelligence_announcement/extract 两表（真实 PG），经真实工具类
 * {@link UserInvestTools#searchAnnouncements} 断言 @Tool 方法 JSON 契约——工具层 →
 * IntelligenceQueryService → 仓库原生 SQL（trgm/JSONB/type 包含）全链真实。
 *
 * <p>{@code UserInvestTools} 非 Spring bean（per-会话实例，经 UserToolkitFactory 构造注入
 * userId）——BDD 以同款构造参数手动装配（scope=all 场景不触用户数据，userId 任意占位；
 * scope=holdings 场景直插 app_user + research_project（status=ACTIVE ∧ current_stage=POSITION
 * ∧ intelligence_alert_enabled=true）走真实 {@code ResearchIntelligenceSubscriptionHookImpl}
 * 读取，不 stub hook）。照任务裁定取 @Tool 方法层断言口径（共享 FixedReplyModel 不发工具调用），
 * feature 措辞保持用户视角。
 */
public class AnnouncementSearchSteps {

    /** 持仓挂接 fixture 用户（场景二直插 app_user，scope=holdings 按 userId 过滤）。 */
    private static final String HOLDER_USERNAME = "bdd-ann-holder";

    /** 持仓挂接 fixture 项目标题（@After 清理锚点）。 */
    private static final String HOLDER_PROJECT_TITLE = "BDD公告持仓挂接项目";

    private static final String FORECAST_TITLE = "贵州茅台2026年度业绩预告：净利润约31.20亿元 同比增长55.10%左右";
    private static final String PENDING_TITLE = "贵州茅台关于使用闲置募集资金进行现金管理的公告";
    private static final String HOLDING_TITLE = "贵州茅台关于以集中竞价方式回购公司股份的公告";
    private static final String OTHER_STOCK_TITLE = "宁德时代关于签订日常经营重大合同的公告";

    @Autowired
    PortfolioApplicationService portfolioService;

    @Autowired
    AllocationApplicationService allocationService;

    @Autowired
    IntelligenceQueryService intelligenceQueryService;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ScenarioContext ctx;

    @假如("已入库含抽取结果的茅台业绩预告公告与一条未抽取公告")
    @Transactional
    public void 已入库公告情报() {
        // 业绩预告条目：六字段要点（扣非/毛利率/分红未披露——null 且进 undisclosed，不编造）
        Long forecastId = insertAnnouncement("bdd-ann-forecast", "600519", "贵州茅台", FORECAST_TITLE,
                "业绩预告", true, "2026-09-28");
        insertExtract(forecastId, """
                {"revenueYi":415.20,"netProfitYi":31.20,"netProfitYoyPct":55.10,
                 "deductedProfitYi":null,"grossMarginPct":null,"dividendDesc":null,
                 "undisclosed":["扣非净利润","毛利率","分红"]}
                """, "[\"EARNINGS_FORECAST\"]");
        // 未抽取条目：无 extract 行——type 过滤（ann_types JSONB 包含）不命中
        insertAnnouncement("bdd-ann-pending", "600519", "贵州茅台", PENDING_TITLE,
                "其他", false, "2026-09-27");
    }

    @假如("已有建仓持仓阶段研究项目且已入库该标的与其他标的的公告")
    @Transactional
    public void 已有持仓项目与公告() {
        Long userId = insertUser(HOLDER_USERNAME);
        insertResearchProject(userId, "600519", "贵州茅台");
        Long holdingId = insertAnnouncement("bdd-ann-hold", "600519", "贵州茅台", HOLDING_TITLE,
                "股份回购", true, "2026-09-28");
        insertExtract(holdingId, """
                {"revenueYi":null,"netProfitYi":null,"netProfitYoyPct":null,
                 "deductedProfitYi":null,"grossMarginPct":null,"dividendDesc":null,
                 "undisclosed":["营业收入","归母净利润","归母净利润同比","扣非净利润","毛利率","分红"]}
                """, "[\"BUYBACK\"]");
        // scope 外标的公告：非持仓标的，scope=holdings 内存过滤应排除（同日更晚入库，页序在前）
        insertAnnouncement("bdd-ann-other", "300750", "宁德时代", OTHER_STOCK_TITLE,
                "其他", true, "2026-09-29");
    }

    @当("用户询问 {string}，Agent 调用 search_announcements 工具检索")
    public void 询问业绩预告(String question) {
        // Agent 对该问句的工具调用参数：标的 + 公告类型（模拟真实工具编排）；scope=all 不触用户数据
        ctx.setIntelligenceAnnouncementJson(userInvestTools(fixtureUserId()).searchAnnouncements(
                stockOf(question), "EARNINGS_FORECAST", null, null, null, null, null));
    }

    @当("Agent 以持仓范围检索公告情报")
    public void 持仓范围检索() {
        ctx.setIntelligenceAnnouncementJson(userInvestTools(fixtureUserId()).searchAnnouncements(
                null, null, null, null, null, "holdings", null));
    }

    @那么("返回的结构化条目含六字段业绩要点、类型标签含 {string} 且附原文链接")
    public void 断言结构化条目(String annType) throws Exception {
        JsonNode body = mapper.readTree(ctx.getIntelligenceAnnouncementJson());
        assertThat(body.path("items").isArray()).isTrue();
        assertThat(body.path("items")).hasSize(1);
        JsonNode item = body.path("items").get(0);
        assertThat(item.path("title").asText()).isEqualTo(FORECAST_TITLE);
        assertThat(item.path("stockCode").asText()).isEqualTo("600519");
        assertThat(item.path("stockName").asText()).isEqualTo("贵州茅台");
        // 六字段业绩要点：已披露数值等值、未披露 null 且进 undisclosed（不编造）
        JsonNode metrics = item.path("metrics");
        assertThat(metrics.path("revenueYi").asDouble()).isCloseTo(415.20, within(1e-9));
        assertThat(metrics.path("netProfitYi").asDouble()).isCloseTo(31.20, within(1e-9));
        assertThat(metrics.path("netProfitYoyPct").asDouble()).isCloseTo(55.10, within(1e-9));
        assertThat(metrics.path("deductedProfitYi").isNull()
                || metrics.path("deductedProfitYi").isMissingNode()).isTrue();
        assertThat(metrics.path("dividendDesc").isNull()
                || metrics.path("dividendDesc").isMissingNode()).isTrue();
        assertThat(metrics.path("undisclosed").toString()).contains("毛利率", "分红");
        // 类型标签（AnnouncementType 枚举名数组）+ 原文链接
        assertThat(item.path("annTypes").toString()).contains(annType);
        assertThat(item.path("pdfUrl").asText()).endsWith("bdd-ann-forecast.pdf");
    }

    @那么("公告命中总数为 {int}")
    public void 断言命中总数(int total) throws Exception {
        assertThat(mapper.readTree(ctx.getIntelligenceAnnouncementJson()).path("total").asLong())
                .isEqualTo(total);
    }

    @那么("仅返回持仓标的的公告")
    public void 断言持仓过滤() throws Exception {
        JsonNode body = mapper.readTree(ctx.getIntelligenceAnnouncementJson());
        assertThat(body.path("items")).hasSize(1);
        JsonNode item = body.path("items").get(0);
        assertThat(item.path("stockCode").asText()).isEqualTo("600519");
        assertThat(item.path("title").asText()).isEqualTo(HOLDING_TITLE);
        assertThat(item.path("annTypes").toString()).contains("BUYBACK");
        // scope 外标的公告被持仓范围过滤排除
        assertThat(body.path("items").toString()).doesNotContain(OTHER_STOCK_TITLE);
        assertThat(body.path("total").asLong()).isEqualTo(1);
    }

    // ── 私有助手 ─────────────────────────────────────────────────

    /** 每场景收尾清 fixture：UNIQUE(source, external_id) 下残留会撞唯一键（extract 级联删除）。 */
    @After
    public void 清理公告种子() {
        jdbcTemplate.update("DELETE FROM intelligence_announcement WHERE external_id LIKE 'bdd-ann-%'");
        // research_project FK 引用 app_user：先删项目再删用户
        jdbcTemplate.update("DELETE FROM research_project WHERE title = ?", HOLDER_PROJECT_TITLE);
        jdbcTemplate.update("DELETE FROM app_user WHERE username = ?", HOLDER_USERNAME);
    }

    /** UserInvestTools 同款构造（UserToolkitFactory 装配形状）；scope=all 路径不消费 userId。 */
    private UserInvestTools userInvestTools(Long userId) {
        return new UserInvestTools(userId, portfolioService, allocationService,
                intelligenceQueryService, mapper);
    }

    /** 持仓 fixture 用户 id（场景二直插的 app_user；场景一无用户行，任意占位 id）。 */
    private Long fixtureUserId() {
        return jdbcTemplate.query(
                "SELECT id FROM app_user WHERE username = ?",
                (rs, i) -> rs.getLong("id"), HOLDER_USERNAME).stream().findFirst().orElse(999999L);
    }

    /** 问句 → 工具 stock 参数：「茅台最近的业绩预告」取标的代码。 */
    private static String stockOf(String question) {
        return question.contains("茅台") ? "600519" : null;
    }

    /** 直插公告行，返回生成的 id（UNIQUE(source, external_id) 幂等键固定，BDD 每轮全新容器）。 */
    private Long insertAnnouncement(String externalId, String stockCode, String stockName, String title,
                                    String annTypeSource, boolean major, String publishedDate) {
        jdbcTemplate.update("""
                        INSERT INTO intelligence_announcement
                            (source, external_id, stock_code, stock_name, title, ann_type_source,
                             major, published_at, pdf_url)
                        VALUES ('cninfo', ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                externalId, stockCode, stockName, title, annTypeSource, major,
                LocalDate.parse(publishedDate).atTime(10, 0).atOffset(ZoneOffset.ofHours(8)),
                "https://static.cninfo.com.cn/" + externalId + ".pdf");
        return jdbcTemplate.queryForObject(
                "SELECT id FROM intelligence_announcement WHERE external_id = ?", Long.class, externalId);
    }

    /** 直插抽取结果行（status=SUCCESS；metrics 六字段契约 + ann_types 枚举名 JSONB，文本块换行为合法 JSON 空白）。 */
    private void insertExtract(Long announcementId, String metricsJson, String annTypesJson) {
        jdbcTemplate.update("""
                        INSERT INTO intelligence_announcement_extract
                            (announcement_id, metrics, ann_types, pdf_text, status, model, extracted_at)
                        VALUES (?, ?::jsonb, ?::jsonb, 'BDD fixture pdf 文本', 'SUCCESS',
                                'bdd-fixture', now())
                        """,
                announcementId, metricsJson, annTypesJson);
    }

    /** 直插 app_user（status=APPROVED 免审核语义；密码哈希占位——不经登录链路）。 */
    private Long insertUser(String username) {
        jdbcTemplate.update(
                "INSERT INTO app_user(username, password_hash, role, status, enabled) "
                        + "VALUES (?, 'bdd-fixture', 'USER', 'APPROVED', TRUE)",
                username);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM app_user WHERE username = ?", Long.class, username);
    }

    /** 直插建仓持仓阶段研究项目（hook 消费口径：ACTIVE ∧ POSITION ∧ intelligence_alert_enabled）。 */
    private void insertResearchProject(Long userId, String stockCode, String stockName) {
        jdbcTemplate.update("""
                        INSERT INTO research_project
                            (user_id, stock_code, stock_name, title, current_stage, status,
                             intelligence_alert_enabled)
                        VALUES (?, ?, ?, ?, 'POSITION', 'ACTIVE', TRUE)
                        """,
                userId, stockCode, stockName, HOLDER_PROJECT_TITLE);
    }
}
