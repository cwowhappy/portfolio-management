package com.portfolio.invest.bdd.steps;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.agent.InvestTools;
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
 * 新闻情报检索步骤（MS-20 F03 验收链路）：照 {@link ScreeningSteps} 先例 fixture
 * 直插 intelligence_news_raw/extract 两表（真实 PG），经真实 {@link InvestTools} bean
 * 调 search_news @Tool 方法断言工具 JSON 契约——工具层 → IntelligenceQueryService →
 * 仓库原生 SQL（trgm/JSONB）全链真实。
 *
 * <p>既有 BDD 无 agent 工具调用先例：共享 {@code FixedReplyModel} 不发工具调用
 * （改其行为会波及会话旅程场景），照任务裁定取 @Tool 方法层断言口径，
 * feature 措辞保持用户视角。「用户询问茅台重大新闻」步骤以关键词「茅台」检索，
 * 模拟 Agent 对该问句的工具调用参数。
 */
public class IntelligenceNewsSteps {

    /** fixture 关键词（三行标题共用，场景间以关键词圈定 fixture 数据边界）。 */
    private static final String TAG = "BDD情报";

    private static final String MAJOR_TITLE = "贵州茅台" + TAG + "宣布百亿回购计划";
    private static final String WATCH_TITLE = TAG + "白酒板块午间小幅波动";
    private static final String PENDING_TITLE = TAG + "某公司发布现金管理公告";

    /** search_news 空结果信封话术（与 InvestTools.NEWS_EMPTY_MESSAGE 一致）。 */
    private static final String EMPTY_MESSAGE = "该条件下暂无情报（新闻仅保留 90 天内）";

    @Autowired
    InvestTools investTools;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    ScenarioContext ctx;

    @假如("已入库含抽取结果的茅台重大新闻、普通新闻与一条未抽取新闻")
    @Transactional
    public void 已入库新闻情报() {
        // 重大条目：MAJOR（importance 85、利好、AI 摘要 + 关键数字 + 标的/行业标签齐全）
        Long majorId = insertRaw("bdd-intel-major", MAJOR_TITLE,
                "源站摘要：公司拟以自有资金回购股份", "2026-09-28");
        insertExtract(majorId, "回购", "[\"600519\"]", "[\"801140\"]",
                "AI 摘要：茅台公告最高百亿回购，彰显管理层信心", "BULLISH",
                "[\"回购金额最高百亿元\"]", 85);
        // 普通条目：WATCH（importance 40、中性、无关键数字）——重要度下限过滤的被排除样本
        Long watchId = insertRaw("bdd-intel-watch", WATCH_TITLE,
                "源站摘要：板块午间震荡", "2026-09-28");
        insertExtract(watchId, null, "[\"600519\"]", null,
                "AI 摘要：板块日内波动，无重大增量信息", "NEUTRAL", null, 40);
        // 未抽取条目：无 extract 行——左连视图 direction/importance 为 null、摘要退化源站摘要
        insertRaw("bdd-intel-pending", PENDING_TITLE,
                "源站摘要：使用闲置自有资金购买理财产品", "2026-09-27");
    }

    @当("用户询问 {string}，Agent 调用 search_news 工具检索")
    public void 询问重大新闻(String question) {
        // Agent 对该问句的工具调用参数：按问句标的关键词做标题检索（模拟真实工具编排）
        ctx.setIntelligenceNewsJson(investTools.searchNews(questionKeyword(question), null, null,
                null, null, null, null));
    }

    @当("Agent 以关键词 {string} 重要度下限 {int} 检索新闻情报")
    public void 按重要度下限检索(String keyword, Integer minImportance) {
        ctx.setIntelligenceNewsJson(investTools.searchNews(keyword, null, null,
                null, null, minImportance, null));
    }

    @当("Agent 以关键词 {string} 检索新闻情报")
    public void 按关键词检索(String keyword) {
        ctx.setIntelligenceNewsJson(investTools.searchNews(keyword, null, null,
                null, null, null, null));
    }

    @那么("返回的结构化条目含 AI 摘要、关键数字、方向为 {string} 且重要度为 {int}")
    public void 断言结构化条目(String directionLabel, Integer importance) throws Exception {
        JsonNode body = mapper.readTree(ctx.getIntelligenceNewsJson());
        assertThat(body.path("items").isArray()).isTrue();
        assertThat(body.path("items")).hasSize(1);
        JsonNode item = body.path("items").get(0);
        assertThat(item.path("title").asText()).isEqualTo(MAJOR_TITLE);
        // 摘要 AI 抽取优先（非源站摘要）
        assertThat(item.path("summary").asText()).isEqualTo("AI 摘要：茅台公告最高百亿回购，彰显管理层信心");
        assertThat(item.path("direction").asText()).isEqualTo(directionOf(directionLabel));
        assertThat(item.path("importance").asInt()).isEqualTo(importance);
        assertThat(item.path("keyNumbers").toString()).contains("回购金额最高百亿元");
        assertThat(item.path("stockCodes").toString()).contains("600519");
    }

    @那么("命中总数为 {int}")
    public void 断言命中总数(int total) throws Exception {
        assertThat(mapper.readTree(ctx.getIntelligenceNewsJson()).path("total").asLong())
                .isEqualTo(total);
    }

    @那么("仅返回重大条目")
    public void 断言重要度过滤() throws Exception {
        JsonNode body = mapper.readTree(ctx.getIntelligenceNewsJson());
        assertThat(body.path("items")).hasSize(1);
        assertThat(body.path("items").get(0).path("title").asText()).isEqualTo(MAJOR_TITLE);
        assertThat(body.path("total").asLong()).isEqualTo(1);
    }

    @那么("返回 {int} 条情报且未抽取条目方向与重要度为空、摘要退化为源站摘要")
    public void 断言未抽取退化(int count) throws Exception {
        JsonNode body = mapper.readTree(ctx.getIntelligenceNewsJson());
        assertThat(body.path("items")).hasSize(count);
        JsonNode pending = findItemByTitle(body, PENDING_TITLE);
        assertThat(pending).isNotNull();
        // null 字段无论序列化为 null 还是剥除（MissingNode），语义都应为空
        assertThat(pending.path("direction").isNull() || pending.path("direction").isMissingNode())
                .isTrue();
        assertThat(pending.path("importance").isNull() || pending.path("importance").isMissingNode())
                .isTrue();
        assertThat(pending.path("summary").asText()).isEqualTo("源站摘要：使用闲置自有资金购买理财产品");
    }

    @那么("返回空条目列表与 90 天保留期提示")
    public void 断言空结果信封() throws Exception {
        JsonNode body = mapper.readTree(ctx.getIntelligenceNewsJson());
        assertThat(body.path("items").isArray()).isTrue();
        assertThat(body.path("items")).isEmpty();
        assertThat(body.path("message").asText()).isEqualTo(EMPTY_MESSAGE);
    }

    // ── 私有助手 ─────────────────────────────────────────────────

    /** 每场景收尾清 fixture：UNIQUE(source, external_id) 下残留会撞唯一键（照 IndustrySteps @After 先例；extract 级联删除）。 */
    @After
    public void 清理新闻种子() {
        jdbcTemplate.update("DELETE FROM intelligence_news_raw WHERE external_id LIKE 'bdd-intel-%'");
    }

    /** 问句 → 工具关键词：「关于茅台的重大新闻」取标的名「茅台」。 */
    private static String questionKeyword(String question) {
        return question.contains("茅台") ? "茅台" : question;
    }

    /** 中文方向标签 → Direction 枚举名（利好/利空/中性）。 */
    private static String directionOf(String label) {
        return switch (label) {
            case "利好" -> "BULLISH";
            case "利空" -> "BEARISH";
            default -> "NEUTRAL";
        };
    }

    private JsonNode findItemByTitle(JsonNode body, String title) {
        for (JsonNode item : body.path("items")) {
            if (title.equals(item.path("title").asText())) {
                return item;
            }
        }
        return null;
    }

    /** 直插原始新闻行，返回生成的 id（UNIQUE(source, external_id) 幂等键固定，BDD 每轮全新容器）。 */
    private Long insertRaw(String externalId, String title, String summary, String publishedDate) {
        jdbcTemplate.update(
                "INSERT INTO intelligence_news_raw(source, external_id, title, summary, published_at) "
                        + "VALUES ('eastmoney', ?, ?, ?, ?)",
                externalId, title, summary,
                LocalDate.parse(publishedDate).atTime(10, 0).atOffset(ZoneOffset.ofHours(8)));
        return jdbcTemplate.queryForObject(
                "SELECT id FROM intelligence_news_raw WHERE external_id = ?", Long.class, externalId);
    }

    /** 直插抽取结果行（status=SUCCESS，新闻检索消费口径；keyNumbers 为 JSONB 字符串，可空）。 */
    private void insertExtract(Long newsRawId, String eventType, String stockCodes,
            String industryCodes, String summary, String direction, String keyNumbers, int importance) {
        jdbcTemplate.update("""
                        INSERT INTO intelligence_news_extract
                            (news_raw_id, event_type, stock_codes, industry_codes, summary,
                             direction, key_numbers, importance, status, model, extracted_at)
                        VALUES (?, ?, ?::jsonb, ?::jsonb, ?, ?, ?::jsonb, ?, 'SUCCESS', 'bdd-fixture', now())
                        """,
                newsRawId, eventType, stockCodes, industryCodes, summary, direction, keyNumbers,
                importance);
    }
}
