package com.portfolio.invest.agent;

import com.portfolio.invest.config.InvestProperties;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import java.nio.file.Paths;
import org.springframework.stereotype.Component;

@Component
public class HarnessAgentFactory {
    private final UserToolkitFactory toolkitFactory;
    private final Model model;
    private final InvestProperties.Mcp.Harness config;

    public HarnessAgentFactory(UserToolkitFactory toolkitFactory, Model model, InvestProperties props) {
        this.toolkitFactory = toolkitFactory;
        this.model = model;
        this.config = props.getMcp().getHarness();
    }

    public HarnessAgent build(Long userId) {
        return HarnessAgent.builder()
                .name("invest")
                .sysPrompt(InvestSystemPrompt.TEXT)
                .model(model)
                .toolkit(toolkitFactory.build(userId))
                .workspace(Paths.get(config.getWorkspace()))
                .stateStore(new JsonFileAgentStateStore(Paths.get(config.getStateRoot())))
                .compaction(CompactionConfig.builder()
                        .triggerMessages(config.getCompaction().getTriggerMessages())
                        .keepMessages(config.getCompaction().getKeepMessages())
                        .flushBeforeCompact(config.getCompaction().isFlushBeforeCompact())
                        .build())
                .memory(MemoryConfig.builder()
                        .flushTrigger(MemoryConfig.FlushTrigger.throttled(config.getMemory().getFlushMinGap()))
                        .build())
                .build();
    }
}
