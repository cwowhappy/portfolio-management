package com.portfolio.invest.infrastructure.intelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.invest.application.intelligence.IntelligenceChatPort;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

class AgentScopeIntelligenceChatPortTest {

    @SuppressWarnings("unchecked")
    private final ObjectProvider<Model> models = mock(ObjectProvider.class);

    private final Model model = mock(Model.class);

    /** 构造单个 ChatResponse chunk：text 非空则带一个 TextBlock，usage 可为 null（流式中间 chunk 无 usage）。 */
    private static ChatResponse chunk(String text, ChatUsage usage) {
        ChatResponse resp = mock(ChatResponse.class);
        when(resp.getContent())
                .thenReturn(text == null ? List.of() : List.of(TextBlock.builder().text(text).build()));
        when(resp.getUsage()).thenReturn(usage);
        return resp;
    }

    private static ChatUsage usage(int inputTokens) {
        ChatUsage usage = mock(ChatUsage.class);
        when(usage.getInputTokens()).thenReturn(inputTokens);
        return usage;
    }

    @DisplayName("有 Model：返回文本与 input tokens，消息为 system+user，temperature=0")
    @Test
    void givenModelPresent_whenComplete_thenReturnsTextTokensAndZeroTemperature() {
        when(models.getIfAvailable()).thenReturn(model);
        // chunk 须在 when(model.stream(...)) 之前构造：thenReturn 实参里再 stub 其他 mock 会触发
        // Mockito UnfinishedStubbing（stubbing-inside-stubbing）
        ChatResponse resp = chunk("{\"items\":[]}", usage(1234));
        when(model.stream(anyList(), anyList(), any())).thenReturn(Flux.just(resp));

        Optional<IntelligenceChatPort.ChatOutcome> out =
                new AgentScopeIntelligenceChatPort(models).complete("系统提示", "用户提示");

        assertThat(out).isPresent();
        assertThat(out.get().text()).isEqualTo("{\"items\":[]}");
        assertThat(out.get().inputTokens()).isEqualTo(1234L);
        ArgumentCaptor<List<Msg>> msgs = ArgumentCaptor.captor();
        ArgumentCaptor<GenerateOptions> options = ArgumentCaptor.captor();
        verify(model).stream(msgs.capture(), anyList(), options.capture());
        assertThat(msgs.getValue()).hasSize(2);
        assertThat(msgs.getValue().get(0).getRole()).isEqualTo(MsgRole.SYSTEM);
        assertThat(msgs.getValue().get(0).getTextContent()).isEqualTo("系统提示");
        assertThat(msgs.getValue().get(1).getRole()).isEqualTo(MsgRole.USER);
        assertThat(msgs.getValue().get(1).getTextContent()).isEqualTo("用户提示");
        assertThat(options.getValue().getTemperature()).isZero();
    }

    @DisplayName("流式多 chunk：文本按序拼接，仅累计非 null usage 的 input tokens")
    @Test
    void givenMultipleChunks_whenComplete_thenConcatenatesTextAndSumsNonNullUsage() {
        when(models.getIfAvailable()).thenReturn(model);
        ChatResponse mid = chunk("{\"title\":", null);
        ChatResponse last = chunk("\"x\"}", usage(1234));
        when(model.stream(anyList(), anyList(), any())).thenReturn(Flux.just(mid, last));

        Optional<IntelligenceChatPort.ChatOutcome> out =
                new AgentScopeIntelligenceChatPort(models).complete("s", "u");

        assertThat(out).isPresent();
        assertThat(out.get().text()).isEqualTo("{\"title\":\"x\"}");
        assertThat(out.get().inputTokens()).isEqualTo(1234L);
    }

    @DisplayName("无 Model（未配置 DEEPSEEK_API_KEY）：返回 empty 且不发起调用")
    @Test
    void givenNoModel_whenComplete_thenEmptyWithoutCallingStream() {
        when(models.getIfAvailable()).thenReturn(null);

        Optional<IntelligenceChatPort.ChatOutcome> out =
                new AgentScopeIntelligenceChatPort(models).complete("s", "u");

        assertThat(out).isEmpty();
        verify(model, never()).stream(anyList(), anyList(), any());
    }

    @DisplayName("stream 抛异常：吞掉返回 empty，绝不向上抛（D4）")
    @Test
    void givenStreamError_whenComplete_thenReturnsEmpty() {
        when(models.getIfAvailable()).thenReturn(model);
        when(model.stream(anyList(), anyList(), any()))
                .thenReturn(Flux.error(new RuntimeException("boom")));

        Optional<IntelligenceChatPort.ChatOutcome> out =
                new AgentScopeIntelligenceChatPort(models).complete("s", "u");

        assertThat(out).isEmpty();
    }

    @DisplayName("调用超时（block 超时）：吞掉返回 empty，绝不向上抛")
    @Test
    void givenNeverEmittingStream_whenComplete_thenTimesOutToEmpty() {
        when(models.getIfAvailable()).thenReturn(model);
        when(model.stream(anyList(), anyList(), any())).thenReturn(Flux.never());

        Optional<IntelligenceChatPort.ChatOutcome> out =
                new AgentScopeIntelligenceChatPort(models, Duration.ofMillis(50)).complete("s", "u");

        assertThat(out).isEmpty();
    }

    @DisplayName("响应无 usage：文本照常返回，inputTokens 记 0")
    @Test
    void givenResponseWithoutUsage_whenComplete_thenZeroTokens() {
        when(models.getIfAvailable()).thenReturn(model);
        ChatResponse resp = chunk("文本", null);
        when(model.stream(anyList(), anyList(), any())).thenReturn(Flux.just(resp));

        Optional<IntelligenceChatPort.ChatOutcome> out =
                new AgentScopeIntelligenceChatPort(models).complete("s", "u");

        assertThat(out).isPresent();
        assertThat(out.get().text()).isEqualTo("文本");
        assertThat(out.get().inputTokens()).isZero();
    }

    @DisplayName("空流（无任何 chunk）：返回 empty")
    @Test
    void givenEmptyStream_whenComplete_thenEmpty() {
        when(models.getIfAvailable()).thenReturn(model);
        when(model.stream(anyList(), anyList(), any())).thenReturn(Flux.empty());

        Optional<IntelligenceChatPort.ChatOutcome> out =
                new AgentScopeIntelligenceChatPort(models).complete("s", "u");

        assertThat(out).isEmpty();
    }

    @DisplayName("Model bean 解析失败（getIfAvailable 抛异常）：同样降级 empty，绝不抛（D4）")
    @Test
    void givenBeanResolutionError_whenComplete_thenReturnsEmpty() {
        when(models.getIfAvailable()).thenThrow(new BeanCreationException("bean 创建失败"));

        Optional<IntelligenceChatPort.ChatOutcome> out =
                new AgentScopeIntelligenceChatPort(models).complete("s", "u");

        assertThat(out).isEmpty();
    }
}
