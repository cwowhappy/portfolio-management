package com.portfolio.invest.application.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.invest.domain.conversation.ChatMessage;
import com.portfolio.invest.domain.conversation.ChatMessageRole;
import com.portfolio.invest.domain.conversation.ConversationException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 消息 wire → domain 边界校验（逐条拦截超长/非法字段）。 */
class ChatMessageWireTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private static boolean hasViolationOn(Set<? extends ConstraintViolation<?>> violations, String path) {
        return violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals(path));
    }

    @DisplayName("合法消息转换为domain")
    @Test
    void givenValidWire_whenToDomain_thenConvert() {
        var m = new ChatMessageWire("m-1", "assistant", "你好", 1700000000000L).toDomain();

        assertThat(m.id()).isEqualTo("m-1");
        assertThat(m.role()).isEqualTo(ChatMessageRole.ASSISTANT);
        assertThat(m.content()).isEqualTo("你好");
        assertThat(m.createdAtMs()).isEqualTo(1700000000000L);
    }

    @DisplayName("B8：assistant 消息带 payload 时 toDomain 透传")
    @Test
    void givenAssistantPayload_whenToDomain_thenPassThrough() {
        var m = new ChatMessageWire("m-1", "assistant", "你好", "{\"v\":1}", 1700000000000L).toDomain();

        assertThat(m.payload()).isEqualTo("{\"v\":1}");
    }

    @DisplayName("B8：旧四参构造形态 payload 默认 null（存量调用方零改动兼容）")
    @Test
    void givenLegacyFourArgConstructor_whenCreate_thenPayloadNull() {
        var w = new ChatMessageWire("m-1", "user", "hi", 1700000000000L);

        assertThat(w.payload()).isNull();
        assertThat(w.toDomain().payload()).isNull();
    }

    @DisplayName("B8：from 构造时携带 domain payload（GET 回灌）")
    @Test
    void givenDomainWithPayload_whenFrom_thenWireCarriesPayload() {
        var domain = ChatMessage.create(null, "m-1", ChatMessageRole.ASSISTANT, "你好",
                "{\"v\":1}", 1700000000000L);

        assertThat(ChatMessageWire.from(domain).payload()).isEqualTo("{\"v\":1}");
    }

    @DisplayName("wire空id被BeanValidation拦截")
    @Test
    void givenWireBlankId_whenValidate_thenBlockedByBeanValidation() {
        assertThat(hasViolationOn(VALIDATOR.validate(new ChatMessageWire(null, "user", "hi", 1L)), "id")).isTrue();
        assertThat(hasViolationOn(VALIDATOR.validate(new ChatMessageWire("  ", "user", "hi", 1L)), "id")).isTrue();
    }

    @DisplayName("wire非法role被BeanValidation拦截")
    @Test
    void givenWireInvalidRole_whenValidate_thenBlockedByBeanValidation() {
        assertThat(hasViolationOn(VALIDATOR.validate(new ChatMessageWire("m-1", "system", "hi", 1L)), "role")).isTrue();
    }

    @DisplayName("wire空content被BeanValidation拦截")
    @Test
    void givenWireBlankContent_whenValidate_thenBlockedByBeanValidation() {
        assertThat(hasViolationOn(VALIDATOR.validate(new ChatMessageWire("m-1", "user", "", 1L)), "content")).isTrue();
    }

    @DisplayName("id为空白被拒")
    @Test
    void givenBlankId_whenToDomain_thenReject() {
        assertThatThrownBy(() -> new ChatMessageWire("   ", "user", "hi", 1L).toDomain())
                .isInstanceOf(ConversationException.class)
                .hasMessageContaining("id");
    }

    @DisplayName("content为null被拒")
    @Test
    void givenNullContent_whenToDomain_thenReject() {
        assertThatThrownBy(() -> new ChatMessageWire("m-1", "user", null, 1L).toDomain())
                .isInstanceOf(ConversationException.class)
                .hasMessageContaining("不能为空");
    }
}
