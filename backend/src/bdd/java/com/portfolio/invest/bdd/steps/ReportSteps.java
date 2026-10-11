package com.portfolio.invest.bdd.steps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.portfolio.invest.agent.trust.ToolInvocation;
import com.portfolio.invest.agent.trust.TrustContext;
import com.portfolio.invest.application.research.ReportAnchorRecheckPort;
import com.portfolio.invest.application.research.ReportStalenessService;
import com.portfolio.invest.bdd.CucumberSpringConfig.ScriptedReportRunnerFactory;
import com.portfolio.invest.domain.research.ReportAnchor;
import io.agentscope.core.agent.RuntimeContext;
import io.cucumber.java.Before;
import io.cucumber.java.zh_cn.当;
import io.cucumber.java.zh_cn.那么;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 深度研究工作流步骤（MS-32 B14 验收链路）：REST 全链走真实 HTTP（MockMvc + 会话）+
 * 真实 PG 落库（任务行/研报/锚点/假设/journal 事件），仅 Agent 侧以
 * {@link ScriptedReportRunnerFactory} 剧本回放替换真 LLM（照
 * {@code ReportWorkflowOrchestratorTest} 的 ScriptedRunner 口径：每轮 call 先 fresh 清池
 * （模拟生产 TrustAgentHook PRE_CALL 时序）再 record 本轮真值——真值池注入走反射，
 * {@code TrustContext.fresh/record} 包私有于 trust 包，B0 Ruling-1 同款取舍）。
 *
 * <p>三剧本：①正常三轮 + 过门草稿（关键假设数字与真值一致）→ SUCCEEDED → 确认挂项目；
 * ②草稿与回炉稿数字均超容差不可归因 → FAILED(VALIDATION_FAILED) 且无研报行；
 * ③已确认研报的锚点现值漂移（重查口打桩回放 CHANGED，任务书简化裁定——重查机制本身
 * B12 单测钉死）→ detail 透出假设过期。
 */
public class ReportSteps {

    /** 轮询截止（fake runner 即时完成，30s 只作编排器异步线程调度的宽松上限）。 */
    private static final long POLL_TIMEOUT_MS = 30_000;

    private static final long POLL_INTERVAL_MS = 150;

    /** 收集/分析轮装载的真值（与草稿正文数字精确一致，锚定全 VERIFIED）。 */
    private static final String QUOTE_TRUTH = "现价986.50元，总市值8500亿";
    private static final String VALUATION_TRUTH = "市盈率35.40，市净率9.80";

    /** 过门正文：四个数字与真值一致；「## 关键假设」一条可绑定。 */
    private static final String VALID_CONTENT_MD =
            "现价986.50元，总市值8500亿，市盈率35.40，市净率9.80。\n\n## 关键假设\n1. 现价986.50元处于合理区间\n";

    /** 错值正文：现价 1120.00 偏离真值 986.50 约 13.6%（超严格档且非倍率可归因）→ 未溯源越线。 */
    private static final String WRONG_CONTENT_MD =
            "现价1120.00元，总市值8500亿，市盈率35.40，市净率9.80。\n\n## 关键假设\n1. 现价1120.00元处于合理区间\n2. 市盈率35.40具备安全边际\n";

    private static final String REPORT_TITLE = "贵州茅台深度研报（BDD）";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    ScenarioContext ctx;

    @Autowired
    ScriptedReportRunnerFactory reportRunnerFactory;

    @Autowired
    ReportAnchorRecheckPort reportAnchorRecheckPort;

    /** 每场景开始前防串：清空 runner 剧本；重查口 reset 后兜底 UNVERIFIABLE（detail 链安全）。 */
    @Before
    public void 重置研报桩() {
        reportRunnerFactory.clear();
        Mockito.reset(reportAnchorRecheckPort);
        Mockito.doAnswer(invocation -> {
            ReportAnchor anchor = invocation.getArgument(1);
            return new ReportStalenessService.AnchorStatus(anchor.id(),
                    ReportStalenessService.AnchorState.UNVERIFIABLE, null);
        }).when(reportAnchorRecheckPort).recheck(Mockito.any(), Mockito.any());
    }

    // ———— 场景 1/3：正常剧本发起（注册一次即可匹配任意关键字位置，cucumber 按表达式文本匹配） ————

    @当("该用户发起研报任务，Agent 三轮正常且草稿关键假设与真值一致")
    public void 发起研报任务正常剧本() throws Exception {
        reportRunnerFactory.arm(List.of(
                rc -> {
                    seedPool(rc, truth("get_quote", QUOTE_TRUTH));
                    return "收集小结：已调取行情与总市值数据";
                },
                rc -> {
                    seedPool(rc, truth("get_valuation", VALUATION_TRUTH));
                    return "分析小结：估值分位中等，商业模式稳健";
                },
                rc -> fencedReport(REPORT_TITLE, VALID_CONTENT_MD, "样本期较短，结论待持续跟踪")));
        发起研报任务();
    }

    // ———— 场景 2：错值剧本发起（草稿 + 回炉稿均错） ————

    @当("该用户发起研报任务，Agent 草稿与回炉稿数字均与真值偏离")
    public void 发起研报任务错值剧本() throws Exception {
        reportRunnerFactory.arm(List.of(
                rc -> {
                    seedPool(rc, truth("get_quote", QUOTE_TRUTH));
                    return "收集小结：已调取行情与总市值数据";
                },
                rc -> {
                    seedPool(rc, truth("get_valuation", VALUATION_TRUTH));
                    return "分析小结：估值分位中等";
                },
                rc -> fencedReport("错值初稿（BDD）", WRONG_CONTENT_MD, "无"),
                rc -> fencedReport("错值回炉稿（BDD）", WRONG_CONTENT_MD, "无")));
        发起研报任务();
    }

    // ———— 轮询 / 研报状态 ————

    @那么("任务轮询至 {string}")
    public void 任务轮询至(String expectedStatus) throws Exception {
        JsonNode body = pollTaskUntil(expectedStatus);
        if (body.path("reportId").isNumber()) {
            ctx.setReportId(body.path("reportId").asLong());
        }
    }

    @那么("任务轮询至 {string}，错误码 {string}")
    public void 任务轮询至失败(String expectedStatus, String errorCode) throws Exception {
        JsonNode body = pollTaskUntil(expectedStatus);
        assertThat(body.path("errorCode").asText()).isEqualTo(errorCode);
    }

    @那么("研报为 {string} 待确认")
    public void 研报待确认(String status) throws Exception {
        断言项目研报状态(status);
    }

    @那么("研报为 {string}")
    public void 研报状态为(String status) throws Exception {
        断言项目研报状态(status);
    }

    @那么("项目研报列表含该研报")
    public void 项目研报列表含该研报() throws Exception {
        JsonNode reports = 研报列表();
        assertThat(reports).hasSize(1);
        assertThat(reports.get(0).path("id").asLong()).isEqualTo(ctx.getReportId());
        assertThat(reports.get(0).path("title").asText()).isEqualTo(REPORT_TITLE);
        assertThat(reports.get(0).path("status").asText()).isEqualTo("CONFIRMED");
    }

    @那么("项目无待确认研报")
    public void 项目无待确认研报() {
        // 门 fail-closed 的落库面：失败任务不得产出任何研报行（含 PENDING_CONFIRM）
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM research_report WHERE project_id = ?",
                Integer.class, ctx.getResearchProjectId());
        assertThat(count).as("数字不一致不出稿：项目研报行应为空").isZero();
    }

    // ———— 确认 / journal ————

    @当("确认定稿该研报")
    public void 确认定稿() throws Exception {
        mockMvc.perform(post("/api/research/reports/{id}/confirm", ctx.getReportId())
                        .session(userSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"CONFIRM\"}"))
                .andExpect(status().isOk());
    }

    @那么("项目 journal 出现「研报定稿」事件")
    public void journal出现研报定稿事件() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT type, title, content FROM journal_entry WHERE project_id = ? AND user_id = ?",
                ctx.getResearchProjectId(), ctx.getUserId());
        assertThat(rows).anySatisfy(row -> {
            assertThat(row.get("type")).isEqualTo("RESEARCH_EVENT");
            assertThat(row.get("title")).isEqualTo("研报定稿");
            assertThat((String) row.get("content")).contains(REPORT_TITLE);
        });
    }

    // ———— 场景 3：假设过期 ————

    @当("行情现值较锚定值 986.50 漂移至 1120.00（偏离超 2%）")
    public void 现值漂移() {
        // 任务书简化裁定：重查口回放 CHANGED（漂移约 13.6% > 2% 阈值）；容差判定本身 B12 单测钉死。
        // doAnswer 形态覆盖 @Before 兜底桩（when(...) 会先触发旧桩答案，matcher 占位 anchor=null 必 NPE）
        Mockito.doAnswer(invocation -> {
            ReportAnchor anchor = invocation.getArgument(1);
            return new ReportStalenessService.AnchorStatus(anchor.id(),
                    ReportStalenessService.AnchorState.CHANGED, "1120.00");
        }).when(reportAnchorRecheckPort).recheck(Mockito.any(), Mockito.any());
    }

    @那么("研报详情中假设锚点状态为 {string} 且现值为 {string}")
    public void 研报详情假设过期(String state, String currentValue) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/research/reports/{id}", ctx.getReportId())
                        .session(userSession()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode detail = mapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(detail.path("status").asText()).isEqualTo("CONFIRMED");
        JsonNode assumptions = detail.path("assumptions");
        assertThat(assumptions.isArray()).isTrue();
        assertThat(assumptions).hasSize(1);
        JsonNode anchor = assumptions.get(0).path("anchor");
        assertThat(anchor.path("tool").asText()).isEqualTo("get_quote");
        assertThat(anchor.path("state").asText()).isEqualTo(state);
        assertThat(anchor.path("currentValue").asText()).isEqualTo(currentValue);
    }

    // ── 私有助手 ─────────────────────────────────────────────────

    /** 发起（F01）：202 异步受理，body.taskId 供编排器异步执行后轮询。 */
    private void 发起研报任务() throws Exception {
        mockMvc.perform(post("/api/research/projects/{id}/report-tasks",
                        ctx.getResearchProjectId())
                        .session(userSession()))
                .andExpect(status().isAccepted());
    }

    /** 轮询至预期终态：异步编排器（reportTaskExecutor）完成前每 150ms 重读；终态不符/超时即断言失败。 */
    private JsonNode pollTaskUntil(String expectedStatus) throws Exception {
        long deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS;
        JsonNode body = null;
        while (System.currentTimeMillis() < deadline) {
            body = latestTask();
            String status = body.path("status").asText();
            if (expectedStatus.equals(status)) {
                return body;
            }
            if ("SUCCEEDED".equals(status) || "FAILED".equals(status)) {
                break; // 已终态但非预期——跳出交由下方断言给出可读失败
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        assertThat(body).as("任务应轮询至 " + expectedStatus).isNotNull();
        assertThat(body.path("status").asText()).isEqualTo(expectedStatus);
        return body;
    }

    private JsonNode latestTask() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/research/projects/{id}/report-tasks/latest",
                        ctx.getResearchProjectId())
                        .session(userSession()))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** 项目研报列表中该研报（ctx.reportId）的状态断言。 */
    private void 断言项目研报状态(String status) throws Exception {
        JsonNode reports = 研报列表();
        assertThat(reports).hasSize(1);
        JsonNode row = reports.get(0);
        assertThat(row.path("id").asLong()).isEqualTo(ctx.getReportId());
        assertThat(row.path("status").asText()).isEqualTo(status);
    }

    private JsonNode 研报列表() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/research/projects/{id}/reports",
                        ctx.getResearchProjectId())
                        .session(userSession()))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** 已审核用户登录会话（懒初始化，照 AuthSteps.login 先例；密码沿 PortfolioSteps.已审核用户 的 abc12345）。 */
    private MockHttpSession userSession() throws Exception {
        if (ctx.getUserSession() == null) {
            MvcResult result = mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + ctx.getUsername()
                                    + "\",\"password\":\"abc12345\"}"))
                    .andExpect(status().isOk())
                    .andReturn();
            // MockMvc 不会依据 JSESSIONID cookie 重建会话，需显式传递登录产生的 MockHttpSession
            ctx.setUserSession((MockHttpSession) result.getRequest().getSession(false));
        }
        return ctx.getUserSession();
    }

    /** 单条工具真值（内置工具、data 时点、非 MCP——比对池可参与 verified 锚定）。 */
    private static ToolInvocation truth(String toolName, String resultText) {
        return new ToolInvocation(toolName, Map.of("code", "600519"), resultText, List.of(),
                "2026-10-11 09:00:00", ToolInvocation.AsOfKind.DATA, false, false, 120L);
    }

    /** 模拟一轮：PRE_CALL fresh 清池（生产时序）→ 装载本轮真值（反射：trust 包私有成员，B9 单测同款）。 */
    private static void seedPool(RuntimeContext rc, ToolInvocation... invocations) {
        try {
            Method fresh = TrustContext.class.getDeclaredMethod("fresh", RuntimeContext.class);
            fresh.setAccessible(true);
            fresh.invoke(null, rc);
            Method record = TrustContext.class.getDeclaredMethod("record", ToolInvocation.class);
            record.setAccessible(true);
            Object pool = TrustContext.current(rc);
            for (ToolInvocation invocation : invocations) {
                record.invoke(pool, invocation);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("真值池反射注入失败", e);
        }
    }

    /** 围栏 JSON 研报 payload（B6 契约形态，照 ReportWorkflowOrchestratorTest.fencedReport）。 */
    private String fencedReport(String title, String contentMd, String limitations) {
        ObjectNode node = mapper.createObjectNode();
        node.put("specVersion", 1).put("title", title)
                .put("contentMd", contentMd).put("limitations", limitations);
        return "```research-report\n" + node + "\n```";
    }
}
