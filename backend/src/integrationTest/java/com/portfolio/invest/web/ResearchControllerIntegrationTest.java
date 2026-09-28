package com.portfolio.invest.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.portfolio.invest.domain.user.UserRepository;
import com.portfolio.invest.support.PostgresTestSupport;
import com.portfolio.invest.support.RecordingMailSender;
import com.portfolio.invest.support.TestCodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
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
