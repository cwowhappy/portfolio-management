package com.portfolio.invest.agui;

import static com.portfolio.invest.support.AguiTestSupport.lastEventOfType;
import static com.portfolio.invest.support.AguiTestSupport.parseEvents;
import static com.portfolio.invest.support.AguiTestSupport.registerApproveAndLogin;
import static com.portfolio.invest.support.AguiTestSupport.resumeRequest;
import static com.portfolio.invest.support.AguiTestSupport.run;
import static com.portfolio.invest.support.AguiTestSupport.runRequest;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.agent.HarnessAgentFactory;
import com.portfolio.invest.agent.InvestTools;
import com.portfolio.invest.agent.McpClientPool;
import com.portfolio.invest.agent.UserToolkitFactory;
import com.portfolio.invest.agent.trust.RecordingAgentToolDecorator;
import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.mcp.McpConfigRepository;
import com.portfolio.invest.domain.mcp.McpSecretCodec;
import com.portfolio.invest.domain.user.UserRepository;
import com.portfolio.invest.support.PostgresTestSupport;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
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
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * TrustAgentHook 全流水集成测试（MS-29 B5）：真实 HarnessAgentFactory（含 B5 挂载）+
 * 装饰后 toolkit + 脚本化假 Model，锁三件事——
 *
 * <ul>
 *   <li><strong>AG-UI 路</strong>：大偏差末轮 → 修正后 Msg 落 stateStore（agent_state.json）+
 *       SSE 帧文本层出现 Custom（trust.correction 先于 trust.anchors）、messageId 与
 *       TEXT_MESSAGE 同源、payload 无 null；</li>
 *   <li><strong>HITL 不因装饰失效</strong>（B3 遗留半边）：ASK 写工具经 RecordingAgentToolDecorator
 *       仍触发权限中断，resume 后续跑至末轮照常锚定；</li>
 *   <li><strong>飞书路</strong>（HarnessAgentFactory.build().call() 共同漏斗）：改写生效、
 *       Custom 事件无消费者静默不报错。</li>
 * </ul>
 *
 * <p>技术方案沿用 {@link AguiInterruptIntegrationTest}：静态单例脚本化 Model（@TestBean 替换
 * investModel）、@Primary 覆盖 userToolkitFactory 在 super.build 生产装饰之上追加测试工具——
 * 测试工具以<strong>真实 RecordingAgentToolDecorator 手工包装</strong>（复刻生产 wiring），
 * stub ToolUseBlock 同时带 input map 与 raw content JSON（B0 教训）。
 */
@SpringBootTest(properties = {
        "DEEPSEEK_API_KEY=test-dummy-key",
        "invest.mcp.harness.workspace=build/trust-hook-test/workspace",
        "invest.mcp.harness.state-root=build/trust-hook-test/state"})
@AutoConfigureMockMvc
@Import(TrustHookIntegrationTest.TrustToolkitConfig.class)
class TrustHookIntegrationTest extends PostgresTestSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path STATE_ROOT = Path.of("build/trust-hook-test/state");

    private static final ScriptedModel MODEL = new ScriptedModel();
    private static final List<AskGateTool> CREATED_ASK_TOOLS = new CopyOnWriteArrayList<>();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository userRepository;

    @Autowired
    HarnessAgentFactory harnessAgentFactory;

    @TestBean(methodName = "scriptedModel")
    Model investModel;

    static Model scriptedModel() {
        return MODEL;
    }

    /**
     * 以 @Primary 覆盖 userToolkitFactory：保留 super.build 完整生产装配（内置装饰 + chunkCallback），
     * 追加注册<strong>经 RecordingAgentToolDecorator 包装</strong>的测试工具（与生产 decorateWithRecording
     * 同款 wiring——工具名不在内置名单，须在注册时手工包一层）。
     */
    @org.springframework.boot.test.context.TestConfiguration
    static class TrustToolkitConfig {
        @Bean
        @Primary
        UserToolkitFactory trustToolkitFactory(InvestTools investTools,
                McpConfigRepository mcpConfigRepository, McpClientPool mcpClientPool,
                McpSecretCodec secretCodec,
                com.portfolio.invest.application.portfolio.PortfolioApplicationService portfolioService,
                com.portfolio.invest.application.allocation.AllocationApplicationService allocationService,
                com.portfolio.invest.application.intelligence.IntelligenceQueryService intelligenceQueryService,
                ObjectMapper objectMapper, InvestProperties investProperties) {
            return new UserToolkitFactory(investTools, mcpConfigRepository, mcpClientPool, secretCodec,
                    portfolioService, allocationService, intelligenceQueryService, objectMapper,
                    investProperties.getMcp().getToolTimeout()) {
                @Override
                public Toolkit build(Long userId) {
                    Toolkit toolkit = super.build(userId);
                    toolkit.registerAgentTool(new RecordingAgentToolDecorator(
                            new QuoteStubTool(), false, objectMapper));
                    AskGateTool gate = new AskGateTool();
                    CREATED_ASK_TOOLS.add(gate);
                    toolkit.registerAgentTool(new RecordingAgentToolDecorator(gate, false, objectMapper));
                    return toolkit;
                }
            };
        }
    }

    @BeforeAll
    static void prepareDirs() throws Exception {
        Files.createDirectories(Path.of("build/trust-hook-test/workspace"));
        Files.createDirectories(Path.of("build/trust-hook-test/state"));
    }

    @DisplayName("AG-UI 路：大偏差修正落 stateStore + SSE 帧文本层含 Custom 事件且 messageId 同源")
    @Test
    void givenWrongQuoteInFinalText_whenRun_thenCorrectedInStateAndCustomFramesOnWire() throws Exception {
        MODEL.script(
                List.of(toolCall("call_q_1", "test_quote", "{\"code\":\"600519\"}")),
                List.of(TextBlock.builder().text("茅台现价15.20元。").build()));
        MockHttpSession session = registerApproveAndLogin(mockMvc, userRepository, "trust_hook_alice");
        String threadId = "trust-" + UUID.randomUUID();

        String body = run(mockMvc, session, runRequest(threadId, "r-1", "查茅台现价"));

        // ———— 字面 SSE 帧断言（B0 审查遗留：帧文本层，非 streamEvents 对象层） ————
        assertThat(body).contains("CUSTOM");
        assertThat(body).contains("\"name\":\"trust.correction\"");
        assertThat(body).contains("\"name\":\"trust.anchors\"");

        // ———— 帧结构断言：correction 先于 anchors；value 无 null ————
        JsonNode correction = customEvent(body, "trust.correction");
        JsonNode anchors = customEvent(body, "trust.anchors");
        assertThat(correction).isNotNull();
        assertThat(anchors).isNotNull();
        assertThat(indexOfCustom(body, "trust.correction"))
                .as("trust.correction 应先于 trust.anchors 出现")
                .isLessThan(indexOfCustom(body, "trust.anchors"));
        assertThat(correction.toString()).doesNotContain(":null");
        assertThat(anchors.toString()).doesNotContain(":null");

        // messageId 同源：Custom 的 messageId == TEXT_MESSAGE_START 的 messageId
        JsonNode textStart = lastEventOfType(body, "TEXT_MESSAGE_START");
        assertThat(textStart).isNotNull();
        assertThat(textStart.path("messageId").asText()).isNotBlank();
        assertThat(correction.path("value").path("messageId").asText())
                .isEqualTo(textStart.path("messageId").asText());
        assertThat(anchors.path("value").path("messageId").asText())
                .isEqualTo(textStart.path("messageId").asText());

        // correction 事件内容：snippet/occ/replacement/note
        JsonNode correctionValue = correction.path("value");
        assertThat(correctionValue.path("snippet").asText()).isEqualTo("15.20元");
        assertThat(correctionValue.path("occ").asInt()).isEqualTo(1);
        assertThat(correctionValue.path("replacement").asText()).isEqualTo("1520.33元");
        assertThat(correctionValue.path("note").asText()).contains("原文误述");

        // anchors payload：v=1、verified 锚定带工具来源与 asOf
        JsonNode payload = anchors.path("value").path("payload");
        assertThat(payload.path("v").asInt()).isEqualTo(1);
        assertThat(payload.path("stats").path("verified").asInt()).isEqualTo(1);
        JsonNode firstAnchor = payload.path("anchors").path(0);
        assertThat(firstAnchor.path("state").asText()).isEqualTo("verified");
        assertThat(firstAnchor.path("tool").asText()).isEqualTo("test_quote");
        assertThat(firstAnchor.path("asOf").asText()).isEqualTo("2026-10-05 14:59:32");
        assertThat(firstAnchor.path("asOfKind").asText()).isEqualTo("data");
        assertThat(firstAnchor.path("raw").asText()).isEqualTo("1520.33");

        // ———— 修正后 Msg 落 stateStore（agent_state.json） ————
        String state = stateContentOf(threadId);
        assertThat(state).contains("1520.33元");
        assertThat(state).contains("> ⚠ 校验修正：原文误述 15.20元");
        assertThat(state).contains("_trust_pool");
        assertThat(state).doesNotContain("现价15.20元");
    }

    @DisplayName("HITL 不因装饰失效：ASK 写工具经装饰器触发权限中断，resume 后末轮照常锚定")
    @Test
    void givenDecoratedAskTool_whenRunAndResume_thenInterruptThenAnchors() throws Exception {
        CREATED_ASK_TOOLS.clear();
        MODEL.script(
                List.of(toolCall("call_w_1", "test_write", "{\"note\":\"记录\"}")),
                List.of(TextBlock.builder().text("已按确认完成写入，无数字。").build()));
        MockHttpSession session = registerApproveAndLogin(mockMvc, userRepository, "trust_hook_bob");
        String threadId = "trust-" + UUID.randomUUID();

        String first = run(mockMvc, session, runRequest(threadId, "r-1", "写一条记录"));
        JsonNode finished = lastEventOfType(first, "RUN_FINISHED");
        assertThat(finished.path("outcome").path("type").asText()).isEqualTo("interrupt");
        assertThat(finished.path("outcome").path("interrupts").path(0)
                .path("metadata").path("toolName").asText()).isEqualTo("test_write");
        assertThat(CREATED_ASK_TOOLS).as("ASK 工具应已注册").isNotEmpty();

        String interruptId = finished.path("outcome").path("interrupts").path(0).path("id").asText();
        String second = run(mockMvc, session, resumeRequest(threadId, "r-2", interruptId, true));

        // resume 续跑：工具执行 + 末轮文本 + hook 照常锚定（信任事件在 resume 流内）
        assertThat(CREATED_ASK_TOOLS).allSatisfy(t -> assertThat(t.executed.get()).isTrue());
        assertThat(second).contains("已按确认完成写入");
        assertThat(second).contains("CUSTOM");
        assertThat(second).contains("\"name\":\"trust.anchors\"");
        assertThat(eventOfTypeOrNull(second, "RUN_ERROR")).isNull();
        // 中断轮不处理（末轮未到），resume 轮处理——两轮都不应有 RUN_ERROR
        assertThat(eventOfTypeOrNull(first, "RUN_ERROR")).isNull();
    }

    @DisplayName("飞书路：HarnessAgentFactory.build().call() 改写生效且无事件爆炸")
    @Test
    void givenFeishuFunnelCall_whenCall_thenRewriteEffectiveAndNoError() throws Exception {
        MODEL.script(
                List.of(toolCall("call_q_2", "test_quote", "{\"code\":\"600519\"}")),
                List.of(TextBlock.builder().text("茅台现价15.20元。").build()));
        String hash = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("abc12345");
        var user = userRepository.save(com.portfolio.invest.domain.user.User
                .register("trust_hook_feishu", hash).approve());

        io.agentscope.harness.agent.HarnessAgent agent = harnessAgentFactory.build(user.id());
        Msg reply = agent.call(
                        Msg.builder().role(MsgRole.USER).textContent("查茅台现价").build(),
                        RuntimeContext.builder().userId(String.valueOf(user.id()))
                                .sessionId("feishu-" + UUID.randomUUID()).build())
                .block(Duration.ofSeconds(120));

        assertThat(reply).isNotNull();
        assertThat(reply.getTextContent())
                .contains("1520.33元")
                .contains("> ⚠ 校验修正：原文误述 15.20元");
    }

    // ———— 辅助 ————

    private static ToolUseBlock toolCall(String id, String name, String rawJson) throws Exception {
        Map<String, Object> input = MAPPER.convertValue(
                MAPPER.readTree(rawJson),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        return new ToolUseBlock(id, name, input, rawJson, Map.of());
    }

    private static JsonNode customEvent(String body, String name) throws Exception {
        for (JsonNode event : parseEvents(body)) {
            if ("CUSTOM".equals(event.path("type").asText())
                    && name.equals(event.path("name").asText())) {
                return event;
            }
        }
        return null;
    }

    private static int indexOfCustom(String body, String name) {
        return body.indexOf("\"name\":\"" + name + "\"");
    }

    private static JsonNode eventOfTypeOrNull(String body, String type) throws Exception {
        JsonNode found = null;
        for (JsonNode event : parseEvents(body)) {
            if (type.equals(event.path("type").asText())) {
                found = event;
            }
        }
        return found;
    }

    /** 递归拼接该 threadId 会话目录下的 state 文件内容（布局：<stateRoot>/<userId>/<threadId>/）。 */
    private static String stateContentOf(String threadId) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (Stream<Path> walk = Files.walk(STATE_ROOT)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().contains("/" + threadId + "/"))
                    .forEach(p -> {
                        try {
                            sb.append(Files.readString(p)).append('\n');
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
        assertThat(sb.length()).as("threadId=%s 应已落盘 state", threadId).isGreaterThan(0);
        return sb.toString();
    }

    // ———— 测试替身 ————

    /** 只读行情 stub：返回带 time 时点的 JSON（真值 1520.33）。 */
    static class QuoteStubTool extends ToolBase {
        QuoteStubTool() {
            super(ToolBase.builder()
                    .name("test_quote")
                    .description("测试行情")
                    .readOnly(true)
                    .inputSchema(Map.of("type", "object",
                            "properties", Map.of("code", Map.of("type", "string")),
                            "required", List.of("code"))));
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.just(new ToolResultBlock(
                    param.getToolUseBlock().getId(), "test_quote",
                    TextBlock.builder()
                            .text("{\"code\":\"600519\",\"price\":1520.33,\"time\":\"2026-10-05 14:59:32\"}")
                            .build()));
        }
    }

    /** 写工具：checkPermissions 恒 ASK（复刻 2.0.3 McpTool 权限语义），executed 标记真实执行。 */
    static class AskGateTool extends ToolBase {
        final AtomicBoolean executed = new AtomicBoolean();

        AskGateTool() {
            super(ToolBase.builder()
                    .name("test_write")
                    .description("验证用写工具")
                    .readOnly(false)
                    .inputSchema(Map.of(
                            "type", "object",
                            "properties", Map.of("note", Map.of("type", "string")),
                            "required", List.of("note"))));
        }

        @Override
        public Mono<PermissionDecision> checkPermissions(
                Map<String, Object> toolInput, PermissionContextState context) {
            return Mono.just(PermissionDecision.ask("test_write requires explicit authorization"));
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            executed.set(true);
            return Mono.just(new ToolResultBlock(
                    param.getToolUseBlock().getId(), "test_write",
                    TextBlock.builder().text("{\"written\":true}").build()));
        }
    }

    /** 脚本化假 Model（照抄 AguiInterruptIntegrationTest：记忆维护调用不消耗脚本）。 */
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
