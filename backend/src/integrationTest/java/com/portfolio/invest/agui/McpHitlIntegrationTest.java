package com.portfolio.invest.agui;

import static com.portfolio.invest.support.AguiTestSupport.eventOfType;
import static com.portfolio.invest.support.AguiTestSupport.lastEventOfType;
import static com.portfolio.invest.support.AguiTestSupport.registerApproveAndLogin;
import static com.portfolio.invest.support.AguiTestSupport.resumeRequest;
import static com.portfolio.invest.support.AguiTestSupport.run;
import static com.portfolio.invest.support.AguiTestSupport.runRequest;
import static org.assertj.core.api.Assertions.assertThat;

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
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;
import reactor.core.publisher.Flux;

/**
 * /agui/run 真实 MCP 工具权限审批端到端（mcp-hitl 验收项，features/mcp-hitl/02-design §二）。
 *
 * <p>与 AguiInterruptIntegrationTest 的差别：不用 WriteGateTool 复刻 ASK 语义，而是内嵌真实
 * MCP server（官方 SDK streamable HTTP，classpath 已有 mcp-core 0.17.2）提供 write_note
 * （不标 readOnlyHint → 写，触发审批）与 read_note（readOnlyHint=true → 只读放行），
 * seed McpProvider/McpEndpoint/McpUserConfig 后由生产 UserToolkitFactory 经 McpClientPool
 * 真实 HTTP 装配，验证 FR-1/2/4/6 全链路。另以只读捕获 Filter 验证 HEADER/BEARER 鉴权
 * header（McpClientPool 装配分支）真实到达内嵌 server。
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

    /** /mcp 请求 header 快照队列（HeaderCaptureFilter 只读捕获；用例内 clear 后 run 再断言）。 */
    private static final ConcurrentLinkedQueue<Map<String, String>> CAPTURED_HEADERS = new ConcurrentLinkedQueue<>();

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
        // HEADER/BEARER 鉴权用例观测点：/mcp 挂只读捕获 Filter。FilterDef/FilterMap 程序化注册
        // （ServletContext#addFilter 的内部实现同款），须在 start() 前完成
        FilterDef captureDef = new FilterDef();
        captureDef.setFilterName("header-capture");
        captureDef.setFilter(new HeaderCaptureFilter());
        ctx.addFilterDef(captureDef);
        FilterMap captureMap = new FilterMap();
        captureMap.setFilterName("header-capture");
        captureMap.addURLPattern("/mcp");
        ctx.addFilterMap(captureMap);
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
        MockHttpSession session = registerApproveAndLogin(mockMvc, userRepository, "mcp_hitl_alice");
        seedMcpConfig("mcp_hitl_alice");
        Path note = NOTE_DIR.resolve("alice.md");
        Files.deleteIfExists(note);
        MODEL.script(List.of(toolCall("call_m_1", "write_note",
                Map.of("file", "alice.md", "content", "验证写入"))));

        String body = run(mockMvc, session, runRequest("interrupt-" + UUID.randomUUID(), "r-1", "写一条笔记"));

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

        // #25：AG-UI 线上不得出现 null 字段（前端 @ag-ui/core InterruptSchema 只认缺省/字符串，
        // "expiresAt":null 会导致整条 RUN_FINISHED 被浏览器端 zod 拒收、审批卡片不渲染）
        assertThat(body).doesNotContain("\"expiresAt\"");

        // 中断即停：写工具未执行（文件未落盘）、无工具结果事件
        assertThat(Files.exists(note)).as("中断即停：写工具未执行").isFalse();
        assertThat(eventOfType(body, "TOOL_CALL_RESULT")).isNull();
    }

    @DisplayName("批准后真实 MCP 写工具执行且 Agent 续跑")
    @Test
    void givenInterrupt_whenResumeApproved_thenRealMcpToolExecutesAndRunContinues() throws Exception {
        MockHttpSession session = registerApproveAndLogin(mockMvc, userRepository, "mcp_hitl_bob");
        seedMcpConfig("mcp_hitl_bob");
        Path note = NOTE_DIR.resolve("bob.md");
        Files.deleteIfExists(note);
        MODEL.script(
                List.of(toolCall("call_m_1", "write_note",
                        Map.of("file", "bob.md", "content", "已批准内容"))),
                List.of(TextBlock.builder().text("已写入笔记。").build()));
        String threadId = "interrupt-" + UUID.randomUUID();

        String first = run(mockMvc, session, runRequest(threadId, "r-1", "写一条笔记"));
        String interruptId = lastEventOfType(first, "RUN_FINISHED")
                .path("outcome").path("interrupts").path(0).path("id").asText();

        String second = run(mockMvc, session, resumeRequest(threadId, "r-2", interruptId, true));

        assertThat(Files.exists(note)).as("批准后工具真实执行（文件落盘）").isTrue();
        assertThat(Files.readString(note)).isEqualTo("已批准内容");
        assertThat(second).contains("已写入笔记。");
        assertThat(lastEventOfType(second, "RUN_FINISHED").path("outcome").isMissingNode()).isTrue();
        assertThat(eventOfType(second, "RUN_ERROR")).isNull();
    }

    @DisplayName("拒绝后真实 MCP 写工具不执行且 Agent 续跑")
    @Test
    void givenInterrupt_whenResumeDenied_thenRealMcpToolSkippedAndRunContinues() throws Exception {
        MockHttpSession session = registerApproveAndLogin(mockMvc, userRepository, "mcp_hitl_carol");
        seedMcpConfig("mcp_hitl_carol");
        Path note = NOTE_DIR.resolve("carol.md");
        Files.deleteIfExists(note);
        MODEL.script(
                List.of(toolCall("call_m_1", "write_note",
                        Map.of("file", "carol.md", "content", "不应写入"))),
                List.of(TextBlock.builder().text("用户拒绝，未写入。").build()));
        String threadId = "interrupt-" + UUID.randomUUID();

        String first = run(mockMvc, session, runRequest(threadId, "r-1", "写一条笔记"));
        String interruptId = lastEventOfType(first, "RUN_FINISHED")
                .path("outcome").path("interrupts").path(0).path("id").asText();

        String second = run(mockMvc, session, resumeRequest(threadId, "r-2", interruptId, false));

        assertThat(Files.exists(note)).as("拒绝后工具不执行").isFalse();
        assertThat(second).contains("用户拒绝，未写入。");
        assertThat(lastEventOfType(second, "RUN_FINISHED").path("outcome").isMissingNode()).isTrue();
        assertThat(eventOfType(second, "RUN_ERROR")).isNull();
    }

    @DisplayName("readOnlyHint=true 的真实 MCP 工具不弹审批直接执行")
    @Test
    void givenRealMcpReadTool_whenRun_thenNoInterruptAndDirectExecution() throws Exception {
        MockHttpSession session = registerApproveAndLogin(mockMvc, userRepository, "mcp_hitl_dave");
        seedMcpConfig("mcp_hitl_dave");
        MODEL.script(
                List.of(toolCall("call_r_1", "read_note", Map.of())),
                List.of(TextBlock.builder().text("笔记读取完成。").build()));

        String body = run(mockMvc, session, runRequest("interrupt-" + UUID.randomUUID(), "r-1", "读一条笔记"));

        assertThat(lastEventOfType(body, "RUN_FINISHED").path("outcome").isMissingNode()).isTrue();
        assertThat(body).contains("note-content");
        assertThat(body).contains("笔记读取完成。");
        assertThat(eventOfType(body, "RUN_ERROR")).isNull();
    }

    @DisplayName("HEADER 鉴权端点的工具调用请求携带自定义鉴权 header 到达 server")
    @Test
    void givenHeaderAuthEndpoint_whenCallTool_thenRequestCarriesCustomHeader() throws Exception {
        MockHttpSession session = registerApproveAndLogin(mockMvc, userRepository, "mcp_hitl_eve");
        seedMcpConfig("mcp_hitl_eve", "HEADER", "X-Test-Token", "hitl-secret-123");
        MODEL.script(
                List.of(toolCall("call_r_1", "read_note", Map.of())),
                List.of(TextBlock.builder().text("笔记读取完成。").build()));
        CAPTURED_HEADERS.clear();

        String body = run(mockMvc, session, runRequest("interrupt-" + UUID.randomUUID(), "r-1", "读一条笔记"));

        // 工具真实执行 + Agent 续跑（同 read-tool 用例，只读不弹审批）
        assertThat(lastEventOfType(body, "RUN_FINISHED").path("outcome").isMissingNode()).isTrue();
        assertThat(body).contains("note-content");
        assertThat(body).contains("笔记读取完成。");
        assertThat(eventOfType(body, "RUN_ERROR")).isNull();
        // run() 已阻塞到流结束，callTool 响应含 note-content 即其请求已过 Filter，快照已就绪
        List<Map<String, String>> captured = List.copyOf(CAPTURED_HEADERS);
        assertThat(captured).as("run 期间到达 server 的 /mcp 请求（initialize/listTools/callTool）").isNotEmpty();
        assertThat(captured).allSatisfy(h -> assertThat(h.get("X-Test-Token")).isEqualTo("hitl-secret-123"));
    }

    @DisplayName("BEARER 鉴权端点的工具调用请求携带 Authorization Bearer 到达 server")
    @Test
    void givenBearerAuthEndpoint_whenCallTool_thenRequestCarriesAuthorizationBearer() throws Exception {
        MockHttpSession session = registerApproveAndLogin(mockMvc, userRepository, "mcp_hitl_frank");
        seedMcpConfig("mcp_hitl_frank", "BEARER", null, "hitl-bearer-token");
        MODEL.script(
                List.of(toolCall("call_r_1", "read_note", Map.of())),
                List.of(TextBlock.builder().text("笔记读取完成。").build()));
        CAPTURED_HEADERS.clear();

        String body = run(mockMvc, session, runRequest("interrupt-" + UUID.randomUUID(), "r-1", "读一条笔记"));

        // 工具真实执行 + Agent 续跑（同 read-tool 用例，只读不弹审批）
        assertThat(lastEventOfType(body, "RUN_FINISHED").path("outcome").isMissingNode()).isTrue();
        assertThat(body).contains("note-content");
        assertThat(body).contains("笔记读取完成。");
        assertThat(eventOfType(body, "RUN_ERROR")).isNull();
        // run() 已阻塞到流结束，callTool 响应含 note-content 即其请求已过 Filter，快照已就绪
        List<Map<String, String>> captured = List.copyOf(CAPTURED_HEADERS);
        assertThat(captured).as("run 期间到达 server 的 /mcp 请求（initialize/listTools/callTool）").isNotEmpty();
        assertThat(captured).allSatisfy(h -> assertThat(h.get("Authorization")).isEqualTo("Bearer hitl-bearer-token"));
    }

    // ———— seed 与内嵌 server ————

    /** seed provider(NONE)/endpoint(内嵌 Tomcat)/用户配置（disabled_tools 空）。幂等：先清旧。 */
    private void seedMcpConfig(String username) {
        seedMcpConfig(username, "NONE", null, null);
    }

    /**
     * 带鉴权维度的 seed：HEADER 需 authHeader + secret，BEARER 仅 secret。非 NONE 且 secret
     * 空白会被 UserToolkitFactory 跳过（视为未配置），故 authSecret 必须非空。
     */
    private void seedMcpConfig(String username, String authType, String authHeader, String authSecret) {
        int port = TOMCAT.getConnector().getLocalPort();
        cleanupSeededMcpConfig();
        jdbcTemplate.update(
                "INSERT INTO mcp_provider (code, name, auth_type, auth_header, auth_secret_enc, enabled, remark)"
                        + " VALUES (?, ?, ?, ?, ?, TRUE, 'HITL 验证内嵌 server')",
                PROVIDER_CODE, "HITL 测试数据源", authType, authHeader, authSecret);
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

    /** 只读捕获 /mcp 请求 header（大小写不敏感快照）后放行；不碰请求/响应，恒调 chain.doFilter。 */
    static class HeaderCaptureFilter implements Filter {
        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            if (request instanceof HttpServletRequest http) {
                // HTTP header 名大小写不敏感，快照用大小写不敏感 map，断言可按任意大小写取名
                Map<String, String> snapshot = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                var names = http.getHeaderNames();
                while (names.hasMoreElements()) {
                    String name = names.nextElement();
                    snapshot.put(name, http.getHeader(name));
                }
                CAPTURED_HEADERS.add(snapshot);
            }
            chain.doFilter(request, response);
        }
    }

    // ———— 请求体构造（三段式 run/SSE 解析/登录骨架收敛至 testFixtures 的 AguiTestSupport） ————

    /** input 与 content 必须同时提供（ToolExecutor 参数校验读 content，见 AguiInterruptIntegrationTest 注释）。 */
    private static ToolUseBlock toolCall(String callId, String name, Map<String, Object> input) {
        return new ToolUseBlock(callId, name, input, MAPPER.valueToTree(input).toString(), Map.of());
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
