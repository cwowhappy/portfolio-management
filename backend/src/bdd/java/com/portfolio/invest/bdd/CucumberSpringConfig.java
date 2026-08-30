package com.portfolio.invest.bdd;

import com.portfolio.invest.infrastructure.market.EastmoneyClient;
import com.portfolio.invest.infrastructure.market.SinaClient;
import com.portfolio.invest.infrastructure.market.TencentClient;
import com.portfolio.invest.support.PostgresTestSupport;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
        "ADMIN_PASSWORD=" + CucumberSpringConfig.ADMIN_PASSWORD})
@AutoConfigureMockMvc
public class CucumberSpringConfig extends PostgresTestSupport {

    /** 内置管理员（AdminSeedRunner 幂等种子，供审核/停用等管理员操作登录后台）。 */
    public static final String ADMIN_USERNAME = "bdd_admin";
    public static final String ADMIN_PASSWORD = "admin12345";

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
