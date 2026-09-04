package com.portfolio.invest.application.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.invest.domain.conversation.Conversation;
import com.portfolio.invest.domain.conversation.ConversationException;
import com.portfolio.invest.domain.conversation.ConversationRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.portfolio.invest.domain.conversation.ConversationErrorCode;

class ConversationApplicationServiceTest {

    private final ConversationRepository repo = mock(ConversationRepository.class);
    private ConversationApplicationService service;

    @BeforeEach
    void setUp() {
        service = new ConversationApplicationService(repo);
    }

    private Conversation owned(String id) {
        return Conversation.create(id, 1L, Instant.parse("2026-08-21T00:00:00Z"));
    }

    @DisplayName("列表返回归属会话")
    @Test
    void listReturnsOwnedConversations() {
        when(repo.findByUserId(1L)).thenReturn(List.of(owned("t-1")));
        assertThat(service.list(1L)).hasSize(1).first().satisfies(v -> {
            assertThat(v.id()).isEqualTo("t-1");
            assertThat(v.title()).isEqualTo("新会话");
        });
    }

    @DisplayName("创建校验空id")
    @Test
    void createRejectsEmptyId() {
        assertThatThrownBy(() -> service.create(1L, null))
                .isInstanceOf(ConversationException.class).hasMessageContaining("不能为空");
        assertThatThrownBy(() -> service.create(1L, ""))
                .isInstanceOf(ConversationException.class).hasMessageContaining("不能为空");
        assertThatThrownBy(() -> service.create(1L, "  "))
                .isInstanceOf(ConversationException.class).hasMessageContaining("不能为空");
        verify(repo, never()).save(any());
    }

    @DisplayName("创建校验超长id")
    @Test
    void createRejectsOversizedId() {
        assertThatThrownBy(() -> service.create(1L, "x".repeat(65)))
                .isInstanceOf(ConversationException.class).hasMessageContaining("格式");
    }

    @DisplayName("创建接受前端非UUID回退id")
    @Test
    void createAcceptsFrontendNonUuidFallbackId() {
        String tPrefixId = "t-" + System.currentTimeMillis();
        when(repo.findByIdAndUserId(tPrefixId, 1L)).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service.create(1L, tPrefixId);
        verify(repo).save(argThat(c -> c.id().equals(tPrefixId)));
    }

    @DisplayName("本人重复创建同id幂等返回")
    @Test
    void createSameIdForOwnerIsIdempotent() {
        when(repo.findByIdAndUserId("t-1", 1L)).thenReturn(Optional.of(owned("t-1")));

        var view = service.create(1L, "t-1");

        assertThat(view.id()).isEqualTo("t-1");
        verify(repo, never()).save(any());
        verify(repo, never()).existsById(anyString());
    }

    @DisplayName("创建时id已被他人占用抛NOT_FOUND且不save")
    @Test
    void createIdTakenByOthersThrowsNotFoundAndDoesNotSave() {
        when(repo.findByIdAndUserId("t-1", 2L)).thenReturn(Optional.empty());
        when(repo.existsById("t-1")).thenReturn(true);
        assertThatThrownBy(() -> service.create(2L, "t-1"))
                .isInstanceOf(ConversationException.class).hasMessageContaining("不存在");
        verify(repo, never()).save(any());
    }

    @DisplayName("创建时id未被任何人占用则save")
    @Test
    void createIdAvailableSaves() {
        when(repo.findByIdAndUserId("t-new", 1L)).thenReturn(Optional.empty());
        when(repo.existsById("t-new")).thenReturn(false);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service.create(1L, "t-new");
        verify(repo).existsById("t-new");
        verify(repo).save(argThat(c -> c.id().equals("t-new") && c.userId().equals(1L)));
    }

    @DisplayName("读取非本人会话抛NOT_FOUND")
    @Test
    void readOthersConversationThrowsNotFound() {
        when(repo.findByIdAndUserId("t-1", 1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.messages(1L, "t-1"))
                .isInstanceOf(ConversationException.class).hasMessageContaining("不存在");
    }

    @DisplayName("保存消息并生成标题")
    @Test
    void saveMessagesGeneratesTitle() {
        Conversation conv = owned("t-1");
        when(repo.findByIdAndUserId("t-1", 1L)).thenReturn(Optional.of(conv));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.saveMessages(1L, "t-1", List.of(
                new ChatMessageWire("m-1", "user", "帮我看看茅台最近走势怎么样", 1700000000000L)));

        verify(repo).save(argThat(c -> c.title().equals("帮我看看茅台最近走势怎么样"))); // 前 24 字
        verify(repo).replaceMessages(eq("t-1"), any());
    }

    @DisplayName("非本人会话禁止保存")
    @Test
    void saveToOthersConversationRejected() {
        when(repo.findByIdAndUserId("t-1", 2L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.saveMessages(2L, "t-1", List.of()))
                .isInstanceOf(ConversationException.class).hasMessageContaining("不存在");
        verify(repo, never()).replaceMessages(anyString(), any());
    }

    @DisplayName("保存消息校验非法role")
    @Test
    void saveMessagesRejectsInvalidRole() {
        when(repo.findByIdAndUserId("t-1", 1L)).thenReturn(Optional.of(owned("t-1")));
        assertThatThrownBy(() -> service.saveMessages(1L, "t-1", List.of(
                new ChatMessageWire("m-1", "system", "hi", 1700000000000L))))
                .isInstanceOf(ConversationException.class)
                .satisfies(e -> assertThat(((ConversationException) e).getCode()).isEqualTo(ConversationErrorCode.INVALID_MESSAGE));
        verify(repo, never()).replaceMessages(anyString(), any());
    }

    @DisplayName("保存消息校验id为空或超长")
    @Test
    void saveMessagesRejectsBlankOrOversizedId() {
        when(repo.findByIdAndUserId("t-1", 1L)).thenReturn(Optional.of(owned("t-1")));
        assertThatThrownBy(() -> service.saveMessages(1L, "t-1", List.of(
                new ChatMessageWire(null, "user", "hi", 1700000000000L))))
                .isInstanceOf(ConversationException.class).hasMessageContaining("id");
        assertThatThrownBy(() -> service.saveMessages(1L, "t-1", List.of(
                new ChatMessageWire("m".repeat(65), "user", "hi", 1700000000000L))))
                .isInstanceOf(ConversationException.class).hasMessageContaining("id");
        verify(repo, never()).replaceMessages(anyString(), any());
    }

    @DisplayName("保存消息校验content为空或超长")
    @Test
    void saveMessagesRejectsBlankOrOversizedContent() {
        when(repo.findByIdAndUserId("t-1", 1L)).thenReturn(Optional.of(owned("t-1")));
        assertThatThrownBy(() -> service.saveMessages(1L, "t-1", List.of(
                new ChatMessageWire("m-1", "user", "", 1700000000000L))))
                .isInstanceOf(ConversationException.class).hasMessageContaining("不能为空");
        assertThatThrownBy(() -> service.saveMessages(1L, "t-1", List.of(
                new ChatMessageWire("m-1", "user", "x".repeat(100 * 1024 + 1), 1700000000000L))))
                .isInstanceOf(ConversationException.class).hasMessageContaining("超长");
        verify(repo, never()).replaceMessages(anyString(), any());
    }

    @DisplayName("保存消息条数超过500被拒")
    @Test
    void saveMessagesOver500CountRejected() {
        when(repo.findByIdAndUserId("t-1", 1L)).thenReturn(Optional.of(owned("t-1")));
        var wires = java.util.stream.IntStream.range(0, 501)
                .mapToObj(i -> new ChatMessageWire("m-" + i, "user", "hi", 1700000000000L))
                .toList();
        assertThatThrownBy(() -> service.saveMessages(1L, "t-1", wires))
                .isInstanceOf(ConversationException.class)
                .satisfies(e -> assertThat(((ConversationException) e).getCode()).isEqualTo(ConversationErrorCode.INVALID_MESSAGE));
        verify(repo, never()).replaceMessages(anyString(), any());
    }

    @DisplayName("删除非本人会话抛NOT_FOUND")
    @Test
    void deleteOthersConversationThrowsNotFound() {
        when(repo.findByIdAndUserId("t-1", 2L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.delete(2L, "t-1"))
                .isInstanceOf(ConversationException.class).hasMessageContaining("不存在");
        verify(repo, never()).delete(anyString());
    }
}
