package com.portfolio.invest.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.invest.domain.user.UserRepository;
import com.portfolio.invest.support.PostgresTestSupport;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import com.portfolio.invest.domain.conversation.ConversationErrorCode;

@SpringBootTest
@AutoConfigureMockMvc
class ConversationControllerIntegrationTest extends PostgresTestSupport {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;

    @Test
    void 登录用户建会话写读列_非本人404_未登录401() throws Exception {
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

        // A 保存消息 → 204，并生成标题
        mockMvc.perform(put("/api/conversations/{id}/messages", convA).session(sessionA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"id\":\"m-1\",\"role\":\"user\",\"content\":\"你好\",\"createdAt\":1700000000000}]"))
                .andExpect(status().isNoContent());

        // A 读回消息
        mockMvc.perform(get("/api/conversations/{id}/messages", convA).session(sessionA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("m-1"))
                .andExpect(jsonPath("$[0].role").value("user"))
                .andExpect(jsonPath("$[0].content").value("你好"));

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

    @Test
    void 他人占用id创建返回404且不改写归属() throws Exception {
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
                .andExpect(status().isNoContent());

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
                .andExpect(jsonPath("$[0].content").value("A的私聊"));

        // B 的列表为空（会话未被接管）
        mockMvc.perform(get("/api/conversations").session(sessionB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void 创建会话id结构性校验失败返回400() throws Exception {
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

    @Test
    void 保存消息超长content返回400而非500() throws Exception {
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

    private void register(String username, String password) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
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
