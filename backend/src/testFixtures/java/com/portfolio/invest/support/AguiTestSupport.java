package com.portfolio.invest.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.domain.user.UserRepository;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * AGUI 集成测试共享骨架（testFixtures 共享，供 integrationTest 的 /agui/run 系列测试复用）。
 *
 * <p>沉淀各 AGUI 集成测试类（AguiStream/McpHitl/AguiChart/HarnessAgentStateIsolation/
 * AguiInterrupt）的同构辅助：三段式 MockMvc 异步请求（asyncStarted → getAsyncResult →
 * asyncDispatch）、RunAgentInput/resume 请求体构造、SSE data 行事件解析、注册→审批→登录。
 * 五处私有拷贝收敛为一，防第六份拷贝——新测试类直接静态引入，不再复制粘贴。
 *
 * <p>各类的测试替身（ScriptedModel/FixedReplyModel）、录制与 seed/teardown 语义各异，
 * 仍留在各测试类内，不做抽象。
 */
public final class AguiTestSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AguiTestSupport() {
    }

    /** 三段式跑一轮 /agui/run（asyncStarted → getAsyncResult → asyncDispatch），返回完整 SSE 响应体。 */
    public static String run(MockMvc mockMvc, MockHttpSession session, String json) throws Exception {
        MvcResult result = mockMvc.perform(post("/agui/run")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(request().asyncStarted())
                .andReturn();
        result.getAsyncResult(30_000);
        mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk());
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** 同 {@link #run}，另附 X-Agent-Id 请求头（指定 agent，如传未注册 agent 走流内错误分支）。 */
    public static String runWithAgentHeader(
            MockMvc mockMvc, MockHttpSession session, String agentId, String json) throws Exception {
        MvcResult result = mockMvc.perform(post("/agui/run")
                        .session(session)
                        .header("X-Agent-Id", agentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(request().asyncStarted())
                .andReturn();
        result.getAsyncResult(30_000);
        mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk());
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** 解析 SSE 响应体中全部 data 行事件。 */
    public static List<JsonNode> parseEvents(String body) throws Exception {
        List<JsonNode> events = new ArrayList<>();
        for (String line : body.split("\n")) {
            if (line.startsWith("data: ")) {
                events.add(MAPPER.readTree(line.substring("data: ".length())));
            }
        }
        return events;
    }

    public static JsonNode eventOfType(String body, String type) throws Exception {
        JsonNode found = null;
        for (JsonNode e : parseEvents(body)) {
            if (type.equals(e.path("type").asText())) found = e;
        }
        return found;
    }

    public static JsonNode lastEventOfType(String body, String type) throws Exception {
        JsonNode last = eventOfType(body, type);
        assertThat(last).as("事件流应包含 %s", type).isNotNull();
        return last;
    }

    public static String runRequest(String threadId, String runId, String text) {
        // AG-UI RunAgentInput 线格式（与 CopilotKit HttpAgent 发送的一致）
        return """
                {"threadId":"%s","runId":"%s","state":{},"messages":[{"id":"%s","role":"user","content":"%s"}],"tools":[],"context":[],"forwardedProps":{}}
                """
                .formatted(threadId, runId, UUID.randomUUID(), text);
    }

    /** resume 轮请求：messages 留空（服务端 server-side-memory 持有会话历史）。 */
    public static String resumeRequest(String threadId, String runId, String interruptId, boolean approved) {
        return """
                {"threadId":"%s","runId":"%s","state":{},"messages":[],"tools":[],"context":[],"forwardedProps":{},"resume":[{"interruptId":"%s","status":"resolved","payload":{"approved":%s}}]}
                """
                .formatted(threadId, runId, interruptId, approved);
    }

    public static MockHttpSession registerApproveAndLogin(
            MockMvc mockMvc, UserRepository userRepository, String username) throws Exception {
        String password = "abc12345";
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isCreated());
        var user = userRepository.findByUsername(username).orElseThrow();
        userRepository.save(user.approve());

        var login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        // MockMvc 不会依据 JSESSIONID cookie 重建会话，需显式传递登录产生的 MockHttpSession
        return (MockHttpSession) login.getRequest().getSession(false);
    }
}
