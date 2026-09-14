package com.portfolio.invest.eval;

import com.portfolio.invest.application.market.MarketDataService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 评估上下文的行情桩装配：JVM 内起完整生产上下文（{@code InvestAgentApplication}）之上，
 * 以同名 bean 定义覆盖 {@code @Primary} 缓存装饰器 {@code cachedMarketDataService}。
 *
 * <p>为什么盖缓存装饰器而不是 {@code OrchestratingMarketDataService}（方案裁定"桩掉编排门面"
 * 的落地差异，见任务报告）：
 * <ul>
 *   <li>装在装饰器前面的桩才会被所有注入方（InvestTools/MarketController/Health）看到——
 *       只盖编排器时缓存装饰器仍在外层，其 search 10 分钟 TTL 会让上一题数据集泄漏到下一题，
 *       破坏"逐题注入固定数据集"的隔离前提；</li>
 *   <li>同名覆盖需 {@code spring.main.allow-bean-definition-overriding=true}（runner 启动属性
 *       里开启，仅评估进程）；缓存类因此不再实例化，真实编排器 bean 仍被装配但无人调用（零外呼）。</li>
 * </ul>
 * <p>不用 {@code @MockitoBean}（test-only API，评估非测试框架）；LLM 侧不动——
 * {@code investModel} 保持真实 DeepSeek，评估对象即它。
 */
@Configuration(proxyBeanMethods = false)
public class EvalMarketStubConfig {

    /** bean 名刻意取 cachedMarketDataService：同名覆盖组件扫描出的 @Primary 缓存装饰器。 */
    @Bean(name = "cachedMarketDataService")
    @Primary
    public EvalStubMarketService cachedMarketDataService() {
        return new EvalStubMarketService();
    }

    /** runner 启动后自检用：确认按类型解析到的确是桩（防 bean 定义覆盖顺序意外）。 */
    public static MarketDataService expectStub(MarketDataService resolved) {
        if (!(resolved instanceof EvalStubMarketService)) {
            throw new IllegalStateException("行情桩未生效：按类型解析到 " + resolved.getClass().getName()
                    + "（bean 同名覆盖失败，检查 allow-bean-definition-overriding 与装配顺序）");
        }
        return resolved;
    }
}
