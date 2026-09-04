package com.portfolio.invest.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.invest.application.conversation.ConversationApplicationService;
import com.portfolio.invest.domain.user.User;
import com.portfolio.invest.domain.user.UserRole;
import com.portfolio.invest.domain.user.UserStatus;
import com.portfolio.invest.infrastructure.security.AuthenticatedUser;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 会话 REST 切片：saveMessages 的 List<ChatMessageWire> Bean Validation（逐条结构性校验）。 */
@WebMvcTest(ConversationController.class)
class ConversationControllerSliceTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ConversationApplicationService service;

    private Authentication auth() {
        var user = User.reconstitute(1L, "u", "p", UserRole.USER, UserStatus.APPROVED, true,
                Instant.now(), Instant.now());
        var principal = new AuthenticatedUser(user);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    private String message(String id, String role) {
        return "[{\"id\":" + (id == null ? "null" : "\"" + id + "\"")
                + ",\"role\":\"" + role + "\",\"content\":\"hi\",\"createdAt\":1}]";
    }

    @DisplayName("保存合法消息返回204")
    @Test
    void saveValidMessagesReturns204() throws Exception {
        mvc.perform(put("/api/conversations/t-1/messages").with(authentication(auth())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(message("m-1", "user")))
                .andExpect(status().isNoContent());
        verify(service).saveMessages(eq(1L), eq("t-1"), any());
    }

    @DisplayName("保存非法role消息返回400")
    @Test
    void saveInvalidRoleMessageReturns400() throws Exception {
        mvc.perform(put("/api/conversations/t-1/messages").with(authentication(auth())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(message("m-1", "system")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @DisplayName("保存空id消息返回400")
    @Test
    void saveMessageWithNullIdReturns400() throws Exception {
        mvc.perform(put("/api/conversations/t-1/messages").with(authentication(auth())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(message(null, "user")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
}
