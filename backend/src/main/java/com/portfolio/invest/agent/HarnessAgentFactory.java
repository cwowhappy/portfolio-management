package com.portfolio.invest.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.agent.observability.ObservabilityAgentHook;
import com.portfolio.invest.agent.report.ReportUsageHook;
import com.portfolio.invest.agent.report.ReportWorkflowPrompt;
import com.portfolio.invest.agent.trust.TrustAgentHook;
import com.portfolio.invest.agent.trust.TrustWireMessageIdMiddleware;
import com.portfolio.invest.application.skill.SkillApplicationService;
import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.observability.ObservabilityRecorder;
import io.agentscope.core.model.Model;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.tool.Toolkit;
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
    /** MS-31 B5：画像注入块构建（AG-UI 与飞书两调用方同走 build——决议 #13 飞书同权自动成立）。 */
    private final ProfileContextBuilder profileContextBuilder;
    /** MS-32 B8：深研执行档（maxItersPerRound，buildReportAgent 消费）。 */
    private final InvestProperties.Report report;

    public HarnessAgentFactory(UserToolkitFactory toolkitFactory,
                               SkillApplicationService skillApplicationService,
                               ClasspathSkillRepository builtInSkillRepository,
                               Model model,
                               InvestProperties props,
                               ObservabilityRecorder observabilityRecorder,
                               ObjectMapper mapper,
                               ProfileContextBuilder profileContextBuilder) {
        this.toolkitFactory = toolkitFactory;
        this.skillApplicationService = skillApplicationService;
        this.builtInSkillRepository = builtInSkillRepository;
        this.model = model;
        this.config = props.getMcp().getHarness();
        this.trust = props.getTrust();
        this.observabilityRecorder = observabilityRecorder;
        this.mapper = mapper;
        this.profileContextBuilder = profileContextBuilder;
        this.report = props.getReport();
    }

    public HarnessAgent build(Long userId) {
        List<String> enabled = skillApplicationService.enabledSkillCodes(userId);
        // 双 hook 共享同一 TrustAgentHook 实例：观测 hook 读其 lastReport() 组装 trust 并集
        //（priority 保 trust=100 先行、observability=200 后行，见 ObservabilityAgentHook 类注释）
        TrustAgentHook trustHook = new TrustAgentHook(trust);
        ObservabilityAgentHook observabilityHook =
                new ObservabilityAgentHook(observabilityRecorder, trustHook, mapper);
        return HarnessAgent.builder()
                .name("invest")
                .sysPrompt(systemPrompt(userId))
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
                .hook(observabilityHook)
                // rc 桥接（2.0.4 RuntimeContextAware 删除的供给替代）：最外层先于 PRE_CALL
                .middleware(new RuntimeContextBridgeMiddleware(
                        trustHook::setRuntimeContext, observabilityHook::setRuntimeContext))
                .middleware(new TrustWireMessageIdMiddleware())
                .build();
    }

    /**
     * 研报任务 Agent 变体（MS-32 B8，B9 编排器消费）：复用 {@link #build(Long)} 全管线，五处差异——
     * ①name=invest-report（区别对话链路，观测/留痕可归因）②sysPrompt 尾接深研指引块
     * （{@link #reportSystemPrompt}，恒注入非 skill）③maxIters 取 report 执行档
     * （invest.report.execution.max-iters-per-round）④toolkit 无 MCP（{@link #reportToolkit}，
     * 天然无 MCP 写工具）⑤追加 {@link ReportUsageHook}（既有双 hook 后，整任务 token 累计）。
     * <strong>对话链路红线：build(Long) 与 systemPrompt(userId) 行为零变更。</strong>
     */
    public HarnessAgent buildReportAgent(Long userId, ReportUsageHook usageHook) {
        List<String> enabled = skillApplicationService.enabledSkillCodes(userId);
        TrustAgentHook trustHook = new TrustAgentHook(trust);
        ObservabilityAgentHook observabilityHook =
                new ObservabilityAgentHook(observabilityRecorder, trustHook, mapper);
        return HarnessAgent.builder()
                .name("invest-report")
                .sysPrompt(reportSystemPrompt(userId))
                .model(model)
                .maxIters(report.getExecution().maxItersPerRound())
                .toolkit(reportToolkit(userId))
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
                // 既有双 hook 原样（trust 先行、观测后行）+ 研报用量钩子追加在后
                .hook(trustHook)
                .hook(observabilityHook)
                .hook(usageHook)
                .middleware(new RuntimeContextBridgeMiddleware(
                        trustHook::setRuntimeContext, observabilityHook::setRuntimeContext,
                        usageHook::setRuntimeContext))
                .middleware(new TrustWireMessageIdMiddleware())
                .build();
    }

    static SkillFilter skillFilter(List<String> enabled) {
        return SkillFilter.only(enabled.toArray(new String[0]));
    }

    /**
     * 系统提示词（MS-31 B5，设计规格 §3.3）：画像块存在时 {@code TEXT + "\n\n" + block}
     * （拼接序固定）；块缺位（授权全关/无数据/构建异常）回落裸 {@link InvestSystemPrompt#TEXT}
     * ——与现状<b>逐字节一致</b>（回归红线，同包测试以 UTF-8 字节比对锁定）。
     */
    String systemPrompt(Long userId) {
        return profileContextBuilder.build(userId)
                .map(block -> InvestSystemPrompt.TEXT + "\n\n" + block)
                .orElseGet(() -> InvestSystemPrompt.TEXT);
    }

    /**
     * MS-32 B8 包私有接缝（红线测试断言口，不内省已构建 agent）：
     * 研报系统提示词 = 对话提示词（含画像块，不吞）+ 空行 + 深研指引块（恒注入，非 skill）。
     */
    String reportSystemPrompt(Long userId) {
        return systemPrompt(userId) + "\n\n" + ReportWorkflowPrompt.BLOCK;
    }

    /** MS-32 B8 包私有接缝（红线测试断言口）：研报工具集 = {@code buildWithoutMcp} 产物（无 MCP 名）。 */
    Toolkit reportToolkit(Long userId) {
        return toolkitFactory.buildWithoutMcp(userId);
    }
}
