package com.portfolio.invest.config;

import com.portfolio.invest.agent.InvestSystemPrompt;
import com.portfolio.invest.agent.InvestTools;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 投研 Agent 装配：bean 名与 agent id 均为 invest（与 agentscope.agui.default-agent-id 对应）。 */
@Configuration
@ConditionalOnBean(name = "investModel")
public class AgentConfig {

    @Bean(name = "invest")
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
