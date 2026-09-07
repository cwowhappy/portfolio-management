package com.portfolio.invest.agui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.domain.user.UserRepository;
import com.portfolio.invest.support.PostgresTestSupport;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Flux;

/**
 * /agui/run 真实 MCP 工具权限审批端到端（mcp-hitl 验收项，features/mcp-hitl/02-design §二）。
 *
 * <p>与 AguiInterruptIntegrationTest 的差别：不用 WriteGateTool 复刻 ASK 语义，而是内嵌真实
 * MCP server（官方 SDK streamable HTTP，classpath 已有 mcp-core 0.17.2）提供 write_note
 * （不标 readOnlyHint → 写，触发审批）与 read_note（readOnlyHint=true → 只读放行），
 * seed McpProvider/McpEndpoint/McpUserConfig 后由生产 UserToolkitFactory 经 McpClientPool
 * 真实 HTTP 装配，验证 FR-1/2/4/6 全链路。
 *
 * <p>技术方案：MockMvc 无真实 HTTP 栈，而 MCP 调用需真 socket（agent 线程 → HTTP → 本
 * 测试 JVM），故 MCP servlet 单独起随机端口内嵌 Tomcat；/agui/run 断言沿用 MockMvc 异步模式。
 */
@SpringBootTest(properties = {
        "DEEPSEEK_API_KEY=test-dummy-key",
        "invest.mcp.harness.workspace=build/mcp-hitl-test/workspace",
        "invest.mcp.harness.state-root=build/mcp-hitl-test/state"})
@AutoConfigureMockMvc
class McpHitlIntegrationTest extends PostgresTestSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 静态单例：TestBean 工厂先于用例体执行，脚本在用例内下发（同 AguiInterruptIntegrationTest）。 */
    private static final ScriptedModel MODEL = new ScriptedModel();

    private static final String PROVIDER_CODE = "hitl-test-mcp";
    private static final Path NOTE_DIR = Path.of("build/mcp-hitl-test/notes");

    private static Tomcat TOMCAT;
    private static McpSyncServer MCP_SERVER;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository userRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    /** bean 名按字段名推断为 investModel，替换 AgentConfig#investModel。 */
    @TestBean(methodName = "scriptedModel")
    Model investModel;

    static Model scriptedModel() {
        return MODEL;
    }

    @BeforeAll
    static void startMcpServer() throws Exception {
        Files.createDirectories(NOTE_DIR);
        Files.createDirectories(Path.of("build/mcp-hitl-test/workspace"));
        Files.createDirectories(Path.of("build/mcp-hitl-test/state"));

        HttpServletStreamableServerTransportProvider transport =
                HttpServletStreamableServerTransportProvider.builder()
                        .mcpEndpoint("/mcp")
                        .jsonMapper(new JacksonMcpJsonMapper(MAPPER))
                        .build();
        McpSchema.JsonSchema contentSchema = new McpSchema.JsonSchema(
                "object",
                Map.of("content", Map.of("type", "string", "description", "写入内容")),
                List.of("content"),
                null, null, null);
        // 不标 annotations → 缺省视为写（对齐 FR-1 的「缺省」分支）
        McpSchema.Tool writeNote = McpSchema.Tool.builder()
                .name("write_note")
                .description("写入笔记（HITL 验证用写工具，不标 readOnlyHint）")
                .inputSchema(contentSchema)
                .build();
        McpSchema.Tool readNote = McpSchema.Tool.builder()
                .name("read_note")
                .description("读取笔记（HITL 验证用只读工具，readOnlyHint=true）")
                .inputSchema(new McpSchema.JsonSchema("object", Map.of(), null, null, null, null))
                .annotations(new McpSchema.ToolAnnotations(null, true, null, null, null, null))
                .build();
        MCP_SERVER = McpServer.sync(transport)
                .serverInfo("hitl-test-mcp", "1.0")
                .tool(writeNote, (exchange, args) -> {
                    // BiFunction#apply 不声明受检异常（javap 核对 0.17.2），IOException 只能包装抛出
                    try {
                        Path file = NOTE_DIR.resolve(String.valueOf(args.getOrDefault("file", "note.md")));
                        Files.writeString(file, String.valueOf(args.get("content")));
                        return new McpSchema.CallToolResult("written:" + file, false);
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                })
                .tool(readNote, (exchange, args) -> new McpSchema.CallToolResult("note-content", false))
                .build();

        TOMCAT = new Tomcat();
        TOMCAT.setBaseDir(Files.createTempDirectory("mcp-hitl-tomcat").toString());
        TOMCAT.setPort(0); // 随机端口，避免与本机服务冲突
        TOMCAT.getConnector(); // 触发 connector 创建
        Context ctx = TOMCAT.addContext("", null);
        // SDK servlet 经 getReader() 读请求体且不 setCharacterEncoding；裸 Tomcat 默认按
        // ISO-8859-1 解码会毁掉中文参数（Spring Boot 内嵌容器默认 UTF-8，手动装配须自设）
        ctx.setRequestCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
        Tomcat.addServlet(ctx, "mcp", transport).addMapping("/mcp");
        TOMCAT.start();
    }

    @AfterAll
    static void stopMcpServer() throws Exception {
        if (MCP_SERVER != null) MCP_SERVER.closeGracefully();
        if (TOMCAT != null) TOMCAT.stop();
    }

    @DisplayName("真实 MCP 写工具（未标 readOnlyHint）触发权限中断且不执行")
    @Test
    void givenRealMcpWriteTool_whenRun_thenPermissionConfirmInterruptEmitted() throws Exception {
        MockHttpSession session = registerApproveAndLogin("mcp_hitl_alice");
        seedMcpConfig("mcp_hitl_alice");
        Path note = NOTE_DIR.resolve("alice.md");
        Files.deleteIfExists(note);
        MODEL.script(List.of(toolCall("call_m_1", "write_note",
                Map.of("file", "alice.md", "content", "验证写入"))));

        String body = run(session, runRequest("interrupt-" + UUID.randomUUID(), "r-1", "写一条笔记"));

        JsonNode finished = lastEventOfType(body, "RUN_FINISHED");
        assertThat(finished.path("outcome").path("type").asText()).isEqualTo("interrupt");
        JsonNode interrupt = finished.path("outcome").path("interrupts").path(0);
        assertThat(interrupt.path("reason").asText()).isEqualTo("tool_call");
        assertThat(interrupt.path("toolCallId").asText()).isEqualTo("call_m_1");
        assertThat(interrupt.path("metadata").path("agentscope.interruptKind").asText())
                .isEqualTo("permission_confirm");
        assertThat(interrupt.path("metadata").path("toolName").asText()).isEqualTo("write_note");
        assertThat(interrupt.path("id").asText()).endsWith(":call_m_1");
        assertThat(interrupt.path("responseSchema").path("properties").has("approved")).isTrue();

        // 中断即停：写工具未执行（文件未落盘）、无工具结果事件
        assertThat(Files.exists(note)).as("中断即停：写工具未执行").isFalse();
        assertThat(eventOfType(body, "TOOL_CALL_RESULT")).isNull();
    }

    @DisplayName("批准后真实 MCP 写工具执行且 Agent 续跑")
    @Test
    void givenInterrupt_whenResumeApproved_thenRealMcpToolExecutesAndRunContinues() throws Exception {
        MockHttpSession session = registerApproveAndLogin("mcp_hitl_bob");
        seedMcpConfig("mcp_hitl_bob");
        Path note = NOTE_DIR.resolve("bob.md");
        Files.deleteIfExists(note);
        MODEL.script(
                List.of(toolCall("call_m_1", "write_note",
                        Map.of("file", "bob.md", "content", "已批准内容"))),
                List.of(TextBlock.builder().text("已写入笔记。").build()));
        String threadId = "interrupt-" + UUID.randomUUID();

        String first = run(session, runRequest(threadId, "r-1", "写一条笔记"));
        String interruptId = lastEventOfType(first, "RUN_FINISHED")
                .path("outcome").path("interrupts").path(0).path("id").asText();

        String second = run(session, resumeRequest(threadId, "r-2", interruptId, true));

        assertThat(Files.exists(note)).as("批准后工具真实执行（文件落盘）").isTrue();
        assertThat(Files.readString(note)).isEqualTo("已批准内容");
        assertThat(second).contains("已写入笔记。");
        assertThat(lastEventOfType(second, "RUN_FINISHED").path("outcome").isMissingNode()).isTrue();
        assertThat(eventOfType(second, "RUN_ERROR")).isNull();
    }

    @DisplayName("拒绝后真实 MCP 写工具不执行且 Agent 续跑")
    @Test
    void givenInterrupt_whenResumeDenied_thenRealMcpToolSkippedAndRunContinues() throws Exception {
        MockHttpSession session = registerApproveAndLogin("mcp_hitl_carol");
        seedMcpConfig("mcp_hitl_carol");
        Path note = NOTE_DIR.resolve("carol.md");
        Files.deleteIfExists(note);
        MODEL.script(
                List.of(toolCall("call_m_1", "write_note",
                        Map.of("file", "carol.md", "content", "不应写入"))),
                List.of(TextBlock.builder().text("用户拒绝，未写入。").build()));
        String threadId = "interrupt-" + UUID.randomUUID();

        String first = run(session, runRequest(threadId, "r-1", "写一条笔记"));
        String interruptId = lastEventOfType(first, "RUN_FINISHED")
                .path("outcome").path("interrupts").path(0).path("id").asText();

        String second = run(session, resumeRequest(threadId, "r-2", interruptId, false));

        assertThat(Files.exists(note)).as("拒绝后工具不执行").isFalse();
        assertThat(second).contains("用户拒绝，未写入。");
        assertThat(lastEventOfType(second, "RUN_FINISHED").path("outcome").isMissingNode()).isTrue();
        assertThat(eventOfType(second, "RUN_ERROR")).isNull();
    }

    @DisplayName("readOnlyHint=true 的真实 MCP 工具不弹审批直接执行")
    @Test
    void givenRealMcpReadTool_whenRun_thenNoInterruptAndDirectExecution() throws Exception {
        MockHttpSession session = registerApproveAndLogin("mcp_hitl_dave");
        seedMcpConfig("mcp_hitl_dave");
        MODEL.script(
                List.of(toolCall("call_r_1", "read_note", Map.of())),
                List.of(TextBlock.builder().text("笔记读取完成。").build()));

        String body = run(session, runRequest("interrupt-" + UUID.randomUUID(), "r-1", "读一条笔记"));

        assertThat(lastEventOfType(body, "RUN_FINISHED").path("outcome").isMissingNode()).isTrue();
        assertThat(body).contains("note-content");
        assertThat(body).contains("笔记读取完成。");
        assertThat(eventOfType(body, "RUN_ERROR")).isNull();
    }

    // ———— seed 与内嵌 server ————

    /** seed provider(NONE)/endpoint(内嵌 Tomcat)/用户配置（disabled_tools 空）。幂等：先清旧。 */
    private void seedMcpConfig(String username) {
        int port = TOMCAT.getConnector().getLocalPort();
        cleanupSeededMcpConfig();
        jdbcTemplate.update(
                "INSERT INTO mcp_provider (code, name, auth_type, enabled, remark) VALUES (?, ?, 'NONE', TRUE, 'HITL 验证内嵌 server')",
                PROVIDER_CODE, "HITL 测试数据源");
        Long providerId = jdbcTemplate.queryForObject(
                "SELECT id FROM mcp_provider WHERE code = ?", Long.class, PROVIDER_CODE);
        Long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM app_user WHERE username = ?", Long.class, username);
        jdbcTemplate.update(
                "INSERT INTO mcp_endpoint (provider_id, domain, name, url) VALUES (?, NULL, 'HITL 内嵌', ?)",
                providerId, "http://localhost:" + port + "/mcp");
        jdbcTemplate.update(
                "INSERT INTO mcp_user_config (user_id, provider_id, enabled, disabled_tools) VALUES (?, ?, TRUE, '[]'::jsonb)",
                userId, providerId);
    }

    /**
     * 清掉本类 seed 的 provider/endpoint/用户配置。PostgresTestSupport 容器为 JVM 级单例、
     * 本类非事务，行会 commit 留存——若不清理，后续同 JVM 运行的计数类断言（如
     * McpConfigRepositoryImplTest 的「迁移 seed 了 3 个启用的 provider」）会看到第 4 个 provider。
     */
    @AfterEach
    void cleanupSeededMcpConfig() {
        jdbcTemplate.update(
                "DELETE FROM mcp_user_config WHERE provider_id IN (SELECT id FROM mcp_provider WHERE code = ?)",
                PROVIDER_CODE);
        jdbcTemplate.update(
                "DELETE FROM mcp_endpoint WHERE provider_id IN (SELECT id FROM mcp_provider WHERE code = ?)",
                PROVIDER_CODE);
        jdbcTemplate.update("DELETE FROM mcp_provider WHERE code = ?", PROVIDER_CODE);
    }

    // ———— 请求与 SSE 解析（与 AguiInterruptIntegrationTest 同构） ————

    private String run(MockHttpSession session, String json) throws Exception {
        MvcResult result = mockMvc.perform(post("/agui/run")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(request().asyncStarted())
                .andReturn();
        result.getAsyncResult(30_000);
        mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk());
        return result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
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

    /** resume 轮请求：messages 留空（服务端 server-side-memory 持有会话历史）。 */
    private static String resumeRequest(String threadId, String runId, String interruptId, boolean approved) {
        return """
                {"threadId":"%s","runId":"%s","state":{},"messages":[],"tools":[],"context":[],"forwardedProps":{},"resume":[{"interruptId":"%s","status":"resolved","payload":{"approved":%s}}]}
                """
                .formatted(threadId, runId, interruptId, approved);
    }

    /** input 与 content 必须同时提供（ToolExecutor 参数校验读 content，见 AguiInterruptIntegrationTest 注释）。 */
    private static ToolUseBlock toolCall(String callId, String name, Map<String, Object> input) {
        return new ToolUseBlock(callId, name, input, MAPPER.valueToTree(input).toString(), Map.of());
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
        return (MockHttpSession) login.getRequest().getSession(false);
    }

    // ———— 测试替身（与 AguiInterruptIntegrationTest 同构） ————

    /** 脚本化假 Model：按队列顺序返回预设内容块，耗尽后兜底纯文本。 */
    static class ScriptedModel implements Model {
        private final Queue<List<ContentBlock>> replies = new ConcurrentLinkedQueue<>();
        private final AtomicLong seq = new AtomicLong();

        @SafeVarargs
        final void script(List<ContentBlock>... scripted) {
            replies.clear();
            replies.addAll(Arrays.asList(scripted));
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            // harness 记忆归档/整合以 tools=null 直呼 Model，会偷走脚本化回复：识别后返回固定文本
            if (tools == null || isMemoryMaintenance(messages)) {
                return textResponse("（无新增记忆）");
            }
            List<ContentBlock> content = replies.poll();
            if (content == null) {
                content = List.of(TextBlock.builder().text("脚本耗尽，兜底文本。").build());
            }
            boolean hasToolCall = content.stream().anyMatch(b -> b instanceof ToolUseBlock);
            return Flux.just(ChatResponse.builder()
                    .id("scripted-" + seq.incrementAndGet())
                    .content(content)
                    .usage(new ChatUsage(10, 5, 0.01))
                    .finishReason(hasToolCall ? "tool_calls" : "stop")
                    .build());
        }

        private static boolean isMemoryMaintenance(List<Msg> messages) {
            return messages.stream().anyMatch(m -> {
                String t = m.getTextContent();
                return t != null && t.contains("Extract NEW memories");
            });
        }

        private Flux<ChatResponse> textResponse(String text) {
            return Flux.just(ChatResponse.builder()
                    .id("scripted-mem-" + seq.incrementAndGet())
                    .content(List.of(TextBlock.builder().text(text).build()))
                    .usage(new ChatUsage(1, 1, 0.0))
                    .finishReason("stop")
                    .build());
        }

        @Override
        public String getModelName() {
            return "scripted-model";
        }
    }
}
