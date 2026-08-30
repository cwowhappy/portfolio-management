package com.portfolio.invest.domain.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ConversationTest {

    @Test
    void 创建时默认标题与归属() {
        Conversation c = Conversation.create("t-1", 1L, Instant.parse("2026-08-21T00:00:00Z"));
        assertThat(c.id()).isEqualTo("t-1");
        assertThat(c.userId()).isEqualTo(1L);
        assertThat(c.title()).isEqualTo("新会话");
    }

    @Test
    void renameIfDefault设置标题仅一次() {
        Conversation c = Conversation.create("t-1", 1L, Instant.now());
        Conversation named = c.renameIfDefault("帮我看看茅台最近走势");
        assertThat(named.title()).isEqualTo("帮我看看茅台最近走势");
        assertThat(named.renameIfDefault("另一条").title()).isEqualTo("帮我看看茅台最近走势");
    }

    @Test
    void 首条消息为null或空白时保持默认标题() {
        Conversation c = Conversation.create("t-1", 1L, Instant.now());
        assertThat(c.renameIfDefault(null).title()).isEqualTo("新会话");
        assertThat(c.renameIfDefault("   ").title()).isEqualTo("新会话");
    }

    @Test
    void 首条消息超长时标题截断为24字() {
        Conversation c = Conversation.create("t-1", 1L, Instant.now());
        String title = c.renameIfDefault("一二三四五六七八九十一二三四五六七八九十一二三四五").title();
        assertThat(title).hasSize(24);
    }

    @Test
    void touch更新updatedAt() {
        Instant before = Instant.parse("2026-08-21T00:00:00Z");
        Conversation c = Conversation.create("t-1", 1L, before);
        assertThat(c.touch(Instant.parse("2026-08-21T01:00:00Z")).updatedAt())
                .isEqualTo(Instant.parse("2026-08-21T01:00:00Z"));
    }
}
