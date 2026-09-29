package com.portfolio.invest.infrastructure.intelligence;

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
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * IntelligenceChatPort 的 AgentScope 实现：复用 agent 包装配的 DeepSeek Model bean
 * （AgentConfig#investModel，@ConditionalOnExpression DEEPSEEK_API_KEY），
 * 经 ObjectProvider 注入——无 key 时 bean 不存在，getIfAvailable() 为 null → 返回 empty，服务照常启动。
 * temperature 固定 0（抽取任务要确定性输出）；任何异常（含 block 超时）吞掉返回 empty + WARN，
 * 绝不向上抛（D4：情报批处理不能因 LLM 故障中断）。
 */
@Component
public class AgentScopeIntelligenceChatPort implements IntelligenceChatPort {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeIntelligenceChatPort.class);
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(120);

    private final ObjectProvider<Model> models;
    private final Duration timeout;

    public AgentScopeIntelligenceChatPort(ObjectProvider<Model> models) {
        this(models, CALL_TIMEOUT);
    }

    /** 测试便利构造：注入短超时以验证超时路径。 */
    AgentScopeIntelligenceChatPort(ObjectProvider<Model> models, Duration timeout) {
        this.models = models;
        this.timeout = timeout;
    }

    @Override
    public Optional<ChatOutcome> complete(String systemPrompt, String userPrompt) {
        try {
            // getIfAvailable 亦在 try 内：Model bean 创建失败（如 ModelRegistry 配置错）同样降级为 empty，绝不抛（D4）
            Model model = models.getIfAvailable();
            if (model == null) {
                log.debug("情报域 LLM 未配置（DEEPSEEK_API_KEY 缺失），本批静默跳过");
                return Optional.empty();
            }
            List<Msg> messages = List.of(
                    Msg.builderForRole(MsgRole.SYSTEM).textContent(systemPrompt).build(),
                    Msg.builderForRole(MsgRole.USER).textContent(userPrompt).build());
            GenerateOptions options = GenerateOptions.builder().temperature(0.0).build();
            List<ChatResponse> chunks = model.stream(messages, List.of(), options)
                    .collectList()
                    .block(timeout);
            if (chunks == null || chunks.isEmpty()) {
                return Optional.empty();
            }
            String text = chunks.stream()
                    .map(ChatResponse::getContent)
                    .filter(Objects::nonNull)
                    .flatMap(List::stream)
                    .filter(TextBlock.class::isInstance)
                    .map(TextBlock.class::cast)
                    .map(TextBlock::getText)
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining());
            long inputTokens = chunks.stream()
                    .map(ChatResponse::getUsage)
                    .filter(Objects::nonNull)
                    .mapToLong(ChatUsage::getInputTokens)
                    .sum();
            return Optional.of(new ChatOutcome(text, inputTokens));
        } catch (Exception e) {
            log.warn("情报域 LLM 调用失败，本批跳过: {}", e.getMessage(), e);
            return Optional.empty();
        }
    }
}
