package com.portfolio.invest.agent;

import com.portfolio.invest.config.InvestProperties;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.tool.Toolkit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 投研 Agent 装配：bean 名与 agent id 均为 invest（与 agentscope.agui.default-agent-id 对应）。
 * 无 DEEPSEEK_API_KEY 时两个 bean 都不创建（服务仍可启动，仅行情 API 可用）。
 * Model 与 Agent 必须放在同一配置类（跨配置类的 ConditionalOnBean 求值顺序不可靠）。
 * 本类位于 agent 包：Agent 装配属于 Agent 能力域，保证 config 包只放配置属性（见 docs/technology/conventions/01-后端DDD分包规范.md）。
 */
@Configuration
public class AgentConfig {

    @Bean
    @ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${DEEPSEEK_API_KEY:}')")
    public Model investModel(InvestProperties props) {
        return ModelRegistry.resolve(
                props.getLlm().getProvider() + ":" + props.getLlm().getModel(),
                ModelCreationContext.builder()
                        .baseUrl(props.getLlm().getBaseUrl())
                        .stream(true)
                        .component(
                                GenerateOptions.class,
                                GenerateOptions.builder()
                                        .parallelToolCalls(false)
                                        .temperature(0.3)
                                        .build())
                        .build());
    }

    @Bean(name = "invest")
    @ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${DEEPSEEK_API_KEY:}')")
    public Agent investAgent(Model investModel, InvestTools investTools) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(investTools);
        return ReActAgent.builder()
                .name("invest")
                .sysPrompt(InvestSystemPrompt.TEXT)
                .model(investModel)
                .toolkit(toolkit)
                .maxIters(10)
                .build();
    }
}
