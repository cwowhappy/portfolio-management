package com.portfolio.invest.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.util.JacksonJsonCodec;
import io.agentscope.core.util.JsonCodec;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AguiEventNonNullCodecTest {

    /**
     * 生产同形 fixture（#25 病灶路径）：RUN_FINISHED.outcome.interrupts[] 携带权限中断。
     * 注：AguiEvent 是密封接口（permits 仅列内置事件 record），无法自造测试替身实现，
     * 而 2.0.3 的 RunFinished/Interrupt 构造器是 public 的，直接用真实事件。
     */
    private static AguiEvent.RunFinished runFinishedWithInterrupt(String expiresAt) {
        AguiEvent.Interrupt interrupt = new AguiEvent.Interrupt(
                "int-1", "tool_call", "需要人工审批", "call-1", null, expiresAt, null);
        return new AguiEvent.RunFinished(
                "t1", "r1", null, new AguiEvent.RunFinishedInterruptOutcome(List.of(interrupt)));
    }

    record PlainDto(@JsonProperty("name") String name, @JsonProperty("remark") String remark) {}

    private final JsonCodec codec = new AguiEventNonNullCodec(
            new JacksonJsonCodec(),
            new JacksonJsonCodec(new JacksonJsonCodec().getObjectMapper()
                    .copy()
                    .setSerializationInclusion(JsonInclude.Include.NON_NULL)));

    @DisplayName("givenAguiEventWithNullField_whenToJson_thenNullFieldOmitted")
    @Test
    void givenAguiEventWithNullField_whenToJson_thenNullFieldOmitted() {
        AguiEvent.RunFinished event = runFinishedWithInterrupt(null);
        // 对照组：默认 codec 确实会把 "expiresAt":null 写上线（证明用例测的是 #25 病灶，非空转绿）
        assertThat(new JacksonJsonCodec().toJson(event)).contains("\"expiresAt\":null");
        String json = codec.toJson(event);
        assertThat(json).contains("\"threadId\":\"t1\"");
        assertThat(json).contains("\"id\":\"int-1\"");
        assertThat(json).doesNotContain("expiresAt");
        assertThat(json).doesNotContain("\"timestamp\":null");
    }

    @DisplayName("givenAguiEventWithNonNullExpiresAt_whenToJson_thenValueKept")
    @Test
    void givenAguiEventWithNonNullExpiresAt_whenToJson_thenValueKept() {
        String json = codec.toJson(runFinishedWithInterrupt("2026-09-09T00:00:00Z"));
        assertThat(json).contains("\"expiresAt\":\"2026-09-09T00:00:00Z\"");
    }

    @DisplayName("givenNonAguiEvent_whenToJson_thenDelegatesUnchanged")
    @Test
    void givenNonAguiEvent_whenToJson_thenDelegatesUnchanged() {
        JsonCodec plain = new JacksonJsonCodec();
        String viaCodec = codec.toJson(new PlainDto("x", null));
        assertThat(viaCodec).isEqualTo(plain.toJson(new PlainDto("x", null)));
    }
}
