package com.portfolio.invest.bdd;

import com.portfolio.invest.application.intelligence.IntelligencePushPort;
import com.portfolio.invest.infrastructure.market.EastmoneyClient;
import com.portfolio.invest.infrastructure.market.SinaClient;
import com.portfolio.invest.infrastructure.market.TencentClient;
import com.portfolio.invest.support.PostgresTestSupport;
import com.portfolio.invest.support.RecordingMailSender;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.cucumber.spring.CucumberContextConfiguration;
import java.util.List;
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

    /** 固定回复的假 Model：单条文本响应（含 usage，配合 emit-token-usage），无工具调用即结束推理循环。 */
    public static class FixedReplyModel implements Model {

        public static final String REPLY = "这是测试环境的固定投研回复。";

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
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
