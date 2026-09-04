package com.portfolio.invest.domain.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ChatMessageTest {

    @DisplayName("构造与取值")
    @Test
    void constructsAndExposesValues() {
        ChatMessage m = ChatMessage.create(null, "m-1", ChatMessageRole.USER, "你好", null, 1700000000000L);
        assertThat(m.id()).isEqualTo("m-1");
        assertThat(m.role()).isEqualTo(ChatMessageRole.USER);
        assertThat(m.content()).isEqualTo("你好");
        assertThat(m.createdAtMs()).isEqualTo(1700000000000L);
    }
}
