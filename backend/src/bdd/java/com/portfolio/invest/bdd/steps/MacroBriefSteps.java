package com.portfolio.invest.bdd.steps;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.agent.InvestTools;
import io.cucumber.java.After;
import io.cucumber.java.zh_cn.假如;
import io.cucumber.java.zh_cn.当;
import io.cucumber.java.zh_cn.那么;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 宏观简报步骤（MS-22 F13 验收链路）：照 {@link IntelligenceNewsSteps} 先例 fixture
 * 直插 intelligence_macro_series / intelligence_policy_raw+event 两路表（真实 PG），
 * 经真实 {@link InvestTools} bean 调 macro_brief @Tool 方法断言工具 JSON 契约——
 * 工具层 → IntelligenceQueryService → MacroQueryService/PolicyRepository 原生 SQL
 * （含国债收益率跨表只读路径）全链真实。@Tool 方法层断言口径与新闻/公告场景一致
 * （共享 FixedReplyModel 不发工具调用，feature 措辞保持用户视角）。
 *
 * <p>政策 fixture published_at 取「今日」（Asia/Shanghai，与服务政策窗口时钟同口径），
 * 确保落 macroBrief 缺省 30 天回看窗 [今日-29, 今日] 内；isPolicy=false 兜底行的可见性
 * 与哨兵派生由 PolicyEvent 读取侧单测守护，本场景聚焦 SUCCESS 政策条目契约。
 */
public class MacroBriefSteps {

    /** fixture 指标与期别（CPI 月度近 6 期 2026-04~09，最新期即断言锚点）。 */
    private static final String LATEST_PERIOD = "2026-09";

    private static final BigDecimal LATEST_VALUE = new BigDecimal("0.4");

    /** fixture 政策行（今日发布，UNIQUE(source, external_id) 幂等键固定，BDD 每轮全新容器）。 */
    private static final String POLICY_EXTERNAL_ID = "bdd-macro-policy-1";

    private static final String POLICY_TITLE = "中国人民银行决定下调金融机构存款准备金率0.5个百分点";

    private static final String POLICY_URL = "https://www.pbc.gov.cn/bdd-macro-fixture-1.html";

    /** 服务政策窗口「今日」口径时区（Asia/Shanghai，与 IntelligenceQueryService 时钟一致）。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Autowired
    InvestTools investTools;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    ScenarioContext ctx;

    @假如("已入库 CPI 近 6 期序列与一条降准政策事件")
    @Transactional
    public void 已入库宏观序列与政策事件() {
        // CPI 近 6 期（2026-04~09 同比 %，企稳回升走势）；yoy 仅最新期给（源未给出时缺席契约）
        insertCpi("2026-04", "0.30", null);
        insertCpi("2026-05", "0.20", null);
        insertCpi("2026-06", "0.10", null);
        insertCpi("2026-07", "0.20", null);
        insertCpi("2026-08", "0.30", null);
        insertCpi("2026-09", "0.40", new BigDecimal("0.40"));
        // 政策 raw + event 两表直插（今日发布落政策窗口；status=SUCCESS 才进检索面）
        Long rawId = insertPolicyRaw();
        jdbcTemplate.update("""
                        INSERT INTO intelligence_policy_event
                            (policy_raw_id, direction, strength, affected_areas, summary,
                             confidence, status, model, extracted_at)
                        VALUES (?, 'EASING', 'HIGH', ?::jsonb, ?, 'HIGH', 'SUCCESS', 'bdd-fixture', now())
                        """,
                rawId, "[\"流动性\"]", "央行全面降准0.5个百分点，预计释放长期资金约1万亿元");
    }

    @当("用户询问 {string}，Agent 调用 macro_brief 工具")
    public void 询问宏观环境(String question) {
        // Agent 对该问句的工具调用参数：宏观环境类问句不带指标过滤（缺省全部七项指标）
        ctx.setIntelligenceMacroBriefJson(investTools.macroBrief(null, null));
    }

    @当("Agent 以指标 {string} 调用宏观简报")
    public void 按指标调用(String indicators) {
        ctx.setIntelligenceMacroBriefJson(investTools.macroBrief(indicators, null));
    }

    @那么("返回的宏观简报含 CPI 最新值、近 5 期序列与数据期别")
    public void 断言指标节() throws Exception {
        JsonNode body = mapper.readTree(ctx.getIntelligenceMacroBriefJson());
        JsonNode cpi = findIndicator(body, "CPI");
        assertThat(cpi).isNotNull();
        assertThat(cpi.path("value").asDouble()).isEqualTo(LATEST_VALUE.doubleValue());
        assertThat(cpi.path("yoy").asDouble()).isEqualTo(0.40);
        // 数据截止期别（引用时注明契约）：最新期 + 期别类型
        assertThat(cpi.path("period").asText()).isEqualTo(LATEST_PERIOD);
        assertThat(cpi.path("periodType").asText()).isEqualTo("MONTH");
        // 近 5 期迷你趋势（period 倒序——最新在前），库中 6 期只取 5 期
        assertThat(cpi.path("series")).hasSize(5);
        assertThat(cpi.path("series").get(0).path("period").asText()).isEqualTo(LATEST_PERIOD);
        assertThat(cpi.path("series").get(4).path("period").asText()).isEqualTo("2026-05");
    }

    @那么("政策事件带取向 {string}、影响领域与原文链接")
    public void 断言政策节(String directionLabel) throws Exception {
        JsonNode body = mapper.readTree(ctx.getIntelligenceMacroBriefJson());
        assertThat(body.path("policies")).hasSize(1);
        JsonNode policy = body.path("policies").get(0);
        assertThat(policy.path("title").asText()).isEqualTo(POLICY_TITLE);
        assertThat(policy.path("direction").asText()).isEqualTo(directionOf(directionLabel));
        assertThat(policy.path("strength").asText()).isEqualTo("HIGH");
        assertThat(policy.path("areas").toString()).contains("流动性");
        assertThat(policy.path("summary").asText()).contains("降准");
        assertThat(policy.path("confidence").asText()).isEqualTo("HIGH");
        assertThat(policy.path("isPolicy").asBoolean()).isTrue();
        assertThat(policy.path("url").asText()).isEqualTo(POLICY_URL);
        assertThat(body.path("total").asLong()).isEqualTo(1);
    }

    @那么("CPI 正常返回且 PMI 显式列入缺失清单")
    public void 断言缺失显式() throws Exception {
        JsonNode body = mapper.readTree(ctx.getIntelligenceMacroBriefJson());
        assertThat(findIndicator(body, "CPI")).isNotNull();
        assertThat(findIndicator(body, "PMI")).isNull();
        assertThat(body.path("missing")).hasSize(1);
        JsonNode missing = body.path("missing").get(0);
        assertThat(missing.path("indicator").asText()).isEqualTo("PMI");
        assertThat(missing.path("missing").asBoolean()).isTrue();
    }

    // ── 私有助手 ─────────────────────────────────────────────────

    /** 每场景收尾清 fixture：policy_event 无级联删除须先清子表；macro 按指标+期别区间圈定。 */
    @After
    public void 清理宏观种子() {
        jdbcTemplate.update(
                "DELETE FROM intelligence_policy_event WHERE policy_raw_id IN "
                        + "(SELECT id FROM intelligence_policy_raw WHERE external_id = ?)",
                POLICY_EXTERNAL_ID);
        jdbcTemplate.update(
                "DELETE FROM intelligence_policy_raw WHERE external_id = ?", POLICY_EXTERNAL_ID);
        jdbcTemplate.update(
                "DELETE FROM intelligence_macro_series WHERE indicator = 'CPI' "
                        + "AND period BETWEEN '2026-04' AND '2026-09'");
    }

    /** 中文取向标签 → PolicyDirection 枚举名（宽松/收紧/中性）。 */
    private static String directionOf(String label) {
        return switch (label) {
            case "宽松" -> "EASING";
            case "收紧" -> "TIGHTENING";
            default -> "NEUTRAL";
        };
    }

    private JsonNode findIndicator(JsonNode body, String indicator) {
        for (JsonNode item : body.path("indicators")) {
            if (indicator.equals(item.path("indicator").asText())) {
                return item;
            }
        }
        return null;
    }

    /** 直插 CPI 单期观测（yoy 可空——源未给出时缺席而非编造）。 */
    private void insertCpi(String period, String value, BigDecimal yoy) {
        jdbcTemplate.update(
                "INSERT INTO intelligence_macro_series(indicator, period, period_type, value, yoy, source_note) "
                        + "VALUES ('CPI', ?, 'MONTH', ?, ?, 'BDD 宏观简报 fixture')",
                period, new BigDecimal(value), yoy);
    }

    /** 直插政策 raw 行（今日 10:00 发布，落政策回看窗），返回生成的 id。 */
    private Long insertPolicyRaw() {
        jdbcTemplate.update(
                "INSERT INTO intelligence_policy_raw(source, external_id, title, url, published_at, content_text) "
                        + "VALUES ('pboc', ?, ?, ?, ?, ?)",
                POLICY_EXTERNAL_ID, POLICY_TITLE, POLICY_URL,
                LocalDate.now(ZONE).atTime(10, 0).atOffset(ZoneOffset.ofHours(8)),
                "为保持银行体系流动性合理充裕，中国人民银行决定下调金融机构存款准备金率0.5个百分点。");
        return jdbcTemplate.queryForObject(
                "SELECT id FROM intelligence_policy_raw WHERE external_id = ?",
                Long.class, POLICY_EXTERNAL_ID);
    }
}
