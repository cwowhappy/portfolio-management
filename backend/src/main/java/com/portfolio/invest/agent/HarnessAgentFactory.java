package com.portfolio.invest.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.agent.observability.ObservabilityAgentHook;
import com.portfolio.invest.application.skill.SkillApplicationService;
import com.portfolio.invest.agent.trust.TrustAgentHook;
import com.portfolio.invest.agent.trust.TrustWireMessageIdMiddleware;
import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.observability.ObservabilityRecorder;
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
    /** MS-30 B6：轮级观测写端口（turn_observation/tool_invocation_obs，ObservabilityAgentHook 消费）。 */
    private final ObservabilityRecorder observabilityRecorder;
    /** MS-30 B6：工具观测 argsJson 序列化（域端口不引 Jackson，序列化归 agent 侧调用方）。 */
    private final ObjectMapper mapper;

    public HarnessAgentFactory(UserToolkitFactory toolkitFactory,
                               SkillApplicationService skillApplicationService,
                               ClasspathSkillRepository builtInSkillRepository,
                               Model model,
                               InvestProperties props,
                               ObservabilityRecorder observabilityRecorder,
                               ObjectMapper mapper) {
        this.toolkitFactory = toolkitFactory;
        this.skillApplicationService = skillApplicationService;
        this.builtInSkillRepository = builtInSkillRepository;
        this.model = model;
        this.config = props.getMcp().getHarness();
        this.trust = props.getTrust();
        this.observabilityRecorder = observabilityRecorder;
        this.mapper = mapper;
    }

    public HarnessAgent build(Long userId) {
        List<String> enabled = skillApplicationService.enabledSkillCodes(userId);
        // 双 hook 共享同一 TrustAgentHook 实例：观测 hook 读其 lastReport() 组装 trust 并集
        //（priority 保 trust=100 先行、observability=200 后行，见 ObservabilityAgentHook 类注释）
        TrustAgentHook trustHook = new TrustAgentHook(trust);
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
                // MS-29 B5 可信溯源钩子 + MS-30 B6 轮级观测钩子（AG-UI 与飞书共同漏斗）
                // + 线上 messageId 观察中间件（两 hook 的 messageId 同源）
                .hook(trustHook)
                .hook(new ObservabilityAgentHook(observabilityRecorder, trustHook, mapper))
                .middleware(new TrustWireMessageIdMiddleware())
                .build();
    }

    static SkillFilter skillFilter(List<String> enabled) {
        return SkillFilter.only(enabled.toArray(new String[0]));
    }
}
