package com.portfolio.invest.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.agent.observability.ObservabilityAgentMiddleware;
import com.portfolio.invest.agent.report.ReportUsageMiddleware;
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
    /** MS-30 B6：轮级观测写端口（turn_observation/tool_invocation_obs，ObservabilityAgentMiddleware 消费）。 */
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
        return baseBuilder(userId, "invest", systemPrompt(userId), toolkitFactory.build(userId))
                // 线上 messageId 观察中间件（trust 与观测的 messageId 同源），末位追加
                .middleware(new TrustWireMessageIdMiddleware())
                .build();
    }

    /**
     * 研报任务 Agent 变体（MS-32 B8，B9 编排器消费）：复用 {@link #build(Long)} 全管线（公共装配
     * 见 {@link #baseBuilder}），五处差异——①name=invest-report（区别对话链路，观测/留痕可归因）
     * ②sysPrompt 尾接深研指引块（{@link #reportSystemPrompt}，恒注入非 skill）③maxIters 取 report
     * 执行档（invest.report.execution.max-iters-per-round）④toolkit 无 MCP（{@link #reportToolkit}，
     * 天然无 MCP 写工具）⑤追加 {@link ReportUsageMiddleware}（trust hook/观测中间件后，
     * messageId 中间件前，整任务 token 累计）。
     * <strong>对话链路红线：build(Long) 与 systemPrompt(userId) 行为零变更。</strong>
     */
    public HarnessAgent buildReportAgent(Long userId, ReportUsageMiddleware usageMiddleware) {
        return baseBuilder(userId, "invest-report", reportSystemPrompt(userId), reportToolkit(userId))
                .maxIters(report.getExecution().maxItersPerRound())
                // 研报用量中间件追加在观测中间件后、messageId 中间件前
                .middleware(usageMiddleware)
                .middleware(new TrustWireMessageIdMiddleware())
                .build();
    }

    /**
     * 两链公共装配（2026-10-12 审查 B3，消除 build/buildReportAgent 整链复制）：skill 白名单 /
     * trust hook / 观测中间件 / 工作区 / 状态存储 / 压缩 / 记忆 / rc 桥接。差异点
     * （name/sysPrompt/maxIters/toolkit/追加中间件）由调用方在返回的 builder 上覆盖——
     * 新增中间件时两链天然同步，杜绝单链漏改。
     *
     * <p>trust hook 与观测中间件共享同一 TrustAgentHook 实例：观测中间件读其 lastReport() 组装
     * trust 并集（中间件 onAgent 链在核心外侧，完成信号天然晚于核心内 trust POST_CALL，
     * 无 priority——见 ObservabilityAgentMiddleware 类注释）。
     */
    private HarnessAgent.Builder baseBuilder(Long userId, String name, String sysPrompt, Toolkit toolkit) {
        List<String> enabled = skillApplicationService.enabledSkillCodes(userId);
        TrustAgentHook trustHook = new TrustAgentHook(trust);
        ObservabilityAgentMiddleware observability =
                new ObservabilityAgentMiddleware(observabilityRecorder, trustHook, mapper);
        return HarnessAgent.builder()
                .name(name)
                .sysPrompt(sysPrompt)
                .model(model)
                .toolkit(toolkit)
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
                // MS-29 B5 可信溯源钩子 + MS-30 B6 轮级观测中间件（AG-UI 与飞书共同漏斗）
                .hook(trustHook)
                // rc 桥接（2.0.4 RuntimeContextAware 删除的供给替代）：最外层先于 PRE_CALL
                .middleware(new RuntimeContextBridgeMiddleware(trustHook::setRuntimeContext))
                .middleware(observability);
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
