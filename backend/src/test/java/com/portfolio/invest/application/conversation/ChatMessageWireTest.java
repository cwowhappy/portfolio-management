package com.portfolio.invest.application.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.invest.domain.conversation.ChatMessageRole;
import com.portfolio.invest.domain.conversation.ConversationException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 消息 wire → domain 边界校验（逐条拦截超长/非法字段）。 */
class ChatMessageWireTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private static boolean hasViolationOn(Set<? extends ConstraintViolation<?>> violations, String path) {
        return violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals(path));
    }

    @Test
    void 合法消息转换为domain() {
        var m = new ChatMessageWire("m-1", "assistant", "你好", 1700000000000L).toDomain();

        assertThat(m.id()).isEqualTo("m-1");
        assertThat(m.role()).isEqualTo(ChatMessageRole.ASSISTANT);
        assertThat(m.content()).isEqualTo("你好");
        assertThat(m.createdAtMs()).isEqualTo(1700000000000L);
    }

    @Test
    void wire空id被BeanValidation拦截() {
        assertThat(hasViolationOn(VALIDATOR.validate(new ChatMessageWire(null, "user", "hi", 1L)), "id")).isTrue();
        assertThat(hasViolationOn(VALIDATOR.validate(new ChatMessageWire("  ", "user", "hi", 1L)), "id")).isTrue();
    }

    @Test
    void wire非法role被BeanValidation拦截() {
        assertThat(hasViolationOn(VALIDATOR.validate(new ChatMessageWire("m-1", "system", "hi", 1L)), "role")).isTrue();
    }

    @Test
    void wire空content被BeanValidation拦截() {
        assertThat(hasViolationOn(VALIDATOR.validate(new ChatMessageWire("m-1", "user", "", 1L)), "content")).isTrue();
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
