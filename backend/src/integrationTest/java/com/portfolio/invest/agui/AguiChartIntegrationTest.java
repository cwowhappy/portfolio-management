package com.portfolio.invest.agui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.application.market.OrchestratingMarketDataService;
import com.portfolio.invest.domain.market.KlineBar;
import com.portfolio.invest.domain.user.UserRepository;
import com.portfolio.invest.support.PostgresTestSupport;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.Model;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 双通道端到端守护（05 §六.4「最值得钉住的行为」）：get_kline 走生产 InvestTools →
 * SSE {@code TOOL_CALL_RESULT.content} 含 ChartSpec 全量；stateStore 落盘的 TOOL output 只有摘要。
 *
 * <p>技术方案：
 * <ul>
 *   <li>假 Model：{@code @TestBean} 替换 bean 名 investModel（同 AguiStream/McpHitl），脚本化
 *   两轮回复——第一轮 tool_use get_kline，第二轮收尾文本；记忆归档守卫见 {@link AguiChartModels}。</li>
 *   <li>隔离网络：mock 具体编排 bean orchestratingMarketDataService（@Primary 缓存装饰器保持真实，
 *   链路 InvestTools → 装饰器 → mock；按接口类型 mock 会与 @Primary 装饰器产生候选歧义，
 *   且装饰器构造参数要求具体类型），kline 桩返回两根 bar（与单测/前端 fixtures 同款值）。</li>
 *   <li>SSE 断言：MockMvc 异步模式读取完整事件流（同 AguiStreamIntegrationTest）；
 *   state 断言：默认 state-root .agentscope/state（相对 backend/ 工作目录，已 gitignore），
 *   按 threadId 定位 &lt;stateRoot&gt;/&lt;userId&gt;/&lt;threadId&gt;/agent_state.json。</li>
 * </ul>
 */
@SpringBootTest(properties = "DEEPSEEK_API_KEY=test-dummy-key")
@AutoConfigureMockMvc
class AguiChartIntegrationTest extends PostgresTestSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper(); // 断言解析用（与被测序列化无关）

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository userRepository;

    /** bean 名按字段名推断为 investModel，替换 AgentConfig#investModel。 */
    @TestBean(methodName = "scriptedModel")
    Model investModel;

    static Model scriptedModel() {
        return AguiChartModels.MODEL;
    }

    @MockitoBean
    OrchestratingMarketDataService orchestratingMarketDataService;

    @DisplayName("get_kline双通道：SSE携带全量ChartSpec，state只落摘要")
    @Test
    void givenScriptedKlineToolCall_whenRunAgent_thenSseCarriesFullChartSpecAndStateKeepsSummaryOnly() throws Exception {
        MockHttpSession session = registerApproveAndLogin("agui_chart_alice");
        when(orchestratingMarketDataService.kline(any(), any(), anyInt())).thenReturn(List.of(
                new KlineBar("2026-09-09", 1800.0, 1850.0, 1860.0, 1790.0, 120_000, 0, 0),
                new KlineBar("2026-09-10", 1850.0, 1840.0, 1870.0, 1830.0, 98_000, 0, 0)));
        AguiChartModels.MODEL.script(
                // @ToolParam.required 默认 true：工具 schema 要求 code/period/limit 全给（参数校验在执行前）
                List.of(AguiChartModels.toolCall("call_k_1", "get_kline",
                        Map.of("code", "600519", "period", "day", "limit", 120))),
                List.of(TextBlock.builder().text("K线已展示。").build()));

        // threadId 唯一化：state walk 按其过滤，避免命中同 JVM 其他用例/本地 dev 的落盘
        String threadId = "chart-" + UUID.randomUUID();
        String body = run(session, runRequest(threadId, "r-1", "贵州茅台最近走势"));

        // ① SSE：TOOL_CALL_RESULT.content = emit 的 ChartSpec 全量（candlestick、specVersion=1）
        JsonNode toolResult = lastEventOfType(body, "TOOL_CALL_RESULT");
        assertThat(toolResult.path("toolCallId").asText()).isEqualTo("call_k_1");
        assertThat(toolResult.path("content").asText())
                .contains("\"type\":\"candlestick\"")
                .contains("\"specVersion\":1");
        // ② 摘要不出现在 SSE（emit 过的工具，返回值 delta 被 skipSet 跳过）
        assertThat(body).doesNotContain("日K 2根");
        assertThat(eventOfType(body, "RUN_ERROR")).isNull();

        // ③ stateStore：TOOL 消息 output 只有摘要，全量 ChartSpec 不得进 LLM 上下文/state
        Path stateFile;
        try (Stream<Path> s = Files.walk(Path.of(".agentscope", "state"))) {
            stateFile = s.filter(p -> p.getFileName().toString().equals("agent_state.json")
                            && p.toString().contains(threadId))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("未找到 threadId=" + threadId + " 的 agent_state.json"));
        }
        JsonNode state = MAPPER.readTree(Files.readString(stateFile));
        String toolOutput = state.path("context").findValuesAsText("text").stream()
                .filter(t -> t.contains("日K 2根"))
                .findFirst()
                .orElse("");
        assertThat(toolOutput).as("摘要已落盘进 LLM 上下文").isNotEmpty();
        assertThat(Files.readString(stateFile)).as("全量 ChartSpec 不得进 state").doesNotContain("specVersion");
    }

    // ———— 请求与 SSE 解析（与 McpHitlIntegrationTest 同构） ————

    private String run(MockHttpSession session, String json) throws Exception {
        MvcResult result = mockMvc.perform(post("/agui/run")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(request().asyncStarted())
                .andReturn();
        // 容器启动 + agent 两轮推理，放宽到 60s
        result.getAsyncResult(60_000);
        mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk());
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static List<JsonNode> parseEvents(String body) throws Exception {
        List<JsonNode> events = new ArrayList<>();
        for (String line : body.split("\n")) {
            if (line.startsWith("data: ")) {
                events.add(MAPPER.readTree(line.substring("data: ".length())));
            }
        }
        return events;
    }

    private static JsonNode eventOfType(String body, String type) throws Exception {
        JsonNode found = null;
        for (JsonNode e : parseEvents(body)) {
            if (type.equals(e.path("type").asText())) found = e;
        }
        return found;
    }

    private static JsonNode lastEventOfType(String body, String type) throws Exception {
        JsonNode last = eventOfType(body, type);
        assertThat(last).as("事件流应包含 %s", type).isNotNull();
        return last;
    }

    private static String runRequest(String threadId, String runId, String text) {
        return """
                {"threadId":"%s","runId":"%s","state":{},"messages":[{"id":"%s","role":"user","content":"%s"}],"tools":[],"context":[],"forwardedProps":{}}
                """
                .formatted(threadId, runId, UUID.randomUUID(), text);
    }

    private MockHttpSession registerApproveAndLogin(String username) throws Exception {
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
