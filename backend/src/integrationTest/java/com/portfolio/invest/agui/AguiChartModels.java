package com.portfolio.invest.agui;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import reactor.core.publisher.Flux;

/**
 * 双通道集成测试（AguiChartIntegrationTest）的测试替身：脚本化假 Model 与工具调用助手。
 *
 * <p>与 McpHitlIntegrationTest 的同名实现同构。该类是私有的且无共享基类，为最小改动
 * 不去改既有测试文件，照抄至此（含 {@code tools == null || isMemoryMaintenance} 记忆归档守卫：
 * harness 记忆归档/整合以 tools=null 直呼 Model，会偷走脚本化回复，须识别后返回固定文本）。
 */
final class AguiChartModels {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 静态单例：TestBean 工厂先于用例体执行，脚本在用例内下发。 */
    static final ScriptedModel MODEL = new ScriptedModel();

    private AguiChartModels() {}

    /** input 与 content 必须同时提供（ToolExecutor 参数校验读 content，见 AguiInterruptIntegrationTest 注释）。 */
    static ToolUseBlock toolCall(String callId, String name, Map<String, Object> input) {
        return new ToolUseBlock(callId, name, input, MAPPER.valueToTree(input).toString(), Map.of());
    }

    /** 脚本化假 Model：按队列顺序返回预设内容块，耗尽后兜底纯文本。 */
    static class ScriptedModel implements Model {
        private final Queue<List<ContentBlock>> replies = new ConcurrentLinkedQueue<>();
        private final AtomicLong seq = new AtomicLong();

        @SafeVarargs
        final void script(List<ContentBlock>... scripted) {
            replies.clear();
            replies.addAll(Arrays.asList(scripted));
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            // harness 记忆归档/整合以 tools=null 直呼 Model，会偷走脚本化回复：识别后返回固定文本
            if (tools == null || isMemoryMaintenance(messages)) {
                return textResponse("（无新增记忆）");
            }
            List<ContentBlock> content = replies.poll();
            if (content == null) {
                content = List.of(TextBlock.builder().text("脚本耗尽，兜底文本。").build());
            }
            boolean hasToolCall = content.stream().anyMatch(b -> b instanceof ToolUseBlock);
            return Flux.just(ChatResponse.builder()
                    .id("scripted-" + seq.incrementAndGet())
                    .content(content)
                    .usage(new ChatUsage(10, 5, 0.01))
                    .finishReason(hasToolCall ? "tool_calls" : "stop")
                    .build());
        }

        private static boolean isMemoryMaintenance(List<Msg> messages) {
            return messages.stream().anyMatch(m -> {
                String t = m.getTextContent();
                return t != null && t.contains("Extract NEW memories");
            });
        }

        private Flux<ChatResponse> textResponse(String text) {
            return Flux.just(ChatResponse.builder()
                    .id("scripted-mem-" + seq.incrementAndGet())
                    .content(List.of(TextBlock.builder().text(text).build()))
                    .usage(new ChatUsage(1, 1, 0.0))
                    .finishReason("stop")
                    .build());
        }

        @Override
        public String getModelName() {
            return "scripted-model";
        }
    }
}
