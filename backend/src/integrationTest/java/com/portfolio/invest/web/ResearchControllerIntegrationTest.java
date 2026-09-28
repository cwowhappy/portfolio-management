package com.portfolio.invest.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.portfolio.invest.application.market.OrchestratingMarketDataService;
import com.portfolio.invest.domain.market.Quote;
import com.portfolio.invest.domain.user.UserRepository;
import com.portfolio.invest.domain.wiki.WikiEntry;
import com.portfolio.invest.domain.wiki.WikiEntryRepository;
import com.portfolio.invest.support.PostgresTestSupport;
import com.portfolio.invest.support.RecordingMailSender;
import com.portfolio.invest.support.TestCodes;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** 研究项目 REST 端点集成测试：真实 PG 全端点 + 404 隔离 + timeline 含研究事件（覆盖 Review Focus 1/2/3/4/5）。 */
@SpringBootTest
@AutoConfigureMockMvc
class ResearchControllerIntegrationTest extends PostgresTestSupport {

    /** 发信桩：@Primary 覆盖未配置 SMTP 的 SmtpMailSender，注册发码走桩取码。 */
    @TestConfiguration
    static class MailStub {
        @Bean
        @Primary
        RecordingMailSender recordingMailSender() {
            return new RecordingMailSender();
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired RecordingMailSender mailStub;
    @Autowired JdbcTemplate jdbcTemplate;

    /** 隔离网络（照 AguiChartIntegrationTest）：@Primary 缓存装饰器保持真实，行情编排打桩。 */
    @MockitoBean
    OrchestratingMarketDataService orchestratingMarketDataService;

    /** 回流 wiki 写桩位：真实入库（标题/标记/软引用可断言），降级用例可单点打失败（P4-T3）。 */
    @MockitoSpyBean
    WikiEntryRepository wikiEntryRepository;

    @DisplayName("主链路：立项→列表过滤→PATCH 标记→策略两级状态机→证伪条件→归档默认隐藏")
    @Test
    void givenLoggedInUser_whenFullLifecycle_thenAllEndpointsBehave() throws Exception {
        register("res_alice", "abc12345");
        approve("res_alice");
        MockHttpSession session = login("res_alice", "abc12345");

        // 立项（withTemplate）→ 201
        MvcResult created = mockMvc.perform(post("/api/research/projects").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stockCode\":\"600519\",\"stockName\":\"贵州茅台\","
                                + "\"industryCode\":\"801120\",\"title\":\"茅台扩产研究\",\"withTemplate\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.stockCode").value("600519"))
                .andExpect(jsonPath("$.stockName").value("贵州茅台"))
                .andExpect(jsonPath("$.industryCode").value("801120"))
                .andExpect(jsonPath("$.title").value("茅台扩产研究"))
                .andExpect(jsonPath("$.currentStage").value("NEW_ANALYSIS"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();
        long projectId = ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();

        // 列表过滤（stage/q）
        mockMvc.perform(get("/api/research/projects").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get("/api/research/projects").session(session).param("stage", "STRATEGY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/research/projects").session(session).param("q", "茅台"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get("/api/research/projects").session(session).param("q", "找不到的词"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // 详情：未建策略 → strategy null；NEW_ANALYSIS 立项即 AUTO 完成（S6 简化裁定）
        mockMvc.perform(get("/api/research/projects/{id}", projectId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.project.id").value(projectId))
                .andExpect(jsonPath("$.strategy").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.falsifiers.length()").value(0))
                .andExpect(jsonPath("$.completions.NEW_ANALYSIS.status").value("COMPLETED"))
                .andExpect(jsonPath("$.completions.NEW_ANALYSIS.basis").value("AUTO"))
                .andExpect(jsonPath("$.completions.STRATEGY.status").value("NOT_STARTED"))
                .andExpect(jsonPath("$.completions.POSITION.status").value("NOT_STARTED"))
                .andExpect(jsonPath("$.completions.REVIEW.status").value("NOT_STARTED"));

        // 未建草稿 GET 策略 → 404
        mockMvc.perform(get("/api/research/projects/{id}/strategy", projectId).session(session))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        // PATCH：改标题 + 切阶段 + 手动标记 REVIEW 完成
        mockMvc.perform(patch("/api/research/projects/{id}", projectId).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"茅台扩产研究（修订）\",\"currentStage\":\"STRATEGY\","
                                + "\"manualMarks\":[{\"stage\":\"REVIEW\",\"state\":\"COMPLETED\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.project.title").value("茅台扩产研究（修订）"))
                .andExpect(jsonPath("$.project.currentStage").value("STRATEGY"))
                .andExpect(jsonPath("$.completions.REVIEW.status").value("COMPLETED"))
                .andExpect(jsonPath("$.completions.REVIEW.basis").value("MANUAL"))
                .andExpect(jsonPath("$.completions.STRATEGY.status").value("IN_PROGRESS"));

        // REOPENED 覆盖 AUTO → IN_PROGRESS；再标 NULL 清除覆盖回 AUTO（Review Focus 3）
        mockMvc.perform(patch("/api/research/projects/{id}", projectId).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"manualMarks\":[{\"stage\":\"NEW_ANALYSIS\",\"state\":\"REOPENED\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completions.NEW_ANALYSIS.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.completions.NEW_ANALYSIS.basis").value("PENDING"));
        mockMvc.perform(patch("/api/research/projects/{id}", projectId).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"manualMarks\":[{\"stage\":\"NEW_ANALYSIS\",\"state\":\"NULL\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completions.NEW_ANALYSIS.status").value("COMPLETED"))
                .andExpect(jsonPath("$.completions.NEW_ANALYSIS.basis").value("AUTO"));

        // 策略暂存：DRAFT 态区间倒挂被 DB CHECK ck_strategy_valuation 兜底拒绝（400 INVALID_DATA）
        mockMvc.perform(put("/api/research/projects/{id}/strategy", projectId).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"thesis\":\"扩产逻辑\",\"valuationLow\":20,\"valuationHigh\":10}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DATA"));

        // 字段不完整可存：估值下限缺省（CHECK 放行 NULL），DRAFT 暂存成功
        mockMvc.perform(put("/api/research/projects/{id}/strategy", projectId).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"thesis\":\"扩产逻辑\",\"valuationHigh\":20,\"positionPlan\":\"两成仓\","
                                + "\"buyConditions\":\"回踩买入\",\"riskNotes\":\"需求不及预期\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("DRAFT"))
                .andExpect(jsonPath("$.thesis").value("扩产逻辑"));

        // 定稿：下限缺失 → 422（domain 先拦，Review Focus 1）
        mockMvc.perform(post("/api/research/projects/{id}/strategy/finalize", projectId).session(session))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALUATION_RANGE_INVALID"));

        // 修正区间后定稿成功 → FINALIZED；STRATEGY 阶段 AUTO 完成
        mockMvc.perform(put("/api/research/projects/{id}/strategy", projectId).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"valuationLow\":10,\"valuationHigh\":20}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("DRAFT"));
        mockMvc.perform(post("/api/research/projects/{id}/strategy/finalize", projectId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("FINALIZED"))
                .andExpect(jsonPath("$.finalizedAt").isNotEmpty());
        mockMvc.perform(get("/api/research/projects/{id}", projectId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completions.STRATEGY.status").value("COMPLETED"))
                .andExpect(jsonPath("$.completions.STRATEGY.basis").value("AUTO"));

        // FINALIZED 态直接 PUT 暂存 → 422，须先 revise（Review Focus 2）
        mockMvc.perform(put("/api/research/projects/{id}/strategy", projectId).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"thesis\":\"改逻辑\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("STRATEGY_FINALIZED"));

        // 证伪条件整替：PREDICATE + EVENT
        mockMvc.perform(put("/api/research/projects/{id}/falsifiers", projectId).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"kind\":\"PREDICATE\",\"predicate\":\"PRICE_BELOW\",\"threshold\":13.5,"
                                + "\"note\":\"跌破估值下限\"},"
                                + "{\"kind\":\"EVENT\",\"note\":\"扩产延期超半年\"}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].kind").value("PREDICATE"))
                .andExpect(jsonPath("$[0].predicate").value("PRICE_BELOW"))
                .andExpect(jsonPath("$[0].id").isNumber())
                .andExpect(jsonPath("$[1].kind").value("EVENT"));
        mockMvc.perform(get("/api/research/projects/{id}/falsifiers", projectId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        // 谓词类缺 predicate → 400（Falsifier 工厂校验传导）
        mockMvc.perform(put("/api/research/projects/{id}/falsifiers", projectId).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"kind\":\"PREDICATE\",\"threshold\":13.5}]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PREDICATE_REQUIRED"));

        // 修订回草稿；DRAFT 态再 revise 宽容 no-op；STRATEGY 完成度回落进行中
        mockMvc.perform(post("/api/research/projects/{id}/strategy/revise", projectId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("DRAFT"));
        mockMvc.perform(post("/api/research/projects/{id}/strategy/revise", projectId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("DRAFT"));
        mockMvc.perform(get("/api/research/projects/{id}", projectId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completions.STRATEGY.status").value("IN_PROGRESS"));

        // 归档：默认列表隐藏、显式 ?status=ARCHIVED 可查（Review Focus 5）
        mockMvc.perform(post("/api/research/projects/{id}/archive", projectId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));
        mockMvc.perform(get("/api/research/projects").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/research/projects").session(session).param("status", "ARCHIVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value((int) projectId));
    }

    @DisplayName("非本人项目全端点 404 隔离（不泄漏存在性）；未登录 401")
    @Test
    void givenOthersProject_whenAccessAnyEndpoint_then404ForAll() throws Exception {
        register("res_owner", "abc12345");
        register("res_intruder", "abc12345");
        approve("res_owner");
        approve("res_intruder");

        // 未登录 → 401
        mockMvc.perform(get("/api/research/projects"))
                .andExpect(status().isUnauthorized());

        MvcResult created = mockMvc.perform(post("/api/research/projects")
                        .session(login("res_owner", "abc12345"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stockCode\":\"600519\",\"stockName\":\"贵州茅台\",\"title\":\"隔离验证\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        long projectId = ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();
        MockHttpSession intruder = login("res_intruder", "abc12345");

        // 他人访问读写全端点 → 一律 404 NOT_FOUND
        mockMvc.perform(get("/api/research/projects/{id}", projectId).session(intruder))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(patch("/api/research/projects/{id}", projectId).session(intruder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"越权改名\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(post("/api/research/projects/{id}/archive", projectId).session(intruder))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/research/projects/{id}/strategy", projectId).session(intruder))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/research/projects/{id}/strategy", projectId).session(intruder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"thesis\":\"越权\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/research/projects/{id}/strategy/finalize", projectId).session(intruder))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/research/projects/{id}/strategy/revise", projectId).session(intruder))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/research/projects/{id}/falsifiers", projectId).session(intruder))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/research/projects/{id}/falsifiers", projectId).session(intruder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[]"))
                .andExpect(status().isNotFound());
        // P3 新端点同口径隔离：entry-plan / checks / hits
        mockMvc.perform(get("/api/research/projects/{id}/entry-plan", projectId).session(intruder))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/research/projects/{id}/entry-plan", projectId).session(intruder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batches\":[]}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/research/projects/{id}/checks/preview", projectId).session(intruder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"checkType\":\"BUY\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/research/projects/{id}/checks", projectId).session(intruder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"checkType\":\"BUY\",\"result\":\"CONFIRMED\",\"items\":[{\"metric\":\"能力圈\",\"outcome\":\"PASS\"}]}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/research/projects/{id}/falsifier/hits", projectId).session(intruder))
                .andExpect(status().isNotFound());
        // P4 证伪评审端点同口径隔离
        mockMvc.perform(get("/api/research/projects/{id}/falsifier/reviews", projectId).session(intruder))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/research/projects/{id}/falsifier/reviews", projectId).session(intruder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conclusion\":\"HOLD\",\"reason\":\"越权评审\"}"))
                .andExpect(status().isNotFound());
        // P4 复盘/回流/建议/检查留痕端点同口径隔离
        mockMvc.perform(get("/api/research/projects/{id}/reviews", projectId).session(intruder))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/research/projects/{id}/reviews", projectId).session(intruder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tier\":\"MONTHLY\",\"periodStart\":\"2026-02-01\",\"periodEnd\":\"2026-02-28\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/research/projects/{id}/reviews/{rid}", projectId, 1L).session(intruder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":{\"q1\":\"越权\"}}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/research/projects/{id}/reviews/{rid}/reflux", projectId, 1L).session(intruder))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/research/projects/{id}/checks", projectId).session(intruder))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/research/projects/{id}/feedback", projectId).session(intruder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stage\":\"REVIEW\",\"content\":\"越权建议\"}"))
                .andExpect(status().isNotFound());

        // 归属者不受影响，且他人未留下任何改动
        mockMvc.perform(get("/api/research/projects/{id}", projectId).session(login("res_owner", "abc12345")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.project.title").value("隔离验证"))
                .andExpect(jsonPath("$.project.status").value("ACTIVE"));
    }

    @DisplayName("研究事件并入时间线 + journal/wiki 按项目反查（F08）")
    @Test
    void givenProjectWithEvents_whenTimelineAndReverseLookup_thenEventsVisible() throws Exception {
        register("res_carol", "abc12345");
        approve("res_carol");
        MockHttpSession session = login("res_carol", "abc12345");

        MvcResult created = mockMvc.perform(post("/api/research/projects").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stockCode\":\"600519\",\"stockName\":\"贵州茅台\",\"title\":\"茅台研究\","
                                + "\"withTemplate\":true}"))
                .andExpect(status().isCreated())
                .andReturn();
        long projectId = ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();
        mockMvc.perform(patch("/api/research/projects/{id}", projectId).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"manualMarks\":[{\"stage\":\"STRATEGY\",\"state\":\"COMPLETED\"}]}"))
                .andExpect(status().isOk());

        // 时间线自动并入研究事件（T5 零改动路径）
        mockMvc.perform(get("/api/journal/timeline").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.type == 'RESEARCH_EVENT' && @.title == '立项：贵州茅台')]").isArray())
                .andExpect(jsonPath("$[?(@.title == '立项：贵州茅台')].refType")
                        .value(org.hamcrest.Matchers.hasItem("JOURNAL")))
                .andExpect(jsonPath("$[?(@.type == 'RESEARCH_EVENT' && @.title == '模板已带入')]").isArray())
                .andExpect(jsonPath("$[?(@.type == 'RESEARCH_EVENT' && @.title == '手动标记：制定投资策略')]").isArray());

        // journal 按项目反查：只含该项目事件（立项 + 模板已带入 + 手动标记 = 3）
        mockMvc.perform(get("/api/journal/entries").session(session).param("projectId", String.valueOf(projectId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[?(@.type != 'RESEARCH_EVENT')]").isEmpty());

        // wiki 按项目反查：v1 无回流条目 → 空
        mockMvc.perform(get("/api/wiki/entries").session(session).param("projectId", String.valueOf(projectId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @DisplayName("P3 主链路：建仓计划（Σ=1 过/>1 拒 422/kelly）→ 检查（UNSET 中性/preview 不落库/越过留痕 422）→ 证伪命中合并")
    @Test
    void givenPlanAndRules_whenEntryCheckHitFlow_thenAllEndpointsBehave() throws Exception {
        register("res_plan_a", "abc12345");
        approve("res_plan_a");
        MockHttpSession session = login("res_plan_a", "abc12345");
        Long userId = userRepository.findByUsername("res_plan_a").orElseThrow().id();

        MvcResult created = mockMvc.perform(post("/api/research/projects").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stockCode\":\"600519\",\"stockName\":\"贵州茅台\",\"title\":\"茅台建仓\"}"))
                .andExpect(status().isCreated()).andReturn();
        long projectId = ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();

        // —— 建仓计划 ——
        // Σratio = 0.6 + 0.4 = 1.0 恰好 → 通过（Review Focus 3：> 1 才拒）；kelly 0.6/2.0 → 0.4（Ruling-15）
        mockMvc.perform(put("/api/research/projects/{id}/entry-plan", projectId).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"winRate\":0.6,\"payoffRatio\":2.0,\"batches\":["
                                + "{\"seq\":1,\"priceLow\":12,\"priceHigh\":13,\"quantity\":100,\"ratio\":0.6},"
                                + "{\"seq\":2,\"priceLow\":10,\"priceHigh\":11,\"quantity\":100,\"amount\":1200,\"ratio\":0.4}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.kellyRatio").value(0.4))
                .andExpect(jsonPath("$.batches.length()").value(2));
        mockMvc.perform(get("/api/research/projects/{id}/entry-plan", projectId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kellyRatio").value(0.4))
                .andExpect(jsonPath("$.batches[0].ratio").value(0.6))
                .andExpect(jsonPath("$.batches[1].amount").value(1200.0));

        // Σratio = 1.01 → 422 RATIO_SUM_EXCEEDED（唯一硬拒绝，D5）
        mockMvc.perform(put("/api/research/projects/{id}/entry-plan", projectId).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batches\":[{\"seq\":1,\"priceLow\":12,\"priceHigh\":13,\"quantity\":1,\"ratio\":0.51},"
                                + "{\"seq\":2,\"priceLow\":10,\"priceHigh\":11,\"quantity\":1,\"ratio\":0.5}]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RATIO_SUM_EXCEEDED"));
        // 整替后仍是首轮计划（拒绝不落库）
        mockMvc.perform(get("/api/research/projects/{id}/entry-plan", projectId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batches.length()").value(2));

        // —— 行情/估值/规则 fixtures：quote 桩 + stock_valuation_daily 当日快照 + PrincipleRule ——
        Mockito.when(orchestratingMarketDataService.quote("600519")).thenReturn(
                new Quote("600519", "贵州茅台", 12.34, 0, 0, 0, 0, 0, 0, 0, 0, null, null, "2026-09-28 15:00:00"));
        LocalDate today = LocalDate.now();
        jdbcTemplate.update("DELETE FROM stock_valuation_daily WHERE trading_day = ? AND stock_code = '600519'", today);
        jdbcTemplate.update("INSERT INTO stock_valuation_daily(trading_day, stock_code, stock_name, pe_ttm, pb, close) "
                        + "VALUES (?,?,?,?,?,?)", today, "600519", "贵州茅台", 25.5, 8.2, 12.34);
        jdbcTemplate.update("DELETE FROM principle_rule WHERE user_id = ?", userId);
        jdbcTemplate.update("INSERT INTO principle_rule(user_id, metric, threshold, enabled, description) "
                + "VALUES (?,?,?,?,?)", userId, "SINGLE_POSITION_RATIO", 0.5, true, "单票上限");

        // —— 检查 preview（BUY）：规则项命中/未配置 UNSET + F01 四项；不落库 ——
        mockMvc.perform(post("/api/research/projects/{id}/checks/preview", projectId).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"checkType\":\"BUY\",\"f01MustItems\":"
                                + "{\"能力圈\":true,\"安全边际\":true,\"估值核对\":true,\"买入条件\":true}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(8))
                // 无持仓：计划 Σratio=1.0 > 0.5 → HIT（currentValue 回显）
                .andExpect(jsonPath("$[0].metric").value("SINGLE_POSITION_RATIO"))
                .andExpect(jsonPath("$[0].outcome").value("HIT"))
                .andExpect(jsonPath("$[0].threshold").value(0.5))
                .andExpect(jsonPath("$[0].currentValue").value(1.0))
                // 未配置指标 → UNSET 中性（Review Focus 2：非 PASS 非 HIT）；pe 25.5 可得但无规则
                .andExpect(jsonPath("$[1].outcome").value("UNSET"))
                .andExpect(jsonPath("$[2].metric").value("STOCK_PE_MAX"))
                .andExpect(jsonPath("$[2].outcome").value("UNSET"))
                .andExpect(jsonPath("$[4].metric").value("能力圈"))
                .andExpect(jsonPath("$[4].outcome").value("PASS"))
                .andExpect(jsonPath("$[7].outcome").value("PASS"));
        Integer records = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM research_check_record WHERE project_id = ?", Integer.class, projectId);
        org.assertj.core.api.Assertions.assertThat(records).isZero(); // preview 不落库
        Integer eventsBefore = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM journal_entry WHERE project_id = ? AND title LIKE '纪律检查%'", Integer.class, projectId);
        org.assertj.core.api.Assertions.assertThat(eventsBefore).isZero();

        // —— 检查提交 ——
        // OVERRIDDEN 缺理由 → 422（Review Focus 1），不落库不写事件
        mockMvc.perform(post("/api/research/projects/{id}/checks", projectId).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"checkType\":\"BUY\",\"result\":\"OVERRIDDEN\",\"overrideReason\":\"  \","
                                + "\"items\":[{\"metric\":\"SINGLE_POSITION_RATIO\",\"threshold\":0.5,"
                                + "\"currentValue\":1.0,\"outcome\":\"HIT\"}]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OVERRIDE_REASON_REQUIRED"));
        assertThatCount("research_check_record", projectId, 0);
        // OVERRIDDEN 携理由 → 201 + journal 事件「纪律检查：买入/越过」
        mockMvc.perform(post("/api/research/projects/{id}/checks", projectId).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"checkType\":\"BUY\",\"result\":\"OVERRIDDEN\",\"overrideReason\":\"计划外机会，仓位已复核\","
                                + "\"items\":[{\"metric\":\"SINGLE_POSITION_RATIO\",\"threshold\":0.5,"
                                + "\"currentValue\":1.0,\"outcome\":\"HIT\"},"
                                + "{\"metric\":\"能力圈\",\"outcome\":\"PASS\"}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.checkType").value("BUY"))
                .andExpect(jsonPath("$.result").value("OVERRIDDEN"))
                .andExpect(jsonPath("$.overrideReason").value("计划外机会，仓位已复核"))
                .andExpect(jsonPath("$.items.length()").value(2));
        assertThatCount("research_check_record", projectId, 1);
        mockMvc.perform(get("/api/journal/entries").session(session).param("projectId", String.valueOf(projectId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.type == 'RESEARCH_EVENT' && @.title == '纪律检查：买入/越过')]").isArray());

        // —— 证伪条件 + 命中合并视图（D21）——
        mockMvc.perform(put("/api/research/projects/{id}/strategy", projectId).session(session)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"thesis\":\"扩产逻辑\"}"))
                .andExpect(status().isOk());
        MvcResult falsifiers = mockMvc.perform(put("/api/research/projects/{id}/falsifiers", projectId).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"kind\":\"PREDICATE\",\"predicate\":\"PRICE_BELOW\",\"threshold\":13.5,\"note\":\"跌破下限\"},"
                                + "{\"kind\":\"EVENT\",\"note\":\"扩产延期超半年\"}]"))
                .andExpect(status().isOk()).andReturn();
        long falsifierId = ((Number) JsonPath.read(falsifiers.getResponse().getContentAsString(), "$[0].id")).longValue();

        // SELL preview：额外注入证伪核对条目（F12，UNSET 待核对）
        mockMvc.perform(post("/api/research/projects/{id}/checks/preview", projectId).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"checkType\":\"SELL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(10)) // 4 规则 + 4 F01 + 2 证伪核对
                .andExpect(jsonPath("$[8].metric").value("PRICE_BELOW 13.5000"))
                .andExpect(jsonPath("$[8].outcome").value("UNSET"))
                .andExpect(jsonPath("$[9].metric").value("扩产延期超半年"));

        // 实时命中：收盘 12.34 < 13.5 → hit + 可解释 basis（含口径尾注，Ruling-17）；EVENT 待人工勾选
        mockMvc.perform(get("/api/research/projects/{id}/falsifier/hits", projectId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].falsifierId").value((int) falsifierId))
                .andExpect(jsonPath("$[0].realtime").value(true))
                .andExpect(jsonPath("$[0].hit").value(true))
                .andExpect(jsonPath("$[0].basis").value(org.hamcrest.Matchers.containsString("收盘价 12.34")))
                .andExpect(jsonPath("$[0].basis").value(org.hamcrest.Matchers.containsString("东财收盘及估值")))
                .andExpect(jsonPath("$[1].kind").value("EVENT"))
                .andExpect(jsonPath("$[1].pending").value(true))
                .andExpect(jsonPath("$[1].basis").value("待人工勾选"));

        // 历史 hit 行合并：落一行留痕 → realtime=false 行追加（createdAt 提供命中时间）
        jdbcTemplate.update("INSERT INTO research_falsifier_hit(project_id, falsifier_id, basis) VALUES (?,?,?)",
                projectId, falsifierId, "收盘价 12.10 < 下限 13.5（东财收盘 2026-09-25）");
        mockMvc.perform(get("/api/research/projects/{id}/falsifier/hits", projectId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[2].realtime").value(false))
                .andExpect(jsonPath("$[2].id").isNumber())
                .andExpect(jsonPath("$[2].kind").value("PREDICATE"))
                .andExpect(jsonPath("$[2].hitAt").isNotEmpty())
                .andExpect(jsonPath("$[2].basis").value(org.hamcrest.Matchers.containsString("2026-09-25")));

        // fixtures 清理（stock_valuation_daily/principle_rule 跨用例共享表）
        jdbcTemplate.update("DELETE FROM stock_valuation_daily WHERE trading_day = ? AND stock_code = '600519'", today);
        jdbcTemplate.update("DELETE FROM principle_rule WHERE user_id = ?", userId);
    }

    @DisplayName("证伪谓词缺收盘价：该条件 skipped（basis 无最近价/估值），不自动命中（Review Focus 4）")
    @Test
    void givenNoQuoteAndNoValuation_whenGetHits_thenPredicateSkipped() throws Exception {
        register("res_plan_b", "abc12345");
        approve("res_plan_b");
        MockHttpSession session = login("res_plan_b", "abc12345");
        MvcResult created = mockMvc.perform(post("/api/research/projects").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stockCode\":\"600519\",\"stockName\":\"贵州茅台\",\"title\":\"缺行情命中\"}"))
                .andExpect(status().isCreated()).andReturn();
        long projectId = ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();

        mockMvc.perform(put("/api/research/projects/{id}/strategy", projectId).session(session)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"thesis\":\"扩产逻辑\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/research/projects/{id}/falsifiers", projectId).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"kind\":\"PREDICATE\",\"predicate\":\"PRICE_BELOW\",\"threshold\":13.5}]"))
                .andExpect(status().isOk());

        // 行情桩抛源异常 + 无估值快照 → close/pe/pb 全缺
        Mockito.when(orchestratingMarketDataService.quote("600519"))
                .thenThrow(new com.portfolio.invest.domain.market.MarketDataException("SOURCE_UNAVAILABLE", "源不可用"));

        mockMvc.perform(get("/api/research/projects/{id}/falsifier/hits", projectId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].skipped").value(true))
                .andExpect(jsonPath("$[0].hit").value(false))
                .andExpect(jsonPath("$[0].basis").value("无最近价/估值"));
    }

    @DisplayName("P4 证伪评审链路：提交（落库+hit 回填+journal 事件）→ REVISE 提示位不自动改策略 → 422/404 边界")
    @Test
    void givenFalsifierHit_whenReviewFlow_thenPersistBackfillEventAndHint() throws Exception {
        register("res_rev_a", "abc12345");
        approve("res_rev_a");
        MockHttpSession session = login("res_rev_a", "abc12345");

        MvcResult created = mockMvc.perform(post("/api/research/projects").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stockCode\":\"600519\",\"stockName\":\"贵州茅台\",\"title\":\"茅台证伪评审\"}"))
                .andExpect(status().isCreated()).andReturn();
        long projectId = ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();

        // 策略定稿 + 证伪条件（hit.falsifier_id FK 依赖）
        mockMvc.perform(put("/api/research/projects/{id}/strategy", projectId).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"thesis\":\"扩产逻辑\",\"valuationLow\":10,\"valuationHigh\":20}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/research/projects/{id}/strategy/finalize", projectId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("FINALIZED"));
        MvcResult falsifiers = mockMvc.perform(put("/api/research/projects/{id}/falsifiers", projectId).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"kind\":\"PREDICATE\",\"predicate\":\"PRICE_BELOW\",\"threshold\":13.5,\"note\":\"跌破下限\"}]"))
                .andExpect(status().isOk()).andReturn();
        long falsifierId = ((Number) JsonPath.read(falsifiers.getResponse().getContentAsString(), "$[0].id")).longValue();

        // 落一行 hit 留痕（日终扫描/实时命中由 P3-T5 交付，此处直插模拟）
        jdbcTemplate.update("INSERT INTO research_falsifier_hit(project_id, falsifier_id, basis) VALUES (?,?,?)",
                projectId, falsifierId, "收盘价 12.10 < 下限 13.5（东财收盘 2026-09-25）");
        Integer hitId = jdbcTemplate.queryForObject(
                "SELECT id FROM research_falsifier_hit WHERE project_id = ?", Integer.class, projectId);

        // 空白理由 → 422 REVIEW_REASON_REQUIRED（照 OVERRIDE_REASON_REQUIRED 先例），不落库
        mockMvc.perform(post("/api/research/projects/{id}/falsifier/reviews", projectId).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hitId\":" + hitId + ",\"conclusion\":\"EXIT\",\"reason\":\"  \"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REVIEW_REASON_REQUIRED"));
        assertThatCount("research_falsifier_review", projectId, 0);

        // EXIT 提交 → 201：落库 + hit 回填 + journal 事件；非 REVISE 无提示位
        MvcResult reviewed = mockMvc.perform(post("/api/research/projects/{id}/falsifier/reviews", projectId).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hitId\":" + hitId + ",\"conclusion\":\"EXIT\",\"reason\":\"跌破下限且基本面恶化\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.projectId").value((int) projectId))
                .andExpect(jsonPath("$.hitId").value(hitId))
                .andExpect(jsonPath("$.conclusion").value("EXIT"))
                .andExpect(jsonPath("$.reason").value("跌破下限且基本面恶化"))
                .andExpect(jsonPath("$.suggestStrategyRevise").value(false))
                .andReturn();
        long reviewId = ((Number) JsonPath.read(reviewed.getResponse().getContentAsString(), "$.id")).longValue();
        assertThatCount("research_falsifier_review", projectId, 1);
        Long backfilled = jdbcTemplate.queryForObject(
                "SELECT review_id FROM research_falsifier_hit WHERE id = ?", Long.class, hitId);
        org.assertj.core.api.Assertions.assertThat(backfilled).isEqualTo(reviewId); // 回填落列
        mockMvc.perform(get("/api/journal/entries").session(session).param("projectId", String.valueOf(projectId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.type == 'RESEARCH_EVENT' && @.title == '证伪评审：退出')]").isArray());

        // REVISE 提交（无 hitId）→ 201 suggestStrategyRevise=true；策略仍 FINALIZED（不自动改，Review Focus 3）
        mockMvc.perform(post("/api/research/projects/{id}/falsifier/reviews", projectId).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conclusion\":\"REVISE\",\"reason\":\"证伪成立，投资逻辑需修订\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.suggestStrategyRevise").value(true))
                .andExpect(jsonPath("$.hitId").value(org.hamcrest.Matchers.nullValue()));
        mockMvc.perform(get("/api/research/projects/{id}/strategy", projectId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("FINALIZED"));

        // GET 列表：两条 createdAt 倒序（REVISE 在前）
        mockMvc.perform(get("/api/research/projects/{id}/falsifier/reviews", projectId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].conclusion").value("REVISE"))
                .andExpect(jsonPath("$[0].suggestStrategyRevise").value(true))
                .andExpect(jsonPath("$[1].conclusion").value("EXIT"));

        // hitId 不存在 → 404 NOT_FOUND（不落库）
        mockMvc.perform(post("/api/research/projects/{id}/falsifier/reviews", projectId).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hitId\":99999999,\"conclusion\":\"HOLD\",\"reason\":\"再观察一个报告期\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThatCount("research_falsifier_review", projectId, 2);
    }

    @DisplayName("P4 复盘闭环：创建定格（PUT 后快照不变）→ 修正圈选 → 回流 wiki（幂等）→ 建议/检查留痕只收集")
    @Test
    void givenReviewFlow_whenCreateCorrectRefluxFeedback_thenFrozenSnapshotWikiReflowAndCollect() throws Exception {
        register("res_t3_a", "abc12345");
        approve("res_t3_a");
        MockHttpSession session = login("res_t3_a", "abc12345");

        MvcResult created = mockMvc.perform(post("/api/research/projects").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stockCode\":\"600519\",\"stockName\":\"贵州茅台\",\"title\":\"茅台复盘研究\"}"))
                .andExpect(status().isCreated()).andReturn();
        long projectId = ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();

        // 建仓计划一批（圈选窗口输入；无组合流水 → tradeIds 空 + 各数据段「无数据」，Focus 1）
        mockMvc.perform(put("/api/research/projects/{id}/entry-plan", projectId).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batches\":[{\"seq\":1,\"priceLow\":12,\"priceHigh\":13,\"quantity\":100,\"ratio\":1.0}]}"))
                .andExpect(status().isOk());

        // —— 创建即定格：POST 201，快照含口径标注；PENDING 态 ——
        MvcResult reviewed = mockMvc.perform(post("/api/research/projects/{id}/reviews", projectId).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tier\":\"MONTHLY\",\"periodStart\":\"2026-02-01\",\"periodEnd\":\"2026-02-28\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.projectId").value((int) projectId))
                .andExpect(jsonPath("$.tier").value("MONTHLY"))
                .andExpect(jsonPath("$.refluxState").value("PENDING"))
                .andExpect(jsonPath("$.wikiEntryId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.tradeIds.length()").value(0))
                .andExpect(jsonPath("$.snapshot.periodStart").value("2026-02-01"))
                .andExpect(jsonPath("$.snapshot.periodEnd").value("2026-02-28"))
                .andExpect(jsonPath("$.snapshot.priceBasis").value("东财收盘"))
                .andExpect(jsonPath("$.snapshot.navSeries").value("无数据"))
                .andExpect(jsonPath("$.snapshot.periodReturn").value("无数据"))
                .andExpect(jsonPath("$.snapshot.trades").value("无数据"))
                .andReturn();
        long reviewId = ((Number) JsonPath.read(reviewed.getResponse().getContentAsString(), "$.id")).longValue();
        String frozen = jdbcTemplate.queryForObject(
                "SELECT auto_snapshot::text FROM research_review WHERE id = ?", String.class, reviewId);

        // 二次 GET：快照仍为定格值（F14 不复算历史）
        mockMvc.perform(get("/api/research/projects/{id}/reviews", projectId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value((int) reviewId))
                .andExpect(jsonPath("$[0].snapshot.priceBasis").value("东财收盘"))
                .andExpect(jsonPath("$[0].snapshot.navSeries").value("无数据"))
                .andExpect(jsonPath("$[0].tradeIds.length()").value(0));

        // —— PUT 修正：answers/overrides 对象上送、tradeIds 去重排序；快照字段不变（定格） ——
        mockMvc.perform(put("/api/research/projects/{id}/reviews/{rid}", projectId, reviewId).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":{\"q1\":\"追高\"},\"overrides\":{\"periodReturn\":\"0.06\"},"
                                + "\"narrative\":\"复盘叙述：追高错误\",\"tradeIds\":[7,3,7]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answers.q1").value("追高"))
                .andExpect(jsonPath("$.overrides.periodReturn").value("0.06"))
                .andExpect(jsonPath("$.narrative").value("复盘叙述：追高错误"))
                .andExpect(jsonPath("$.tradeIds.length()").value(2))
                .andExpect(jsonPath("$.tradeIds[0]").value(3))
                .andExpect(jsonPath("$.tradeIds[1]").value(7))
                .andExpect(jsonPath("$.refluxState").value("PENDING"))
                .andExpect(jsonPath("$.snapshot.priceBasis").value("东财收盘"))
                .andExpect(jsonPath("$.snapshot.navSeries").value("无数据"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT auto_snapshot::text FROM research_review WHERE id = ?", String.class, reviewId))
                .isEqualTo(frozen); // 修正不动快照列（Focus：快照不可改）

        // —— 回流：wiki RESEARCH_NOTE 落库（SOP_REVIEW + projectId + narrative 内容）→ REFLOWN ——
        MvcResult refluxed = mockMvc.perform(post("/api/research/projects/{id}/reviews/{rid}/reflux",
                        projectId, reviewId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refluxState").value("REFLOWN"))
                .andExpect(jsonPath("$.wikiEntryId").isNumber())
                .andReturn();
        long wikiEntryId = ((Number) JsonPath.read(refluxed.getResponse().getContentAsString(), "$.wikiEntryId")).longValue();
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForMap(
                "SELECT type, title, category, content, project_id FROM wiki_entry WHERE id = ?", wikiEntryId))
                .containsEntry("type", "RESEARCH_NOTE")
                .containsEntry("title", "复盘·茅台复盘研究·2026-02-01~2026-02-28")
                .containsEntry("category", "SOP_REVIEW")
                .containsEntry("content", "复盘叙述：追高错误")
                .containsEntry("project_id", projectId);
        mockMvc.perform(get("/api/wiki/entries").session(session).param("projectId", String.valueOf(projectId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.category == 'SOP_REVIEW')]").isNotEmpty());

        // 幂等：二次回流返回既有 wiki_entry_id，不重复建条目（Focus 4）
        mockMvc.perform(post("/api/research/projects/{id}/reviews/{rid}/reflux", projectId, reviewId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refluxState").value("REFLOWN"))
                .andExpect(jsonPath("$.wikiEntryId").value((int) wikiEntryId));
        Integer wikiCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM wiki_entry WHERE project_id = ?", Integer.class, projectId);
        org.assertj.core.api.Assertions.assertThat(wikiCount).isEqualTo(1);

        // —— 检查留痕读取（P3-T4 deferred 端点）：两行 createdAt 倒序 ——
        mockMvc.perform(post("/api/research/projects/{id}/checks", projectId).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"checkType\":\"BUY\",\"result\":\"CONFIRMED\",\"items\":"
                                + "[{\"metric\":\"能力圈\",\"outcome\":\"PASS\"}]}"))
                .andExpect(status().isCreated());
        MvcResult secondCheck = mockMvc.perform(post("/api/research/projects/{id}/checks", projectId).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"checkType\":\"SELL\",\"result\":\"CONFIRMED\",\"items\":"
                                + "[{\"metric\":\"能力圈\",\"outcome\":\"PASS\"}]}"))
                .andExpect(status().isCreated()).andReturn();
        long secondCheckId = ((Number) JsonPath.read(secondCheck.getResponse().getContentAsString(), "$.id")).longValue();
        mockMvc.perform(get("/api/research/projects/{id}/checks", projectId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value((int) secondCheckId)) // 时间倒序
                .andExpect(jsonPath("$[0].checkType").value("SELL"))
                .andExpect(jsonPath("$[0].items.length()").value(1))
                .andExpect(jsonPath("$[1].checkType").value("BUY"));

        // —— 模板改进建议：只收集（reviewId 关联 + 无 reviewId 两种形态均落库） ——
        mockMvc.perform(post("/api/research/projects/{id}/feedback", projectId).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewId\":" + reviewId + ",\"stage\":\"REVIEW\",\"content\":\"月度模板建议增加仓位口径维度\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.stage").value("REVIEW"))
                .andExpect(jsonPath("$.reviewId").value((int) reviewId));
        mockMvc.perform(post("/api/research/projects/{id}/feedback", projectId).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stage\":\"STRATEGY\",\"content\":\"策略模板 checklist 太长\"}"))
                .andExpect(status().isCreated());
        assertThatCount("research_feedback", projectId, 2);

        // 边界：内容空白 → 422 FEEDBACK_CONTENT_REQUIRED 不落库；他项目 reviewId → 404
        mockMvc.perform(post("/api/research/projects/{id}/feedback", projectId).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stage\":\"REVIEW\",\"content\":\"  \"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FEEDBACK_CONTENT_REQUIRED"));
        mockMvc.perform(post("/api/research/projects/{id}/feedback", projectId).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewId\":99999999,\"stage\":\"REVIEW\",\"content\":\"孤儿建议\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThatCount("research_feedback", projectId, 2);
    }

    @DisplayName("P4 回流降级：叙述空白 422 先拦；wiki 写异常 502 且 reflux_state 回 PENDING（不阻断、可重试恢复）")
    @Test
    void givenWikiWriteFailure_whenReflux_then502DegradedAndRecoverable() throws Exception {
        register("res_t3_b", "abc12345");
        approve("res_t3_b");
        MockHttpSession session = login("res_t3_b", "abc12345");

        MvcResult created = mockMvc.perform(post("/api/research/projects").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stockCode\":\"600519\",\"stockName\":\"贵州茅台\",\"title\":\"降级验证\"}"))
                .andExpect(status().isCreated()).andReturn();
        long projectId = ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();
        MvcResult reviewed = mockMvc.perform(post("/api/research/projects/{id}/reviews", projectId).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tier\":\"WEEKLY\",\"periodStart\":\"2026-02-24\",\"periodEnd\":\"2026-02-28\"}"))
                .andExpect(status().isCreated()).andReturn();
        long reviewId = ((Number) JsonPath.read(reviewed.getResponse().getContentAsString(), "$.id")).longValue();

        // 未写叙述直接回流 → 422 REFLUX_NARRATIVE_REQUIRED（wiki 层零触碰）
        mockMvc.perform(post("/api/research/projects/{id}/reviews/{rid}/reflux", projectId, reviewId).session(session))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REFLUX_NARRATIVE_REQUIRED"));
        Mockito.verify(wikiEntryRepository, Mockito.never()).save(Mockito.any(WikiEntry.class));

        // 补叙述 → wiki 写失败 → 502 文案；复盘行保持 PENDING、零 wiki 条目（降级不阻断）
        mockMvc.perform(put("/api/research/projects/{id}/reviews/{rid}", projectId, reviewId).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":{\"q1\":\"ok\"},\"narrative\":\"叙述\"}"))
                .andExpect(status().isOk());
        Mockito.doThrow(new RuntimeException("wiki down"))
                .when(wikiEntryRepository).save(Mockito.any(WikiEntry.class));
        mockMvc.perform(post("/api/research/projects/{id}/reviews/{rid}/reflux", projectId, reviewId).session(session))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("REFLUX_WIKI_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("知识库")));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT reflux_state FROM research_review WHERE id = ?", String.class, reviewId))
                .isEqualTo("PENDING");
        assertThatCount("wiki_entry", projectId, 0);
        // 复盘本体不受回流失败影响（不阻断：仍可读可改）
        mockMvc.perform(get("/api/research/projects/{id}/reviews", projectId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].refluxState").value("PENDING"))
                .andExpect(jsonPath("$[0].narrative").value("叙述"));

        // 恢复：wiki 写恢复后重试回流成功（可重试语义）
        Mockito.reset(wikiEntryRepository);
        mockMvc.perform(post("/api/research/projects/{id}/reviews/{rid}/reflux", projectId, reviewId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refluxState").value("REFLOWN"))
                .andExpect(jsonPath("$.wikiEntryId").isNumber());
        assertThatCount("wiki_entry", projectId, 1);

        // 修正行不存在/越项目 → 404（不泄漏存在性）
        mockMvc.perform(put("/api/research/projects/{id}/reviews/{rid}", projectId, 99999999L).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":{\"q1\":\"x\"}}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(post("/api/research/projects/{id}/reviews/{rid}/reflux", projectId, 99999999L).session(session))
                .andExpect(status().isNotFound());
    }

    private void assertThatCount(String table, long projectId, int expected) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE project_id = ?", Integer.class, projectId);
        org.assertj.core.api.Assertions.assertThat(count).isEqualTo(expected);
    }

    /** 三段式注册：发码（邮件桩取码）→ 携码注册；邮箱由用户名派生保证类内唯一。 */
    private void register(String username, String password) throws Exception {
        String email = username + "@test.local";
        mockMvc.perform(post("/api/auth/register-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password
                                + "\",\"email\":\"" + email + "\"}"))
                .andExpect(status().isOk());
        String code = TestCodes.extractSixDigits(mailStub.sent.get(mailStub.sent.size() - 1).text());
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password
                                + "\",\"email\":\"" + email + "\",\"code\":\"" + code + "\"}"))
                .andExpect(status().isCreated());
    }

    private void approve(String username) {
        var user = userRepository.findByUsername(username).orElseThrow();
        userRepository.save(user.approve());
    }

    private MockHttpSession login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }
}
