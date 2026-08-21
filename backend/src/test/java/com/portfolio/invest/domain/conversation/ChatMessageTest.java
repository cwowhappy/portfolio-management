package com.portfolio.invest.domain.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ChatMessageTest {

    @Test
    void 构造与取值() {
        ChatMessage m = ChatMessage.create(null, "m-1", "user", "你好", null, 1700000000000L);
        assertThat(m.id()).isEqualTo("m-1");
        assertThat(m.role()).isEqualTo("user");
        assertThat(m.content()).isEqualTo("你好");
        assertThat(m.createdAtMs()).isEqualTo(1700000000000L);
    }
}
