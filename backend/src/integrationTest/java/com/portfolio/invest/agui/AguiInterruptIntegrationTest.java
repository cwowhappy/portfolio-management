package com.portfolio.invest.agui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.agent.InvestTools;
import com.portfolio.invest.agent.McpClientPool;
import com.portfolio.invest.agent.UserToolkitFactory;
import com.portfolio.invest.domain.mcp.McpConfigRepository;
import com.portfolio.invest.domain.user.UserRepository;
import com.portfolio.invest.support.PostgresTestSupport;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import reactor.core.publisher.Mono;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Flux;

/**
 * /agui/run 权限确认 HITL 中断端到端（agentscope 2.0.3 契约，见 docs/technology/research/copilotkit/agui-hitl.md）。
 *
 * <p>验证链路：写工具（readOnly=false）触发权限 ASK → 2.0.3 将 RequireUserConfirmEvent 映射为
 * {@code RUN_FINISHED.outcome.interrupts[]}（reason=tool_call，metadata.agentscope.interruptKind=
 * permission_confirm）→ 同 thread {@code resume[]}（approved=true/false）转 ConfirmResult → 工具执行/拒绝后
 * Agent 续跑。2.0.1 上同一链路为 RAW 事件透传 + resume[] 契约错误（P0 报告），场景 1-3 应跑红。
 *
 * <p>2.0.3 实测行为备注：resume 续跑轮的 SSE 不重放工具事件（TOOL_CALL_START 已在中断轮发过，
 * TOOL_CALL_RESULT 被 {@code AguiStreamContext#hasStartedToolCall} 抑制），工具执行情况以
 * WriteGateTool.executed 标志与服务端持久化 state（tool_result success）佐证。
 *
 * <p>技术方案说明：
 * <ul>
 *   <li>脚本化假 Model（静态单例 + 每用例重编脚本）：TestBean 工厂方法在测试方法体之前执行，
 *       场景参数只能在用例内下发，故用可变脚本队列而非工厂入参。</li>
 *   <li>{@code @Primary} 覆盖 {@code userToolkitFactory}：匿名子类在 {@code super.build(userId)}
 *       基础上追加注册 {@link WriteGateTool}（readOnly=false）。生产工具全部只读（MCP 侧由
 *       UserToolkitFactory 强制 readOnly=true），不注入写工具无法触发权限 ASK；不改动生产代码。</li>
 *   <li>SSE 断言沿用 {@link AguiStreamIntegrationTest} 的 MockMvc 异步模式，事件按 {@code data:} 行
 *       解析为 JSON 后做结构断言（interrupt id 含随机 replyId，需解析后在 resume 请求中回用）。</li>
 *   <li>harness 的 workspace/state-root 重定向到 build/ 下，避免用例状态写入仓库工作区。</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "DEEPSEEK_API_KEY=test-dummy-key",
        "invest.mcp.harness.workspace=build/agui-interrupt-test/workspace",
        "invest.mcp.harness.state-root=build/agui-interrupt-test/state"})
@AutoConfigureMockMvc
@Import(AguiInterruptIntegrationTest.WriteToolkitConfig.class)
class AguiInterruptIntegrationTest extends PostgresTestSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 静态单例：TestBean 工厂先于用例体执行，脚本在用例内下发（见类注释）。 */
    private static final ScriptedModel MODEL = new ScriptedModel();

    /** 记录各 thread 创建的写工具实例，用例内断言「是否真正执行」。 */
    private static final List<WriteGateTool> CREATED_TOOLS = new CopyOnWriteArrayList<>();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository userRepository;

    /** bean 名按字段名推断为 investModel，替换 AgentConfig#investModel。 */
    @TestBean(methodName = "scriptedModel")
    Model investModel;

    static Model scriptedModel() {
        return MODEL;
    }

    /**
     * 以 @Primary 覆盖 @Component 的 userToolkitFactory（@TestBean 工厂方法必须 static，拿不到
     * Spring 依赖，故走 TestConfiguration）。在保留 super.build 完整生产装配（investTools + MCP）
     * 的基础上追加注册写工具，是 HarnessAgentFactory 唯一注入点，@Primary 精确胜出。
     */
    @org.springframework.boot.test.context.TestConfiguration
    static class WriteToolkitConfig {
        @Bean
        @Primary
        UserToolkitFactory writeToolkitFactory(InvestTools investTools,
                McpConfigRepository mcpConfigRepository, McpClientPool mcpClientPool) {
            return new UserToolkitFactory(investTools, mcpConfigRepository, mcpClientPool) {
                @Override
                public Toolkit build(Long userId) {
                    Toolkit toolkit = super.build(userId);
                    WriteGateTool gate = new WriteGateTool();
                    CREATED_TOOLS.add(gate);
                    toolkit.registerAgentTool(gate);
                    return toolkit;
                }
            };
        }
    }

    @BeforeAll
    static void prepareDirs() throws Exception {
        Files.createDirectories(java.nio.file.Path.of("build/agui-interrupt-test/workspace"));
        Files.createDirectories(java.nio.file.Path.of("build/agui-interrupt-test/state"));
    }

    @DisplayName("写工具触发权限ASK：RUN_FINISHED携带permission_confirm中断且工具未执行")
    @Test
    void givenWriteToolCall_whenRun_thenPermissionConfirmInterruptEmitted() throws Exception {
        CREATED_TOOLS.clear();
        MODEL.script(List.of(toolCall()));
        MockHttpSession session = registerApproveAndLogin("agui_ia_alice");
        String threadId = "interrupt-" + UUID.randomUUID();

        String body = run(session, runRequest(threadId, "r-1", "写一条记录"));
        JsonNode finished = lastEventOfType(body, "RUN_FINISHED");

        assertThat(finished.path("outcome").path("type").asText()).isEqualTo("interrupt");
        JsonNode interrupt = finished.path("outcome").path("interrupts").path(0);
        assertThat(interrupt.path("reason").asText()).isEqualTo("tool_call");
        assertThat(interrupt.path("toolCallId").asText()).isEqualTo("call_w_1");
        assertThat(interrupt.path("metadata").path("agentscope.interruptKind").asText())
                .isEqualTo("permission_confirm");
        assertThat(interrupt.path("metadata").path("toolName").asText()).isEqualTo("test_write");
        assertThat(interrupt.path("id").asText()).endsWith(":call_w_1");
        assertThat(interrupt.path("responseSchema").path("properties").has("approved")).isTrue();

        // 中断即停：写工具未执行、无工具结果事件
        assertThat(CREATED_TOOLS).allSatisfy(t -> assertThat(t.executed.get()).isFalse());
        assertThat(eventOfType(body, "TOOL_CALL_RESULT")).isNull();
    }

    @DisplayName("resume approved=true：工具执行且Agent续跑到最终回复")
    @Test
    void givenInterrupt_whenResumeApproved_thenToolExecutesAndRunContinues() throws Exception {
        CREATED_TOOLS.clear();
        MODEL.script(
                List.of(toolCall()),
                List.of(TextBlock.builder().text("已按确认完成写入。").build()));
        MockHttpSession session = registerApproveAndLogin("agui_ia_bob");
        String threadId = "interrupt-" + UUID.randomUUID();

        String first = run(session, runRequest(threadId, "r-1", "写一条记录"));
        String interruptId = lastEventOfType(first, "RUN_FINISHED")
                .path("outcome").path("interrupts").path(0).path("id").asText();

        String second = run(session, resumeRequest(threadId, "r-2", interruptId, true));

        // 2.0.3 实测钉住：续跑轮不重放工具事件——TOOL_CALL_START 在中断轮已发过，续跑流的
        // startedToolCalls 为空，AguiStreamContext#hasStartedToolCall 抑制 TOOL_CALL_RESULT。
        // 工具确已执行由下方 executed 标志与服务端 state（tool_result success）佐证。
        assertThat(eventOfType(second, "TOOL_CALL_RESULT"))
                .as("r-2 事件流：%s", second)
                .isNull();
        assertThat(CREATED_TOOLS).anySatisfy(t -> assertThat(t.executed.get()).isTrue());
        assertThat(second).contains("已按确认完成写入。");
        JsonNode finished = lastEventOfType(second, "RUN_FINISHED");
        assertThat(finished.path("outcome").isMissingNode()).isTrue();
        assertThat(eventOfType(second, "RUN_ERROR")).isNull();
    }

    @DisplayName("resume approved=false：工具被拒不执行且Agent继续")
    @Test
    void givenInterrupt_whenResumeDenied_thenToolSkippedAndRunContinues() throws Exception {
        CREATED_TOOLS.clear();
        MODEL.script(
                List.of(toolCall()),
                List.of(TextBlock.builder().text("用户拒绝，改为只读结论。").build()));
        MockHttpSession session = registerApproveAndLogin("agui_ia_carol");
        String threadId = "interrupt-" + UUID.randomUUID();

        String first = run(session, runRequest(threadId, "r-1", "写一条记录"));
        String interruptId = lastEventOfType(first, "RUN_FINISHED")
                .path("outcome").path("interrupts").path(0).path("id").asText();

        String second = run(session, resumeRequest(threadId, "r-2", interruptId, false));

        assertThat(CREATED_TOOLS).allSatisfy(t -> assertThat(t.executed.get()).isFalse());
        assertThat(second).contains("用户拒绝，改为只读结论。");
        JsonNode finished = lastEventOfType(second, "RUN_FINISHED");
        assertThat(finished.path("outcome").isMissingNode()).isTrue();
        assertThat(eventOfType(second, "RUN_ERROR")).isNull();
    }

    @DisplayName("无open interrupt时resume返回契约错误（P0观测行为保持）")
    @Test
    void givenNoOpenInterrupt_whenResume_thenContractError() throws Exception {
        MODEL.script(List.of(TextBlock.builder().text("无需确认。").build()));
        MockHttpSession session = registerApproveAndLogin("agui_ia_dave");
        String threadId = "interrupt-" + UUID.randomUUID();

        String body = run(session, resumeRequest(threadId, "r-1", "no-such-interrupt:call_x", true));

        JsonNode error = eventOfType(body, "RUN_ERROR");
        assertThat(error).isNotNull();
        assertThat(error.path("code").asText()).isEqualTo("AGUI_INTERRUPT_CONTRACT_ERROR");
    }

    // ———— 请求与 SSE 解析 ————

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

    /** 解析 SSE 响应体中全部 data 行事件。 */
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

    /**
     * 构造 test_write 的 ToolUseBlock：input 与 content 必须同时提供——
     * ToolExecutor 的参数校验读 {@code content}（raw JSON），缺失会直接校验失败，走不到权限门。
     */
    private static ToolUseBlock toolCall() {
        Map<String, Object> input = Map.of("note", "验证写入");
        return new ToolUseBlock(
                "call_w_1", "test_write", input, MAPPER.valueToTree(input).toString(), Map.of());
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

    // ———— 测试替身 ————

    /** 验证用写工具：readOnly=false 触发权限 ASK；executed 标记是否真正执行。 */
    static class WriteGateTool extends ToolBase {
        final AtomicBoolean executed = new AtomicBoolean();

        WriteGateTool() {
            super(ToolBase.builder()
                    .name("test_write")
                    .description("验证用写工具：需要用户确认才允许执行")
                    .readOnly(false)
                    .inputSchema(Map.of(
                            "type", "object",
                            "properties", Map.of("note",
                                    Map.of("type", "string", "description", "写入备注")),
                            "required", List.of("note"))));
        }

        /**
         * 复刻 2.0.3 McpTool 的权限语义：非只读工具每次调用都要用户授权。
         * 注：@Tool 注解工具的 checkPermissions 默认 passthrough——2.0.3 权限上下文为
         * trivial（DEFAULT 模式且无规则）时走轻量路径，工具自身不 ASK 即放行。
         */
        @Override
        public Mono<PermissionDecision> checkPermissions(
                Map<String, Object> toolInput, PermissionContextState context) {
            return Mono.just(PermissionDecision.ask("test_write requires explicit authorization"));
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            executed.set(true);
            return Mono.just(new ToolResultBlock(
                    param.getToolUseBlock().getId(),
                    getName(),
                    TextBlock.builder()
                            .text("{\"written\":true,\"note\":\"" + param.getInput().get("note") + "\"}")
                            .build()));
        }
    }

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
            // harness 记忆归档/整合（MemoryFlushManager/MemoryConsolidator）以 tools=null 直呼 Model，
            // 会偷走脚本化回复：识别后返回固定「无新增记忆」，不消耗脚本队列
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
