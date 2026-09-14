package com.portfolio.invest.agui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.invest.domain.user.UserRepository;
import com.portfolio.invest.support.PostgresTestSupport;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Flux;

/**
 * /agui/run SSE 事件流端到端：认证会话 → AG-UI 生命周期事件 → 错误路径。
 *
 * <p>技术方案说明：
 * <ul>
 *   <li>假 Model：{@code DEEPSEEK_API_KEY} 只是占位值（触发 {@code AgentConfig} 的
 *   {@code @ConditionalOnExpression} 装配路径），真正的 DeepSeek Model 在构造时会读真实环境变量
 *   （{@code System.getenv("DEEPSEEK_API_KEY")}，见 agentscope DeepSeekModelProvider），测试进程里没有。
 *   因此用 {@code @TestBean} + 静态工厂把 bean 名 {@code investModel} 整个替换为 {@link FixedReplyModel}
 *   （bean 定义级替换，原 {@code ModelRegistry.resolve(...)} 工厂方法不会执行），
 *   {@code AgentConfig#investAgent} 拿到假 Model 装配出真实 ReActAgent，全程不打真实 LLM。</li>
 *   <li>SSE 断言：{@code AguiMvcController} 返回 {@code SseEmitter}（Spring MVC 内部包装成 DeferredResult，
 *   {@code emitter.complete()} 时才释放 MockMvc 异步锁）。MockMvc 对该模型支持是确定的：
 *   {@code getAsyncResult(timeout)} 阻塞到流结束，再 {@code asyncDispatch} 收尾后读取完整响应体，
 *   事件以 {@code data: {"type":"RUN_STARTED",...}} 文本行写入响应（见 agentscope AguiEventEncoder），
 *   因此直接对响应体做子串断言，无需真实端口与 HTTP 客户端。</li>
 *   <li>假 Model 返回单条不含 ToolUseBlock 的文本块：ReActAgent 收到无工具调用的响应即结束推理循环，
 *   驱动出完整 RUN_STARTED → TEXT_MESSAGE_* → RUN_FINISHED 生命周期。</li>
 *   <li>状态隔离：仅重定向 state-root 至 build/agui-stream-test/state，避免 harness 会话状态写进
 *   仓库 .agentscope/state（McpHitlIntegrationTest 另重定向 workspace，本类不重定向——workspace
 *   仍为缺省 .agentscope/workspace，整目录已 gitignore，且本类假 Model 固定回复不含 ToolUseBlock，
 *   不会调用任何写工具）；现有用例不断言 state，无影响。</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "DEEPSEEK_API_KEY=test-dummy-key",
        "invest.mcp.harness.state-root=build/agui-stream-test/state"})
@AutoConfigureMockMvc
class AguiStreamIntegrationTest extends PostgresTestSupport {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository userRepository;

    /** bean 名按字段名推断为 investModel，精确替换 AgentConfig#investModel。 */
    @TestBean(methodName = "fixedReplyModel")
    Model investModel;

    static Model fixedReplyModel() {
        return new FixedReplyModel();
    }

    @DisplayName("认证用户发起对话返回完整AGUI事件流")
    @Test
    void givenAuthenticatedUser_whenRunConversation_thenFullAguiEventStreamReturned() throws Exception {
        MockHttpSession session = registerApproveAndLogin("agui_alice");

        MvcResult result = mockMvc.perform(post("/agui/run")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(runRequest("t-1", "r-1", "分析一下贵州茅台")))
                .andExpect(request().asyncStarted())
                .andReturn();

        // 阻塞到 SSE 流结束（emitter.complete() 释放异步锁），再收尾读取完整事件流
        result.getAsyncResult(30_000);
        mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk());

        String body = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        // AG-UI 生命周期事件齐全
        assertThat(body).contains("RUN_STARTED");
        assertThat(body).contains("TEXT_MESSAGE_START");
        assertThat(body).contains("TEXT_MESSAGE_CONTENT");
        assertThat(body).contains("TEXT_MESSAGE_END");
        assertThat(body).contains("RUN_FINISHED");
        // 假 Model 的固定回复进入事件流，证明 Model→Agent→AG-UI 适配链路走通
        assertThat(body).contains(FixedReplyModel.REPLY);
        // 装配进上下文的确实是假 Model（而非真实 DeepSeek 客户端）
        assertThat(investModel).isInstanceOf(FixedReplyModel.class);
    }

    @DisplayName("请求未注册Agent返回流内错误事件而非500")
    @Test
    void givenUnregisteredAgent_whenRunConversation_thenInStreamErrorNot500() throws Exception {
        MockHttpSession session = registerApproveAndLogin("agui_bob");

        // AguiMvcController 在异步线程内捕获 AgentNotFoundException，
        // 降级为流内 error 事件 + RUN_FINISHED（HTTP 200），不会冒泡到 GlobalExceptionHandler
        MvcResult result = mockMvc.perform(post("/agui/run")
                        .session(session)
                        .header("X-Agent-Id", "ghost-agent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(runRequest("t-2", "r-2", "你好")))
                .andExpect(request().asyncStarted())
                .andReturn();

        result.getAsyncResult(30_000);
        mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk());

        String body = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).contains("error").contains("ghost-agent");
        assertThat(body).contains("RUN_FINISHED");
        assertThat(body).doesNotContain("TEXT_MESSAGE_START");
    }

    @DisplayName("非法请求体返回400与流内解析错误事件")
    @Test
    void givenInvalidRequestBody_whenRunConversation_thenStructuredErrorNotEventStream() throws Exception {
        MockHttpSession session = registerApproveAndLogin("agui_carol");

        // agentscope 2.0.3：请求体由 starter 内置 AguiRequestBodyParser 解析，非法 JSON 不再
        // 冒泡为 MVC 绑定异常（GlobalExceptionHandler 不介入），而是以 SSE 流内 RAW 事件返回
        // 解析错误（threadId/runId 回退 unknown），HTTP 状态 400
        MvcResult result = mockMvc.perform(post("/agui/run")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-a-json"))
                .andExpect(status().isBadRequest())
                .andReturn();
        String body = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).contains("Failed to parse request");
        assertThat(body).doesNotContain("TEXT_MESSAGE_START");
    }

    @DisplayName("同会话两轮对话第二轮只发最新消息时模型输入仍含第一轮文本")
    @Test
    void givenSecondTurn_whenSendOnlyLatestMessage_thenAgentSeesPriorContext() throws Exception {
        // 录制清零：只看本用例的两轮调用（记忆用例不关心其它用例的历史录制）
        FixedReplyModel.RECORDED_CALLS.clear();
        MockHttpSession session = registerApproveAndLogin("agui_memory");
        // 同一 session 同一 threadId、不同 runId：两轮各自独立 run，会话连续性由服务端识别
        String threadId = "memory-" + UUID.randomUUID();

        // 第一轮告知姓名；第二轮 runRequest 天然只携带最新一条消息（不发历史）
        String firstBody = runAgui(session, runRequest(threadId, "r-mem-1", "我叫小明，请记住"));
        String secondBody = runAgui(session, runRequest(threadId, "r-mem-2", "我叫什么名字？"));

        // 两轮均完整走完生命周期且无流内错误
        assertThat(firstBody).contains("RUN_FINISHED").doesNotContain("RUN_ERROR");
        assertThat(secondBody).contains("RUN_FINISHED").doesNotContain("RUN_ERROR");

        // 第二次推理调用的 messages 含第一轮用户文本：历史由服务端 stateStore 拼装而非客户端回传，
        // 锁 ADR-0011 server-side-memory 语义（agentscope.agui.server-side-memory=true）
        List<Msg> secondTurnInput = inferenceInputs().stream()
                .filter(msgs -> containsText(msgs, "我叫什么名字？"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "未找到含第二轮问句的推理调用，实际录制：" + FixedReplyModel.RECORDED_CALLS));
        assertThat(joinText(secondTurnInput)).contains("我叫小明");
    }

    // ———— 两轮记忆用例辅助（录制判读 + 三段式 run 收敛） ————

    /** 三段式跑一轮 /agui/run（asyncStarted → getAsyncResult → asyncDispatch），返回完整 SSE 响应体。 */
    private String runAgui(MockHttpSession session, String json) throws Exception {
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

    /**
     * 推理轮的 Model 输入：剔除 harness 记忆归档/整合调用。该类调用以 {@code tools=null}
     * 直呼 Model，或提示词含 "Extract NEW memories"（守卫口径同 McpHitlIntegrationTest
     * 的 ScriptedModel），录制时一并留存以便过滤。
     */
    private static List<List<Msg>> inferenceInputs() {
        return FixedReplyModel.RECORDED_CALLS.stream()
                .filter(call -> call.tools() != null)
                .filter(call -> !containsText(call.messages(), "Extract NEW memories"))
                .map(FixedReplyModel.ModelCall::messages)
                .toList();
    }

    private static boolean containsText(List<Msg> messages, String needle) {
        return messages.stream().anyMatch(m -> {
            String text = m.getTextContent();
            return text != null && text.contains(needle);
        });
    }

    private static String joinText(List<Msg> messages) {
        return messages.stream()
                .map(Msg::getTextContent)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n"));
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

    private static String runRequest(String threadId, String runId, String text) {
        // AG-UI RunAgentInput 线格式（与 CopilotKit HttpAgent 发送的一致）
        return """
                {"threadId":"%s","runId":"%s","state":{},"messages":[{"id":"%s","role":"user","content":"%s"}],"tools":[],"context":[],"forwardedProps":{}}
                """
                .formatted(threadId, runId, UUID.randomUUID(), text);
    }

    /**
     * 固定回复的假 Model：返回单条预构造文本响应（含 usage，配合 emit-token-usage）。
     * 另顺带录制每次 {@code stream()} 的 {@code (messages, tools)} 快照到 {@link #RECORDED_CALLS}
     * （只录不改，回复语义不变），供两轮记忆用例判读模型实际收到的输入。
     */
    static class FixedReplyModel implements Model {

        static final String REPLY = "这是测试环境的固定投研回复。";

        /** 单次 stream() 调用快照：messages 与 tools 成对留存，供断言区分推理轮与记忆归档调用。 */
        record ModelCall(List<Msg> messages, List<ToolSchema> tools) {}

        /** 跨用例累积的调用录制（用例开始前 clear）；并发结构，agent 异步线程写、测试线程读。 */
        static final ConcurrentLinkedQueue<ModelCall> RECORDED_CALLS = new ConcurrentLinkedQueue<>();

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            RECORDED_CALLS.add(new ModelCall(List.copyOf(messages), tools));
            return Flux.just(ChatResponse.builder()
                    .id("fake-completion-1")
                    .content(List.of(TextBlock.builder().text(REPLY).build()))
                    .usage(new ChatUsage(10, 5, 0.01))
                    .finishReason("stop")
                    .build());
        }

        @Override
        public String getModelName() {
            return "fixed-reply-model";
        }
    }
}
