package com.portfolio.invest.application.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.invest.domain.conversation.ConversationException;
import org.junit.jupiter.api.Test;

/** 消息 wire → domain 边界校验（逐条拦截超长/非法字段）。 */
class ChatMessageWireTest {

    @Test
    void 合法消息转换为domain() {
        var m = new ChatMessageWire("m-1", "assistant", "你好", 1700000000000L).toDomain();

        assertThat(m.id()).isEqualTo("m-1");
        assertThat(m.role()).isEqualTo("assistant");
        assertThat(m.content()).isEqualTo("你好");
        assertThat(m.createdAtMs()).isEqualTo(1700000000000L);
    }

    @Test
    void id为空白被拒() {
        assertThatThrownBy(() -> new ChatMessageWire("   ", "user", "hi", 1L).toDomain())
                .isInstanceOf(ConversationException.class)
                .hasMessageContaining("id");
    }

    @Test
    void content为null被拒() {
        assertThatThrownBy(() -> new ChatMessageWire("m-1", "user", null, 1L).toDomain())
                .isInstanceOf(ConversationException.class)
                .hasMessageContaining("不能为空");
    }
}
