package com.portfolio.invest.agui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.invest.domain.user.UserRepository;
import com.portfolio.invest.infrastructure.security.AuthenticatedUser;
import com.portfolio.invest.support.PostgresTestSupport;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.spring.boot.agui.common.AguiAgentRegistryCustomizer;
import io.agentscope.spring.boot.agui.common.AguiRuntimeContextResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Flux;

/**
 * Spike（Option B）：resolver 从 HTTP session 的 SecurityContext 读 userId → ThreadLocal → 工厂 supplier 读 ThreadLocal。
 *
 * <p>回答的唯一问题：{@link AguiRuntimeContextResolver} 与 {@code registerFactory} 的 supplier
 * 都运行在 {@code AguiMvcController} 的同一个线程池线程上（resolver 先、supplier 后），
 * resolver 通过 {@code AguiRuntimeContextRequest#getNativeRequest} 拿到原始 {@code HttpServletRequest}，
 * 从其 session 的 {@code SPRING_SECURITY_CONTEXT} 属性（Spring Security 登录时写入）取出
 * {@code AuthenticatedUser.user().id()}，存入静态 {@code ThreadLocal}，工厂 supplier 再读该 ThreadLocal。
 * 若 {@code capturedUserId} 等于登录用户 id，则 Option B 可行。
 */
@SpringBootTest(properties = "DEEPSEEK_API_KEY=test-dummy-key")
@AutoConfigureMockMvc
class SecurityContextThreadLocalSpikeTest extends PostgresTestSupport {

    /** resolver 写入、supplier 读取的按线程 userId 载体。 */
    static final ThreadLocal<Long> CURRENT_USER = new ThreadLocal<>();

    /** supplier 运行线程上读到的登录用户 id（null = ThreadLocal 未生效）。 */
    static volatile Long capturedUserId;

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

    @DisplayName("resolver 从 session 读 userId 写入 ThreadLocal，工厂 supplier 读到正确用户")
    @Test
    void givenAuthenticatedUser_whenRunFactoryAgent_thenFactoryReadsUserIdViaThreadLocal() throws Exception {
        MockHttpSession session = registerApproveAndLogin("spike_alice");

        Long expectedUserId = userRepository.findByUsername("spike_alice").orElseThrow().id();

        MvcResult result = mockMvc.perform(post("/agui/run")
                        .session(session)
                        .header("X-Agent-Id", "spike-agent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(runRequest("t-1", "r-1", "hi")))
                .andExpect(request().asyncStarted())
                .andReturn();

        // 阻塞到 SSE 流结束（emitter.complete() 释放异步锁），此时 resolver 与 supplier 必已执行
        result.getAsyncResult(30_000);
        mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk());

        assertThat(capturedUserId)
                .as("工厂 supplier 必须通过 ThreadLocal 读到登录用户 id")
                .isEqualTo(expectedUserId);
    }

    @TestConfiguration
    static class SpikeConfig {

        @Bean
        AguiRuntimeContextResolver spikeRuntimeContextResolver() {
            return request -> {
                Long userId = null;
                HttpServletRequest nativeReq = request.getNativeRequest(HttpServletRequest.class);
                if (nativeReq != null) {
                    HttpSession session = nativeReq.getSession(false);
                    Object ctx = session == null
                            ? null
                            : session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
                    if (ctx instanceof SecurityContext securityContext) {
                        Authentication auth = securityContext.getAuthentication();
                        if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser authenticatedUser) {
                            userId = authenticatedUser.user().id();
                        }
                    }
                }
                CURRENT_USER.set(userId);
                return RuntimeContext.builder()
                        .userId(userId == null ? null : userId.toString())
                        .build();
            };
        }

        @Bean
        AguiAgentRegistryCustomizer spikeAgentRegistryCustomizer(Model investModel) {
            return registry -> registry.registerFactory("spike-agent", () -> {
                capturedUserId = CURRENT_USER.get();
                return ReActAgent.builder()
                        .name("spike-agent")
                        .sysPrompt("你是 spike 测试 agent。")
                        .model(investModel)
                        .toolkit(new Toolkit())
                        .maxIters(1)
                        .build();
            });
        }
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
