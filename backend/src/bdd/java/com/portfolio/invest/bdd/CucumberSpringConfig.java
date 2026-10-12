package com.portfolio.invest.bdd;

import com.portfolio.invest.agent.report.ReportAgentRunner;
import com.portfolio.invest.agent.report.ReportAgentRunnerFactory;
import com.portfolio.invest.agent.report.ReportUsageMiddleware;
import com.portfolio.invest.application.intelligence.IntelligencePushPort;
import com.portfolio.invest.application.research.ReportAnchorRecheckPort;
import com.portfolio.invest.infrastructure.market.EastmoneyClient;
import com.portfolio.invest.infrastructure.market.SinaClient;
import com.portfolio.invest.infrastructure.market.TencentClient;
import com.portfolio.invest.support.PostgresTestSupport;
import com.portfolio.invest.support.RecordingMailSender;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.cucumber.spring.CucumberContextConfiguration;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;

/**
 * Cucumber-Spring 上下文配置：整套 BDD 共享一个 Spring 上下文（Testcontainers PG 由
 * {@link PostgresTestSupport} 提供 JVM 单例，{@code @DynamicPropertySource} 在 cucumber-spring
 * 的 TestContextManager 下同样生效）。
 *
 * <p>bean 覆盖说明：
 * <ul>
 *   <li>假 Model：{@code DEEPSEEK_API_KEY} 只是占位值（触发 {@code AgentConfig} 的
 *   {@code @ConditionalOnExpression} 装配路径）。{@code @TestBean} + 静态工厂把 bean 名
 *   {@code investModel} 做定义级替换（原 {@code ModelRegistry.resolve(...)} 工厂不执行，
 *   无需真实 API key），Agent 装配出真实 ReActAgent，全程不打真实 LLM。
 *   与 integrationTest 的 AguiStreamIntegrationTest 同方案（bdd 看不到该 source set，此处自带等价物）。</li>
 *   <li>行情客户端：三个最外层客户端（东财/新浪/腾讯）以 {@code @MockitoBean} 整体替换为 mock，
 *   BDD 全程不打真实网络；各场景自行 stub 主源故障 / 备源数据，并用 {@code verify} 断言降级与缓存行为。
 *   注意 @MockitoBean 不能写在 @Configuration 里，写在本上下文配置类（即 cucumber-spring 的
 *   测试类）上是允许的。</li>
 * </ul>
 */
@CucumberContextConfiguration
@SpringBootTest(properties = {
        "DEEPSEEK_API_KEY=test-dummy-key",
        "ADMIN_USERNAME=" + CucumberSpringConfig.ADMIN_USERNAME,
        "ADMIN_PASSWORD=" + CucumberSpringConfig.ADMIN_PASSWORD,
        "invest.mcp.secret-key=" + CucumberSpringConfig.MCP_SECRET_KEY})
@AutoConfigureMockMvc
public class CucumberSpringConfig extends PostgresTestSupport {

    /** 内置管理员（AdminSeedRunner 幂等种子，供审核/停用等管理员操作登录后台）。 */
    public static final String ADMIN_USERNAME = "bdd_admin";
    public static final String ADMIN_PASSWORD = "admin12345";

    /**
     * MCP token 加密测试主密钥（P1-10）：base64 编码的 32 字节全零（编译期常量，注解
     * 属性要求）——BDD 只验证加密链路（admin 设置 → v1 密文落库 → 解密还原），不依赖密钥随机性。
     */
    public static final String MCP_SECRET_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    /** bean 名按字段名推断为 investModel，精确替换 AgentConfig#investModel。 */
    @TestBean(methodName = "fixedReplyModel")
    Model investModel;

    static Model fixedReplyModel() {
        return new FixedReplyModel();
    }

    @MockitoBean
    EastmoneyClient eastmoneyClient;

    @MockitoBean
    SinaClient sinaClient;

    @MockitoBean
    TencentClient tencentClient;

    /**
     * 情报推送口打桩（M16-F11 BDD 公告推送场景）：推送服务全链真实（命中集 union +
     * push_log 留痕 + journal 回执），仅飞书单发口 stub——全程不打真实飞书 API；
     * 未 stub 场景默认 false（与未配置飞书的真实降级语义一致，不改变既有行为）。
     */
    @MockitoBean
    IntelligencePushPort intelligencePushPort;

    /**
     * 研报锚点重查口打桩（MS-32 B14 假设过期场景，任务书简化裁定）：staleness 编排全链真实
     * （锚点读取 + TTL 缓存 + detail 组装），仅重查口 stub——场景 3 回放 CHANGED（现值漂移）。
     * {@code ReportSteps} 的 @Before 统一 reset 并兜底 UNVERIFIABLE（未 stub 场景的 detail
     * 调用不因 mock 缺省 null 炸链）；重查机制本身（白名单派发/容差判定/四态序）由 B12
     * 单测钉死，BDD 只验 detail 组装与状态透出。
     */
    @MockitoBean
    ReportAnchorRecheckPort reportAnchorRecheckPort;

    /**
     * 发信桩：@Primary 覆盖未配置 SMTP 的 SmtpMailSender。T5 起注册为三段式（发码→携码注册），
     * 旅程步骤需要从桩记录的邮件中取码。
     */
    @TestConfiguration
    static class MailStub {
        @Bean
        @Primary
        RecordingMailSender recordingMailSender() {
            return new RecordingMailSender();
        }
    }

    /**
     * 研报 runner 脚本桩（MS-32 B14）：@Primary 覆盖真 {@code ReportAgentRunnerFactory}——
     * {@code ReportSteps} 按场景 arm 剧本（按调用计数回放：第 1~3 调阶段应答、第 4 调起
     * 草稿/回炉稿），全程不打真实 LLM；落库链（编排器状态机/校验门/sink/配额）全真。
     */
    @TestConfiguration
    static class ReportRunnerStub {
        @Bean
        @Primary
        ScriptedReportRunnerFactory scriptedReportRunnerFactory() {
            return new ScriptedReportRunnerFactory();
        }
    }

    /**
     * 脚本化研报 runner 工厂：剧本队列按调用序逐条消费（并发安全），耗尽即抛——
     * 未预期的额外轮快速失败（编排器收敛 FAILED(AGENT_ERROR) 而非静默错剧本）。
     *
     * <p><b>Ruling-21 修复③拓扑拟真（终审 C-1，照 {@code ReportWorkflowOrchestratorTest}
     * 的 ScriptedRunner / 集成测试 fakeRunnerFactory 同款）</b>：每轮对编排器裸 rc 派生新实例
     * （模拟 {@code HarnessAgent.ensureSessionDefaults} 字节码行为——工具装饰器与用量中间件只见
     * 派生 rc），剧本步在<b>派生 rc</b> 上注真值；轮末代拟 harness 接线（onAgent 完成流：
     * 派生 rc 作 ctx、AgentResultEvent 作轮收尾事件，doOnComplete 快照真值）——编排器在
     * 自有 rc 上直读恒空，只能经 {@link ReportUsageMiddleware#drainTruthInvocations()}
     * 真值通道拿到，回归旧读法场景即红。</p>
     */
    public static final class ScriptedReportRunnerFactory implements ReportAgentRunnerFactory {

        private final ConcurrentLinkedQueue<Function<RuntimeContext, String>> script =
                new ConcurrentLinkedQueue<>();

        /** 装载剧本（清空旧队列后整体替换）。 */
        public void arm(List<Function<RuntimeContext, String>> steps) {
            script.clear();
            script.addAll(steps);
        }

        /** 清空剧本（场景开始前防跨场景残留）。 */
        public void clear() {
            script.clear();
        }

        /** 剩余未消费步数（步骤断言用）。 */
        public int remaining() {
            return script.size();
        }

        @Override
        public ReportAgentRunner create(Long userId, ReportUsageMiddleware usageMiddleware) {
            return (rc, userPrompt) -> {
                Function<RuntimeContext, String> step = script.poll();
                if (step == null) {
                    throw new IllegalStateException("研报剧本耗尽：出现未预期的额外调用轮");
                }
                // 拓扑拟真：派生 rc 上跑剧本（真值注进派生池），轮末 onAgent 完成流快照进中间件
                RuntimeContext derived = RuntimeContext.builder(rc).build();
                String result = step.apply(derived);
                usageMiddleware.onAgent(null, derived, new AgentInput(List.of()),
                        input -> reactor.core.publisher.Flux.just(
                                (io.agentscope.core.event.AgentEvent) new io.agentscope.core.event.AgentResultEvent(
                                        Msg.builder().id("round-" + System.nanoTime()).role(MsgRole.ASSISTANT)
                                                .textContent("轮收尾").build())))
                        .blockLast();
                return result;
            };
        }
    }

    /** 固定回复的假 Model：单条文本响应（含 usage，配合 emit-token-usage），无工具调用即结束推理循环。 */
    public static class FixedReplyModel implements Model {

        public static final String REPLY = "这是测试环境的固定投研回复。";

        /**
         * 单次 stream() 调用快照（messages 与 tools 成对留存，只录不改，回复语义不变），
         * 供画像注入场景（MS-31）判读模型实际收到的系统提示——与 integrationTest 的
         * {@code AguiStreamIntegrationTest.FixedReplyModel} 同方案；场景开始前由
         * {@code MemorySteps} 的 Before 钩子清零。
         */
        public record ModelCall(List<Msg> messages, List<ToolSchema> tools) {}

        /** 跨场景累积的调用录制（场景开始前 clear）；并发结构，agent 异步线程写、步骤线程读。 */
        public static final ConcurrentLinkedQueue<ModelCall> RECORDED_CALLS = new ConcurrentLinkedQueue<>();

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            RECORDED_CALLS.add(new ModelCall(List.copyOf(messages), tools));
            return Flux.just(ChatResponse.builder()
                    .id("fake-completion-1")
                    .content(List.of(TextBlock.builder().text(REPLY).build()))
                    .usage(new ChatUsage(10, 5, 0.01))
                    .finishReason("stop")
                    .build());
        }

        @Override
        public String getModelName() {
            return "fixed-reply-model";
        }
    }
}
