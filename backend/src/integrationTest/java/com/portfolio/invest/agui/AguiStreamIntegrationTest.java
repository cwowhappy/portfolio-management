package com.portfolio.invest.agui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import java.util.UUID;
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
 * </ul>
 */
@SpringBootTest(properties = "DEEPSEEK_API_KEY=test-dummy-key")
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

    @Test
    void 认证用户发起对话返回完整AGUI事件流() throws Exception {
        MockHttpSession session = 注册审核并登录("agui_alice");

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

    @Test
    void 请求未注册Agent返回流内错误事件而非500() throws Exception {
        MockHttpSession session = 注册审核并登录("agui_bob");

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

    @Test
    void 非法请求体返回结构化错误而非事件流() throws Exception {
        MockHttpSession session = 注册审核并登录("agui_carol");

        // JSON 解析在控制器入参绑定阶段失败，走不到 SSE：
        // GlobalExceptionHandler 的 HttpMessageNotReadableException 映射 → 400 INVALID_REQUEST
        mockMvc.perform(post("/agui/run")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-a-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    private MockHttpSession 注册审核并登录(String username) throws Exception {
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

    /** 固定回复的假 Model：返回单条预构造文本响应（含 usage，配合 emit-token-usage）。 */
    static class FixedReplyModel implements Model {

        static final String REPLY = "这是测试环境的固定投研回复。";

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
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
