package com.portfolio.invest.domain.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConversationTest {

    @DisplayName("创建时默认标题与归属")
    @Test
    void whenCreateConversation_thenUseDefaultTitleAndOwnership() {
        Conversation c = Conversation.create("t-1", 1L, Instant.parse("2026-08-21T00:00:00Z"));
        assertThat(c.id()).isEqualTo("t-1");
        assertThat(c.userId()).isEqualTo(1L);
        assertThat(c.title()).isEqualTo("新会话");
    }

    @DisplayName("renameIfDefault设置标题仅一次")
    @Test
    void givenDefaultTitledConversation_whenRenameIfDefault_thenSetTitleOnlyOnce() {
        Conversation c = Conversation.create("t-1", 1L, Instant.now());
        Conversation named = c.renameIfDefault("帮我看看茅台最近走势");
        assertThat(named.title()).isEqualTo("帮我看看茅台最近走势");
        assertThat(named.renameIfDefault("另一条").title()).isEqualTo("帮我看看茅台最近走势");
    }

    @DisplayName("首条消息为null或空白时保持默认标题")
    @Test
    void givenNullOrBlankFirstMessage_whenRenameIfDefault_thenKeepDefaultTitle() {
        Conversation c = Conversation.create("t-1", 1L, Instant.now());
        assertThat(c.renameIfDefault(null).title()).isEqualTo("新会话");
        assertThat(c.renameIfDefault("   ").title()).isEqualTo("新会话");
    }

    @DisplayName("首条消息超长时标题截断为24字")
    @Test
    void givenOverlongFirstMessage_whenRenameIfDefault_thenTruncateTitleTo24Chars() {
        Conversation c = Conversation.create("t-1", 1L, Instant.now());
        String title = c.renameIfDefault("一二三四五六七八九十一二三四五六七八九十一二三四五").title();
        assertThat(title).hasSize(24);
    }

    @DisplayName("标题按码点截断不劈开代理对")
    @Test
    void givenEmojiAtBoundary_whenRenameIfDefault_thenTruncateByCodePointPreservingPairs() {
        Conversation c = Conversation.create("t-1", 1L, Instant.now());
        // 23 个 ASCII 'a' + 1 个 emoji（占 2 个 UTF-16 码元）正好 24 个码点；若按 UTF-16 码元截断会劈开代理对
        String content = "a".repeat(23) + "😀" + "尾部文字";
        String title = c.renameIfDefault(content).title();
        assertThat(title.codePointCount(0, title.length())).isEqualTo(24);
        assertThat(title).isEqualTo("a".repeat(23) + "😀");
    }

    @DisplayName("touch更新updatedAt")
    @Test
    void givenConversation_whenTouch_thenUpdateUpdatedAt() {
        Instant before = Instant.parse("2026-08-21T00:00:00Z");
        Conversation c = Conversation.create("t-1", 1L, before);
        assertThat(c.touch(Instant.parse("2026-08-21T01:00:00Z")).updatedAt())
                .isEqualTo(Instant.parse("2026-08-21T01:00:00Z"));
    }
}
