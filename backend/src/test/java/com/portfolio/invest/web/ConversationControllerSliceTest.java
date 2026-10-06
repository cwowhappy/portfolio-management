package com.portfolio.invest.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.invest.application.conversation.ChatMessageWire;
import com.portfolio.invest.application.conversation.ConversationApplicationService;
import com.portfolio.invest.application.conversation.ConversationMessagesView;
import com.portfolio.invest.application.conversation.ConversationSaveResult;
import com.portfolio.invest.domain.user.User;
import com.portfolio.invest.domain.user.UserRole;
import com.portfolio.invest.domain.user.UserStatus;
import com.portfolio.invest.infrastructure.security.AuthenticatedUser;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.mockito.ArgumentCaptor;
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

    @DisplayName("保存合法消息返回200并暴露updatedAt，未带If-Match时基准为null")
    @Test
    void givenValidMessage_whenSaveMessages_thenReturn200WithUpdatedAt() throws Exception {
        when(service.saveMessages(any(), any(), any(), any()))
                .thenReturn(new ConversationSaveResult(Instant.parse("2026-08-21T00:00:00Z")));

        mvc.perform(put("/api/conversations/t-1/messages").with(authentication(auth())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(message("m-1", "user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedAt").value("2026-08-21T00:00:00Z"));
        verify(service).saveMessages(eq(1L), eq("t-1"), any(), isNull());
    }

    @DisplayName("携带If-Match头时解析为Instant校验基准透传服务层")
    @Test
    void givenIfMatchHeader_whenSaveMessages_thenPassParsedInstant() throws Exception {
        when(service.saveMessages(any(), any(), any(), any()))
                .thenReturn(new ConversationSaveResult(Instant.parse("2026-08-21T00:00:00Z")));

        mvc.perform(put("/api/conversations/t-1/messages").with(authentication(auth())).with(csrf())
                        .header("If-Match", "2026-08-20T12:34:56Z")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(message("m-1", "user")))
                .andExpect(status().isOk());
        verify(service).saveMessages(eq(1L), eq("t-1"), any(), eq(Instant.parse("2026-08-20T12:34:56Z")));
    }

    @DisplayName("非法If-Match头视为未携带（向后兼容无条件更新）")
    @Test
    void givenMalformedIfMatchHeader_whenSaveMessages_thenTreatedAsAbsent() throws Exception {
        when(service.saveMessages(any(), any(), any(), any()))
                .thenReturn(new ConversationSaveResult(Instant.parse("2026-08-21T00:00:00Z")));

        mvc.perform(put("/api/conversations/t-1/messages").with(authentication(auth())).with(csrf())
                        .header("If-Match", "not-a-timestamp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(message("m-1", "user")))
                .andExpect(status().isOk());
        verify(service).saveMessages(eq(1L), eq("t-1"), any(), isNull());
    }

    @DisplayName("保存非法role消息返回400")
    @Test
    void givenInvalidRoleMessage_whenSaveMessages_thenReturn400() throws Exception {
        mvc.perform(put("/api/conversations/t-1/messages").with(authentication(auth())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(message("m-1", "system")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @DisplayName("保存空id消息返回400")
    @Test
    void givenMessageWithNullId_whenSaveMessages_thenReturn400() throws Exception {
        mvc.perform(put("/api/conversations/t-1/messages").with(authentication(auth())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(message(null, "user")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @DisplayName("B8：PUT 消息含 payload 字段时绑定到 wire.payload，缺省时为 null")
    @Test
    void givenPayloadFieldInPutBody_whenSaveMessages_thenBindsToWire() throws Exception {
        when(service.saveMessages(any(), any(), any(), any()))
                .thenReturn(new ConversationSaveResult(Instant.parse("2026-08-21T00:00:00Z")));

        mvc.perform(put("/api/conversations/t-1/messages").with(authentication(auth())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"id\":\"m-1\",\"role\":\"assistant\",\"content\":\"hi\","
                                + "\"payload\":\"{\\\"v\\\":1}\",\"createdAt\":1}]"))
                .andExpect(status().isOk());
        ArgumentCaptor<List<ChatMessageWire>> withPayload = ArgumentCaptor.captor();
        verify(service).saveMessages(eq(1L), eq("t-1"), withPayload.capture(), isNull());
        assertThat(withPayload.getValue().get(0).payload()).isEqualTo("{\"v\":1}");

        mvc.perform(put("/api/conversations/t-1/messages").with(authentication(auth())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(message("m-1", "assistant")))
                .andExpect(status().isOk());
        ArgumentCaptor<List<ChatMessageWire>> withoutPayload = ArgumentCaptor.captor();
        verify(service, times(2)).saveMessages(eq(1L), eq("t-1"), withoutPayload.capture(), isNull());
        assertThat(withoutPayload.getValue().get(0).payload()).isNull();
    }

    @DisplayName("B8：GET 消息视图序列化 payload 字段（携带原样、缺省为 null）")
    @Test
    void givenMessagesViewWithPayload_whenGetMessages_thenPayloadSerialized() throws Exception {
        var withPayload = new ConversationMessagesView(
                Instant.parse("2026-08-21T00:00:00Z"),
                List.of(new ChatMessageWire("m-1", "assistant", "hi", "{\"v\":1}", 1L)));
        when(service.messages(any(), eq("t-1"))).thenReturn(withPayload);

        mvc.perform(get("/api/conversations/t-1/messages").with(authentication(auth())).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[0].payload").value("{\"v\":1}"));

        var withoutPayload = new ConversationMessagesView(
                Instant.parse("2026-08-21T00:00:00Z"),
                List.of(new ChatMessageWire("m-2", "user", "hi", 1L)));
        when(service.messages(any(), eq("t-1"))).thenReturn(withoutPayload);

        mvc.perform(get("/api/conversations/t-1/messages").with(authentication(auth())).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[0].payload").value(org.hamcrest.Matchers.nullValue()));
    }
}
