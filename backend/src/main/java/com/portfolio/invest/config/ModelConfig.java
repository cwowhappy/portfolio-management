package com.portfolio.invest.config;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** DeepSeek 模型装配：无 DEEPSEEK_API_KEY 时跳过（服务仍可启动，仅行情 API 可用）。 */
@Configuration
public class ModelConfig {

    @Bean
    @ConditionalOnProperty(name = "DEEPSEEK_API_KEY")
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
}
