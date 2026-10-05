package com.portfolio.invest.agent;

import com.portfolio.invest.application.skill.SkillApplicationService;
import com.portfolio.invest.agent.trust.TrustAgentHook;
import com.portfolio.invest.agent.trust.TrustWireMessageIdMiddleware;
import com.portfolio.invest.config.InvestProperties;
import io.agentscope.core.model.Model;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import java.nio.file.Paths;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${DEEPSEEK_API_KEY:}')")
public class HarnessAgentFactory {
    private final UserToolkitFactory toolkitFactory;
    private final SkillApplicationService skillApplicationService;
    private final ClasspathSkillRepository builtInSkillRepository;
    private final Model model;
    private final InvestProperties.Mcp.Harness config;
    /** MS-29 B5：可信溯源设置（容差/修正上限，TrustAgentHook 消费）。 */
    private final InvestProperties.Trust trust;

    public HarnessAgentFactory(UserToolkitFactory toolkitFactory,
                               SkillApplicationService skillApplicationService,
                               ClasspathSkillRepository builtInSkillRepository,
                               Model model,
                               InvestProperties props) {
        this.toolkitFactory = toolkitFactory;
        this.skillApplicationService = skillApplicationService;
        this.builtInSkillRepository = builtInSkillRepository;
        this.model = model;
        this.config = props.getMcp().getHarness();
        this.trust = props.getTrust();
    }

    public HarnessAgent build(Long userId) {
        List<String> enabled = skillApplicationService.enabledSkillCodes(userId);
        return HarnessAgent.builder()
                .name("invest")
                .sysPrompt(InvestSystemPrompt.TEXT)
                .model(model)
                .toolkit(toolkitFactory.build(userId))
                .skillRepository(builtInSkillRepository)
                .skillFilter(skillFilter(enabled))
                .disableDefaultWorkspaceSkills()
                .disableDynamicSkills()
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
                // MS-29 B5：可信溯源回合钩子 + 线上 messageId 观察中间件（AG-UI 与飞书共同漏斗）
                .hook(new TrustAgentHook(trust))
                .middleware(new TrustWireMessageIdMiddleware())
                .build();
    }

    static SkillFilter skillFilter(List<String> enabled) {
        return SkillFilter.only(enabled.toArray(new String[0]));
    }
}
