package com.portfolio.invest.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.invest.domain.user.UserRepository;
import com.portfolio.invest.support.PostgresTestSupport;
import com.portfolio.invest.support.RecordingMailSender;
import com.portfolio.invest.support.TestCodes;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import com.portfolio.invest.domain.conversation.ConversationErrorCode;

@SpringBootTest
@AutoConfigureMockMvc
class ConversationControllerIntegrationTest extends PostgresTestSupport {

    /** 发信桩：@Primary 覆盖未配置 SMTP 的 SmtpMailSender，注册发码走桩取码。 */
    @TestConfiguration
    static class MailStub {
        @Bean
        @Primary
        RecordingMailSender recordingMailSender() {
            return new RecordingMailSender();
        }

        /**
         * 过渡期测试脚手架：UserToolkitFactory 的 Duration toolTimeout 依赖（BE-B 接线中）在
         * 主装配供给前由测试侧兜底；@ConditionalOnMissingBean 保证主装配一旦提供即自动退出。
         */
        @Bean
        @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(java.time.Duration.class)
        java.time.Duration mcpToolTimeoutFallback() {
            return java.time.Duration.ofSeconds(30);
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired RecordingMailSender mailStub;

    @DisplayName("登录用户建会话写读列_非本人404_未登录401")
    @Test
    void givenLoggedInOwnerAndOtherUser_whenConversationCrud_thenOwnerSucceedsOtherNotFoundAnonymousUnauthorized() throws Exception {
        register("conv_alice", "abc12345");
        register("conv_bob", "abc12345");
        approve("conv_alice");
        approve("conv_bob");

        // 未登录访问 → 401
        mockMvc.perform(get("/api/conversations"))
                .andExpect(status().isUnauthorized());

        MockHttpSession sessionA = login("conv_alice", "abc12345");
        MockHttpSession sessionB = login("conv_bob", "abc12345");

        // A 创建会话 → 201
        String convA = UUID.randomUUID().toString();
        mockMvc.perform(post("/api/conversations").session(sessionA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + convA + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(convA))
                .andExpect(jsonPath("$.title").value("新会话"));

        // A 保存消息 → 200（暴露新 updatedAt），并生成标题
        mockMvc.perform(put("/api/conversations/{id}/messages", convA).session(sessionA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"id\":\"m-1\",\"role\":\"user\",\"content\":\"你好\",\"createdAt\":1700000000000}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedAt").exists()); // B6：PUT 响应暴露新 updatedAt

        // A 读回消息
        mockMvc.perform(get("/api/conversations/{id}/messages", convA).session(sessionA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedAt").exists()) // B6：消息响应携带 updatedAt（If-Match 基准）
                .andExpect(jsonPath("$.messages[0].id").value("m-1"))
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andExpect(jsonPath("$.messages[0].content").value("你好"));

        // A 列表（按 updatedAt 倒序）包含该会话
        mockMvc.perform(get("/api/conversations").session(sessionA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(convA))
                .andExpect(jsonPath("$[0].title").value("你好"));

        // B 也创建一个会话
        String convB = UUID.randomUUID().toString();
        mockMvc.perform(post("/api/conversations").session(sessionB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + convB + "\"}"))
                .andExpect(status().isCreated());

        // A 访问 B 的会话消息 → 404
        mockMvc.perform(get("/api/conversations/{id}/messages", convB).session(sessionA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ConversationErrorCode.NOT_FOUND));

        // A 向 B 的会话保存消息 → 404
        mockMvc.perform(put("/api/conversations/{id}/messages", convB).session(sessionA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"id\":\"m-x\",\"role\":\"user\",\"content\":\"越权\",\"createdAt\":1700000000000}]"))
                .andExpect(status().isNotFound());

        // A 删除 B 的会话 → 404
        mockMvc.perform(delete("/api/conversations/{id}", convB).session(sessionA))
                .andExpect(status().isNotFound());

        // A 删除自己的会话 → 204；列表清空
        mockMvc.perform(delete("/api/conversations/{id}", convA).session(sessionA))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/conversations").session(sessionA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @DisplayName("乐观校验：过期If-Match→409且服务端消息不变，当前If-Match→200且updatedAt前进")
    @Test
    void givenConcurrentWrites_whenSaveMessagesWithIfMatch_thenConflictKeepsServerState() throws Exception {
        register("conv_eve", "abc12345");
        approve("conv_eve");
        MockHttpSession session = login("conv_eve", "abc12345");
        String convId = UUID.randomUUID().toString();
        mockMvc.perform(post("/api/conversations").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + convId + "\"}"))
                .andExpect(status().isCreated());

        // 基线：无 If-Match 写入 m-1（向后兼容放行），拿到服务端 updatedAt v1
        mockMvc.perform(put("/api/conversations/{id}/messages", convId).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"id\":\"m-1\",\"role\":\"user\",\"content\":\"服务端基线\",\"createdAt\":1700000000000}]"))
                .andExpect(status().isOk());
        MvcResult get1 = mockMvc.perform(get("/api/conversations/{id}/messages", convId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[0].id").value("m-1"))
                .andReturn();
        String v1 = com.jayway.jsonpath.JsonPath.read(get1.getResponse().getContentAsString(), "$.updatedAt");

        // 模拟另一窗口先写：携带 v1 的 PUT 成功（m-2），updatedAt 前进
        mockMvc.perform(put("/api/conversations/{id}/messages", convId).session(session)
                        .header("If-Match", v1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"id\":\"m-1\",\"role\":\"user\",\"content\":\"服务端基线\",\"createdAt\":1700000000000},"
                                + "{\"id\":\"m-2\",\"role\":\"assistant\",\"content\":\"另一窗口回复\",\"createdAt\":1700000001000}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedAt").exists());

        // 窗口 A 仍持旧值 v1：过期 If-Match → 409 CONFLICT，且服务端消息一字未变（无删除发生）
        mockMvc.perform(put("/api/conversations/{id}/messages", convId).session(session)
                        .header("If-Match", v1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"id\":\"m-1\",\"role\":\"user\",\"content\":\"服务端基线\",\"createdAt\":1700000000000},"
                                + "{\"id\":\"m-x\",\"role\":\"user\",\"content\":\"旧窗口的整段替换\",\"createdAt\":1700000002000}]"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        MvcResult afterConflict = mockMvc.perform(get("/api/conversations/{id}/messages", convId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages.length()").value(2))
                .andExpect(jsonPath("$.messages[1].id").value("m-2"))
                .andReturn();
        String v2 = com.jayway.jsonpath.JsonPath.read(afterConflict.getResponse().getContentAsString(), "$.updatedAt");

        // 窗口 A 重新 GET 拿到 v2 后重试 → 200（与前端 409→GET 合并→重试一次的流程一致）
        mockMvc.perform(put("/api/conversations/{id}/messages", convId).session(session)
                        .header("If-Match", v2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"id\":\"m-1\",\"role\":\"user\",\"content\":\"服务端基线\",\"createdAt\":1700000000000},"
                                + "{\"id\":\"m-2\",\"role\":\"assistant\",\"content\":\"另一窗口回复\",\"createdAt\":1700000001000},"
                                + "{\"id\":\"m-3\",\"role\":\"user\",\"content\":\"旧窗口合并重试\",\"createdAt\":1700000002000}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedAt").exists());
        mockMvc.perform(get("/api/conversations/{id}/messages", convId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages.length()").value(3))
                .andExpect(jsonPath("$.messages[2].id").value("m-3"));
    }

    @DisplayName("他人占用id创建返回404且不改写归属")
    @Test
    void givenConversationIdOccupiedByOtherUser_whenCreate_thenNotFoundAndOwnershipKept() throws Exception {
        register("conv_take_a", "abc12345");
        register("conv_take_b", "abc12345");
        approve("conv_take_a");
        approve("conv_take_b");
        MockHttpSession sessionA = login("conv_take_a", "abc12345");
        MockHttpSession sessionB = login("conv_take_b", "abc12345");

        String sharedId = UUID.randomUUID().toString();

        // A 创建会话并保存消息
        mockMvc.perform(post("/api/conversations").session(sessionA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + sharedId + "\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(put("/api/conversations/{id}/messages", sharedId).session(sessionA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"id\":\"m-1\",\"role\":\"user\",\"content\":\"A的私聊\",\"createdAt\":1700000000000}]"))
                .andExpect(status().isOk());

        // B 用同一 id POST → 404（不泄露存在性），不得被 merge 改写归属
        mockMvc.perform(post("/api/conversations").session(sessionB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + sharedId + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ConversationErrorCode.NOT_FOUND));

        // A 的会话仍存在、归属未变、消息未变
        mockMvc.perform(get("/api/conversations").session(sessionA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(sharedId))
                .andExpect(jsonPath("$[0].title").value("A的私聊"));
        mockMvc.perform(get("/api/conversations/{id}/messages", sharedId).session(sessionA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[0].content").value("A的私聊"));

        // B 的列表为空（会话未被接管）
        mockMvc.perform(get("/api/conversations").session(sessionB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @DisplayName("创建会话id结构性校验失败返回400")
    @Test
    void givenBlankOrOversizedConversationId_whenCreate_thenBadRequest() throws Exception {
        register("conv_carol", "abc12345");
        approve("conv_carol");
        MockHttpSession session = login("conv_carol", "abc12345");

        // 空 id 与超长 id 由 Bean Validation 在 web 层拦截（H8），业务层 INVALID_ID 兜底保留
        mockMvc.perform(post("/api/conversations").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("会话 id 不能为空"));
        mockMvc.perform(post("/api/conversations").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + "x".repeat(65) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("会话 id 最长64字符"));
    }

    @DisplayName("保存消息超长content返回400而非500")
    @Test
    void givenOversizedContentOrIllegalRole_whenSaveMessage_thenBadRequestNot500() throws Exception {
        register("conv_dave", "abc12345");
        approve("conv_dave");
        MockHttpSession session = login("conv_dave", "abc12345");
        String convId = UUID.randomUUID().toString();
        mockMvc.perform(post("/api/conversations").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + convId + "\"}"))
                .andExpect(status().isCreated());

        // content 超过 100KB 上限 → 400（H8/B-8：结构性校验在 wire 层 Bean Validation 拦截，code=INVALID_REQUEST）
        String oversized = "x".repeat(100 * 1024 + 1);
        mockMvc.perform(put("/api/conversations/{id}/messages", convId).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"id\":\"m-1\",\"role\":\"user\",\"content\":\"" + oversized
                                + "\",\"createdAt\":1700000000000}]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        // 非法 role → 400（同样由 wire 层 Bean Validation 拦截）
        mockMvc.perform(put("/api/conversations/{id}/messages", convId).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"id\":\"m-1\",\"role\":\"system\",\"content\":\"hi\",\"createdAt\":1700000000000}]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @DisplayName("B8：payload 通道——合法 JSON 落 jsonb 回灌语义等价，非法/超限/user 带载三降级且content保留")
    @Test
    void givenMessagesWithPayload_whenSaveAndFetch_thenRoundTripAndPerMessageDegrade() throws Exception {
        register("conv_payload", "abc12345");
        approve("conv_payload");
        MockHttpSession session = login("conv_payload", "abc12345");
        String convId = UUID.randomUUID().toString();
        mockMvc.perform(post("/api/conversations").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + convId + "\"}"))
                .andExpect(status().isCreated());

        // 合法 payload（trust v1 形态，嵌套+CJK）：PUT → GET 往返。jsonb 落库会规范化文本（键序/空白），
        // 断言按 JSON 树语义等价而非字节相等
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        String payload = "{\"v\":1,\"anchors\":[{\"snippet\":\"贵州茅台收盘价\",\"occ\":1,"
                + "\"tool\":\"quote\",\"state\":\"verified\"}],\"stats\":{\"digits\":2,\"unverified\":0}}";
        String putBody = om.writeValueAsString(java.util.List.of(
                java.util.Map.of("id", "m-1", "role", "user", "content", "问", "createdAt", 1),
                java.util.Map.of("id", "m-2", "role", "assistant", "content", "答",
                        "payload", payload, "createdAt", 2)));
        mockMvc.perform(put("/api/conversations/{id}/messages", convId).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(putBody))
                .andExpect(status().isOk());

        MvcResult fetched = mockMvc.perform(get("/api/conversations/{id}/messages", convId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[0].payload").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.messages[1].id").value("m-2"))
                .andReturn();
        String roundTripped = com.jayway.jsonpath.JsonPath.read(
                fetched.getResponse().getContentAsString(), "$.messages[1].payload");
        org.assertj.core.api.Assertions.assertThat(om.readTree(roundTripped)).isEqualTo(om.readTree(payload));

        // 三降级（默认上限 65536 字节）：user 带载拒收 / 非法 JSON / 超限——单条降级不整批拒，content 全保留
        String oversize = "[\"" + "x".repeat(65540) + "\"]"; // 65544 字节合法 JSON，超 64KB 上限
        String degradeBody = om.writeValueAsString(java.util.List.of(
                java.util.Map.of("id", "m-3", "role", "user", "content", "用户问",
                        "payload", "{\"v\":1}", "createdAt", 3),
                java.util.Map.of("id", "m-4", "role", "assistant", "content", "坏JSON",
                        "payload", "not-json{", "createdAt", 4),
                java.util.Map.of("id", "m-5", "role", "assistant", "content", "超限",
                        "payload", oversize, "createdAt", 5),
                java.util.Map.of("id", "m-6", "role", "assistant", "content", "好条",
                        "payload", "{\"v\":1}", "createdAt", 6)));
        mockMvc.perform(put("/api/conversations/{id}/messages", convId).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(degradeBody))
                .andExpect(status().isOk());

        MvcResult degraded = mockMvc.perform(get("/api/conversations/{id}/messages", convId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages.length()").value(4))
                .andExpect(jsonPath("$.messages[0].payload").value(org.hamcrest.Matchers.nullValue())) // user 拒收
                .andExpect(jsonPath("$.messages[0].content").value("用户问"))
                .andExpect(jsonPath("$.messages[1].payload").value(org.hamcrest.Matchers.nullValue())) // 非法 JSON
                .andExpect(jsonPath("$.messages[1].content").value("坏JSON"))
                .andExpect(jsonPath("$.messages[2].payload").value(org.hamcrest.Matchers.nullValue())) // 超限
                .andExpect(jsonPath("$.messages[2].content").value("超限"))
                .andExpect(jsonPath("$.messages[3].content").value("好条"))
                .andReturn();
        // 同批合法条不受影响（jsonb 落库规范化文本，树比较）
        org.assertj.core.api.Assertions.assertThat(om.readTree((String) com.jayway.jsonpath.JsonPath.read(
                degraded.getResponse().getContentAsString(), "$.messages[3].payload")))
                .isEqualTo(om.readTree("{\"v\":1}"));
    }

    /** 三段式注册：发码（邮件桩取码）→ 携码注册；邮箱由用户名派生保证类内唯一。 */
    private void register(String username, String password) throws Exception {
        String email = username + "@test.local";
        mockMvc.perform(post("/api/auth/register-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password
                                + "\",\"email\":\"" + email + "\"}"))
                .andExpect(status().isOk());
        String code = TestCodes.extractSixDigits(mailStub.sent.get(mailStub.sent.size() - 1).text());
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password
                                + "\",\"email\":\"" + email + "\",\"code\":\"" + code + "\"}"))
                .andExpect(status().isCreated());
    }

    private void approve(String username) {
        var user = userRepository.findByUsername(username).orElseThrow();
        userRepository.save(user.approve());
    }

    private MockHttpSession login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }
}
