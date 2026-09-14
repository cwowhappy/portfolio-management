package com.portfolio.invest.eval;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.portfolio.invest.domain.user.UserRepository;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;

/**
 * /agui/run 的 MockMvc 驱动器（评估版，形态对齐 integrationTest 的 AguiTestSupport：三段式
 * asyncStarted → getAsyncResult → asyncDispatch）。差异：超时 120s（真实 DeepSeek 延迟）；
 * 请求体经 Jackson 构造（用户文本含引号/换行也稳）；每题独立注册-审批-登录用户 + 独立 threadId。
 */
public final class AguiDriver {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MockMvc mockMvc;
    private final UserRepository userRepository;

    public AguiDriver(WebApplicationContext context, UserRepository userRepository) {
        this.mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .webAppContextSetup(context).build();
        this.userRepository = userRepository;
    }

    /** 单轮 SSE 事件流原文 + 解析后事件；失败（超时/流内异常）时 error 非空、rawBody 尽量保留。 */
    public record SseTurn(int index, String requestJson, String rawBody, List<JsonNode> events,
                          long durationMs, String error) {

        public boolean failed() {
            return error != null;
        }
    }

    /** 注册→审批→登录，返回登录会话（MockMvc 不回放 JSESSIONID，须显式传 MockHttpSession）。 */
    public MockHttpSession registerApproveAndLogin(String username) throws Exception {
        String password = "eval-pass-123456";
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(java.util.Map.of(
                                "username", username, "password", password))))
                .andExpect(status().isCreated());
        var user = userRepository.findByUsername(username).orElseThrow();
        userRepository.save(user.approve());
        var login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(java.util.Map.of(
                                "username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) login.getRequest().getSession(false);
    }

    /** AG-UI RunAgentInput 线格式（同 AguiTestSupport.runRequest，Jackson 构造防注入）。 */
    public String runRequest(String threadId, String runId, String text) {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("threadId", threadId);
            root.put("runId", runId);
            root.putObject("state");
            ArrayNode messages = root.putArray("messages");
            ObjectNode message = messages.addObject();
            message.put("id", "m-" + UUID.randomUUID());
            message.put("role", "user");
            message.put("content", text);
            root.putArray("tools");
            root.putArray("context");
            root.putObject("forwardedProps");
            return MAPPER.writeValueAsString(root);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("构造 RunAgentInput 失败", e);
        }
    }

    /** 三段式跑一轮；超时毫秒数可配（默认 120s，真实 LLM 延迟）。 */
    public SseTurn run(MockHttpSession session, String threadId, String runId, String text,
                       int turnIndex, long timeoutMs) {
        String requestJson = runRequest(threadId, runId, text);
        long start = System.currentTimeMillis();
        MvcResult result = null;
        try {
            result = mockMvc.perform(post("/agui/run")
                            .session(session)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestJson))
                    .andExpect(request().asyncStarted())
                    .andReturn();
            result.getAsyncResult(timeoutMs);
            mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk());
            String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            return new SseTurn(turnIndex, requestJson, body, parseEvents(body),
                    System.currentTimeMillis() - start, null);
        } catch (Throwable t) {
            // 失败也带回已写出的响应体片段（可回放）；异常摘要进报告
            String partial = "";
            if (result != null) {
                try {
                    partial = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
                } catch (Exception ignored) {
                    partial = ""; // UTF-8 必然可用，此分支仅为满足受检异常语法
                }
            }
            List<JsonNode> partialEvents;
            try {
                partialEvents = parseEvents(partial);
            } catch (Exception e) {
                partialEvents = List.of();
            }
            return new SseTurn(turnIndex, requestJson, partial, partialEvents,
                    System.currentTimeMillis() - start,
                    t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /** 解析 SSE data 行（忽略 : keep-alive 注释行）。 */
    public static List<JsonNode> parseEvents(String body) throws Exception {
        List<JsonNode> events = new ArrayList<>();
        for (String line : body.split("\n")) {
            if (line.startsWith("data: ")) {
                events.add(MAPPER.readTree(line.substring("data: ".length())));
            }
        }
        return events;
    }
}
