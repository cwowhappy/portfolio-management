package com.portfolio.invest.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.application.market.MarketDataService;
import com.portfolio.invest.application.valuation.ValuationApplicationService;
import com.portfolio.invest.config.InvestProperties;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;

/**
 * AgentConfig 条件装配契约：无 DEEPSEEK_API_KEY 时 Model/Agent bean 不存在（服务仍可启动），
 * 有 key 时装配成功。用 ApplicationContextRunner 做纯上下文级断言，不起容器、不起完整应用。
 *
 * <p>「有 key」用例通过 {@link ModelRegistry#registerFactory} 为 deepseek:* 注册假工厂：
 * 真实 DeepSeekModelProvider 构造时读取 {@code System.getenv("DEEPSEEK_API_KEY")}（而非 Spring 属性），
 * 是否可构造取决于测试进程环境（make 会 export .env 的真实 key，裸 gradlew 则没有），不可依赖。
 * 注册假工厂后 {@code AgentConfig#investModel} 的 {@code ModelRegistry.resolve("deepseek:...")}
 * 路径仍被真实执行，只是返回假 Model，用例在两种环境下行为一致。
 * userFactories 为 JVM 级静态注册表，但其他测试类不经过 ModelRegistry（SSE 集成测试用
 * {@code @TestBean} 在 bean 定义级替换），无泄漏影响。
 */
class AgentConfigContextTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(StubDependencies.class, AgentConfig.class);

    @Test
    void 无APIKEY时Model与Agent不装配但上下文可启动() {
        // 显式置空：make 会 export .env 中的真实 DEEPSEEK_API_KEY 进测试进程，
        // runner 环境会继承该环境变量；withPropertyValues 落在 systemProperties，
        // 优先级高于 systemEnvironment，置空后 hasText 为 false。
        runner.withPropertyValues("DEEPSEEK_API_KEY=").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean("investModel");
            assertThat(context).doesNotHaveBean("invest");
        });
    }

    @Test
    void 有APIKEY时Model与Agent装配成功() {
        ModelRegistry.registerFactory("deepseek:.*", (modelId, creationContext) -> new FakeModel());

        runner.withPropertyValues("DEEPSEEK_API_KEY=test-key")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasBean("investModel");
                    assertThat(context).hasBean("invest");
                    assertThat(context.getBean("investModel")).isInstanceOf(FakeModel.class);
                    Agent agent = (Agent) context.getBean("invest");
                    assertThat(agent).isInstanceOf(ReActAgent.class);
                    assertThat(agent.getName()).isEqualTo("invest");
                });
    }

    /** AgentConfig 的协作者（不触发组件扫描，显式供给；InvestTools 用 mock 依赖构造真实实例以保留 @Tool 注解）。 */
    @Configuration(proxyBeanMethods = false)
    static class StubDependencies {

        @Bean
        InvestProperties investProperties() {
            return new InvestProperties();
        }

        @Bean
        InvestTools investTools() {
            return new InvestTools(
                    mock(MarketDataService.class), mock(ValuationApplicationService.class), new ObjectMapper());
        }
    }

    static class FakeModel implements Model {

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.empty();
        }

        @Override
        public String getModelName() {
            return "fake-model";
        }
    }
}
