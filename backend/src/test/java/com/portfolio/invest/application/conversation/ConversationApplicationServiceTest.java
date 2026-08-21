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
import org.junit.jupiter.api.Test;

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

    @Test
    void 列表返回归属会话() {
        when(repo.findByUserId(1L)).thenReturn(List.of(owned("t-1")));
        assertThat(service.list(1L)).hasSize(1).first().satisfies(v -> {
            assertThat(v.id()).isEqualTo("t-1");
            assertThat(v.title()).isEqualTo("新会话");
        });
    }

    @Test
    void 创建校验空id() {
        assertThatThrownBy(() -> service.create(1L, null))
                .isInstanceOf(ConversationException.class).hasMessageContaining("不能为空");
        assertThatThrownBy(() -> service.create(1L, ""))
                .isInstanceOf(ConversationException.class).hasMessageContaining("不能为空");
        assertThatThrownBy(() -> service.create(1L, "  "))
                .isInstanceOf(ConversationException.class).hasMessageContaining("不能为空");
        verify(repo, never()).save(any());
    }

    @Test
    void 创建校验超长id() {
        assertThatThrownBy(() -> service.create(1L, "x".repeat(65)))
                .isInstanceOf(ConversationException.class).hasMessageContaining("格式");
    }

    @Test
    void 创建接受前端非UUID回退id() {
        String tPrefixId = "t-" + System.currentTimeMillis();
        when(repo.findByIdAndUserId(tPrefixId, 1L)).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service.create(1L, tPrefixId);
        verify(repo).save(argThat(c -> c.id().equals(tPrefixId)));
    }

    @Test
    void 读取非本人会话抛NOT_FOUND() {
        when(repo.findByIdAndUserId("t-1", 1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.messages(1L, "t-1"))
                .isInstanceOf(ConversationException.class).hasMessageContaining("不存在");
    }

    @Test
    void 保存消息并生成标题() {
        Conversation conv = owned("t-1");
        when(repo.findByIdAndUserId("t-1", 1L)).thenReturn(Optional.of(conv));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.saveMessages(1L, "t-1", List.of(
                new ChatMessageWire("m-1", "user", "帮我看看茅台最近走势怎么样", 1700000000000L)));

        verify(repo).save(argThat(c -> c.title().equals("帮我看看茅台最近走势怎么样"))); // 前 24 字
        verify(repo).replaceMessages(eq("t-1"), any());
    }

    @Test
    void 非本人会话禁止保存() {
        when(repo.findByIdAndUserId("t-1", 2L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.saveMessages(2L, "t-1", List.of()))
                .isInstanceOf(ConversationException.class).hasMessageContaining("不存在");
        verify(repo, never()).replaceMessages(anyString(), any());
    }

    @Test
    void 删除非本人会话抛NOT_FOUND() {
        when(repo.findByIdAndUserId("t-1", 2L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.delete(2L, "t-1"))
                .isInstanceOf(ConversationException.class).hasMessageContaining("不存在");
        verify(repo, never()).delete(anyString());
    }
}
