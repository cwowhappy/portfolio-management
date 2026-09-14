package com.portfolio.invest.eval;

import com.portfolio.invest.InvestAgentApplication;
import com.portfolio.invest.application.market.MarketDataService;
import com.portfolio.invest.domain.user.UserRepository;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Agent 效果评估入口（方案 §5.4 批次 4）：周期性诊断仪，评估对象是真实 DeepSeek——
 * JVM 内起完整生产上下文（Testcontainers PG + 真实 investModel），仅行情门面替换为逐题注入的桩。
 *
 * <p>两模式：stub（默认，本批次实现）JVM 内 MockMvc 驱动 /agui/run；real 只留口子
 * （--real 本期打印未实现提示后正常退出）。报告恒生成、退出码恒 0（诊断不挂门槛；
 * 仅 DEEPSEEK_API_KEY 缺失与题库校验失败这类框架性错误退出 1）。顺序逐题、无并发、
 * 每题独立注册用户 + 独立 threadId（state 干净），单轮异步超时默认 120s（LLM 延迟）。
 */
public final class EvalRunner {

    /** 命令行参数（--real / --compare=&lt;path&gt; / --timeout-ms=&lt;n&gt;）。 */
    record Options(boolean real, Path compare, long timeoutMs) {

        static Options parse(String[] args) {
            boolean real = false;
            Path compare = null;
            long timeoutMs = 120_000;
            for (String arg : args) {
                if ("--real".equals(arg)) real = true;
                else if (arg.startsWith("--compare=")) compare = Path.of(arg.substring("--compare=".length()));
                else if (arg.startsWith("--timeout-ms=")) timeoutMs = Long.parseLong(arg.substring("--timeout-ms=".length()));
                else throw new IllegalArgumentException("未知参数: " + arg + "（支持 --real / --compare=<path> / --timeout-ms=<n>）");
            }
            return new Options(real, compare, timeoutMs);
        }
    }

    public static void main(String[] args) {
        int exitCode = new EvalRunner().run(Options.parse(args));
        System.exit(exitCode);
    }

    private int run(Options options) {
        // —— 环境解析：DEEPSEEK_API_KEY 进程环境变量优先，缺失回退仓库根 .env（指引退出 1） ——
        Path repoRoot = EnvSupport.repoRoot();
        Map<String, String> dotEnv = EnvSupport.loadDotEnv(repoRoot);
        String apiKey = EnvSupport.resolve("DEEPSEEK_API_KEY", dotEnv).orElse(null);
        if (apiKey == null) {
            System.err.println("缺少 DEEPSEEK_API_KEY：请 export DEEPSEEK_API_KEY=<key>，"
                    + "或在仓库根 .env 配置 DEEPSEEK_API_KEY=<key>（make eval-agent / gradlew evalAgent "
                    + "会自动把 .env 的 key 注入评估进程环境变量）。");
            return 1;
        }
        if (System.getenv("DEEPSEEK_API_KEY") == null || System.getenv("DEEPSEEK_API_KEY").isBlank()) {
            // agentscope DeepSeekModelProvider 只回退 System.getenv，JVM 内无法补设——须在进程启动前导出
            System.err.println("DEEPSEEK_API_KEY 仅存在于 .env、未导出为进程环境变量："
                    + "真实 DeepSeek Model 无法装配（agentscope 直读 System.getenv）。"
                    + "请通过 make eval-agent 或 ./gradlew evalAgent 运行（任务会注入环境变量）。");
            return 1;
        }
        String model = EnvSupport.resolve("DEEPSEEK_MODEL", dotEnv).orElse("deepseek-v4-flash");
        String baseUrl = EnvSupport.resolve("DEEPSEEK_BASE_URL", dotEnv).orElse("https://api.deepseek.com");

        if (options.real()) {
            // real 轨（--real --base-url=... --admin-user=... --admin-pass=...）本期只留口子，Task 14+ 扩展
            System.out.println("[eval] --real 轨本期未实现（方案 §5.4 批次 4 只落 stub 模式骨架）。");
            System.out.println("[eval] stub 基线（默认模式）已可评估 mode: stub 的全部题库。");
            return 0;
        }

        // —— 题库装载（schema 校验 fail-fast） ——
        List<EvalQuestion> questions = QuestionLoader.loadFromClasspath();
        System.out.printf("[eval] 题库装载 %d 题；模式=stub；被评模型=deepseek/%s%n", questions.size(), model);

        // —— 上下文启动：Testcontainers PG + 完整生产装配 + 行情桩 + 真实 DeepSeek ——
        // 覆盖项必须走命令行参数（优先级高于 application.yml）；SpringApplicationBuilder.properties()
        // 只是 default properties（优先级最低），会被 application.yml 的 datasource/state-root 压掉
        // ——首跑即因此连上了本机 dev 库（已回滚清理），勿改回
        EvalPostgresSupport.postgres();
        ConfigurableApplicationContext context = new SpringApplicationBuilder()
                .sources(InvestAgentApplication.class, EvalMarketStubConfig.class)
                .run(
                        "--spring.datasource.url=" + EvalPostgresSupport.jdbcUrl(),
                        "--spring.datasource.username=" + EvalPostgresSupport.username(),
                        "--spring.datasource.password=" + EvalPostgresSupport.password(),
                        "--spring.datasource.hikari.maximum-pool-size=5",
                        "--spring.datasource.hikari.minimum-idle=1",
                        // 同名覆盖 cachedMarketDataService（行情桩）的前提
                        "--spring.main.allow-bean-definition-overriding=true",
                        // harness state/workspace 重定向到 build/（不写仓库 .agentscope）
                        "--invest.mcp.harness.state-root=build/eval-agent/state",
                        "--invest.mcp.harness.workspace=build/eval-agent/workspace",
                        // 完整上下文启动前提（同测试任务的显式 key 注入）
                        "--REMEMBER_ME_KEY=eval-remember-me-key",
                        // 不占用 8080：MockMvc 驱动无需真实端口，嵌入式 Tomcat 随机端口兜底
                        "--server.port=0");
        try {
            // 覆盖项兜底校验：datasource 必须命中评估容器（防优先级回归导致静默连上 dev 库）
            String actualUrl = context.getEnvironment().getProperty("spring.datasource.url");
            if (!EvalPostgresSupport.jdbcUrl().equals(actualUrl)) {
                throw new IllegalStateException("评估 datasource 未指向 Testcontainers 容器: " + actualUrl);
            }
            EvalStubMarketService stub = (EvalStubMarketService)
                    EvalMarketStubConfig.expectStub(context.getBean(MarketDataService.class));
            // 守卫：investModel 必须是真实装配（AgentConfig 的 @ConditionalOnExpression 已触发）
            Object investModel = context.getBean("investModel");
            System.out.printf("[eval] 上下文就绪：行情桩=%s（逐题注入），LLM=真实 %s（%s）%n",
                    stub.getClass().getSimpleName(), investModel.getClass().getSimpleName(), model);

            AguiDriver driver = new AguiDriver((org.springframework.web.context.WebApplicationContext) context,
                    context.getBean(UserRepository.class));
            DeepSeekJudge judge = new DeepSeekJudge(baseUrl, model, apiKey, options.timeoutMs());

            List<QuestionOutcome> outcomes = new ArrayList<>();
            String judgeRespondedModel = null;
            for (EvalQuestion question : questions) {
                if (!question.isStubMode()) {
                    outcomes.add(new QuestionOutcome(question, QuestionOutcome.Status.SKIPPED,
                            "real 轨题目在 stub 基线下跳过（--real 本期未实现）", null,
                            null, null, 0, List.of(), List.of(), null, null, null));
                    System.out.printf("[eval] %-32s SKIPPED（real 轨预留）%n", question.id());
                    continue;
                }
                QuestionOutcome outcome = runOne(question, driver, stub, judge, options);
                outcomes.add(outcome);
                if (outcome.judge() != null && outcome.judge().respondedModel() != null) {
                    judgeRespondedModel = outcome.judge().respondedModel();
                }
                System.out.printf("[eval] %-32s %-7s（%.1fs，事件 %d）%n", question.id(),
                        outcome.status(), outcome.durationMs() / 1000.0, outcome.eventCount());
            }

            ReportWriter.Written written;
            try {
                written = new ReportWriter(Path.of("build", "reports", "eval-agent"))
                        .write(outcomes, new ReportWriter.Meta("stub", model, baseUrl, model,
                                judgeRespondedModel, (int) options.timeoutMs()), options.compare());
            } catch (java.io.IOException e) {
                throw new IllegalStateException("评估报告写出失败", e);
            }
            System.out.printf("[eval] 报告已生成：%s%n[eval]           %s%n", written.json(), written.md());
        } finally {
            context.close();
        }
        return 0;
    }

    /** 单题执行：桩注入 → 独立用户/threadId → 逐轮 SSE → 断言器（结构分）→ judge（主观细项）。 */
    private QuestionOutcome runOne(EvalQuestion question, AguiDriver driver, EvalStubMarketService stub,
                                   DeepSeekJudge judge, Options options) {
        long start = System.currentTimeMillis();
        String safeId = question.id().replaceAll("[^a-zA-Z0-9_-]", "_").toLowerCase(Locale.ROOT);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String username = "eval_" + safeId + "_" + suffix;
        String threadId = "eval-" + safeId + "-" + suffix;
        try {
            stub.inject(question.stubData());
            var session = driver.registerApproveAndLogin(username);
            List<AguiDriver.SseTurn> turns = new ArrayList<>();
            for (int i = 0; i < question.turns().size(); i++) {
                turns.add(driver.run(session, threadId, threadId + "-turn-" + (i + 1),
                        question.turns().get(i), i, options.timeoutMs()));
            }
            AguiEventExtractor.Transcript transcript = AguiEventExtractor.extract(turns);
            List<AssertionEngine.DimensionResult> dimensions = AssertionEngine.evaluate(question, transcript);

            DeepSeekJudge.Verdict verdict = judge.judge(question.judge(),
                    readResource("rubric/" + question.judge() + ".md"),
                    readResource("rubric/judge-prompt-template.md"),
                    Map.of("question", String.join("\n", question.turns()),
                            "answer", transcript.assistantText(),
                            // 工具入参 + 返回摘要（judge 数值核对的唯一事实源；返回截断防图表全量刷屏）
                            "tools", transcript.toolCalls().isEmpty() ? "（无工具调用）"
                                    : String.join("\n", transcript.toolCalls().stream()
                                            .map(c -> c.forJudge(500)).toList())));

            boolean turnFailed = turns.stream().anyMatch(AguiDriver.SseTurn::failed);
            boolean dimsFail = dimensions.stream()
                    .anyMatch(d -> d.status() == AssertionEngine.Status.FAIL);
            boolean judgeFail = verdict != null && verdict.error() == null && !verdict.pass();
            QuestionOutcome.Status status = turnFailed || dimsFail || judgeFail
                    ? QuestionOutcome.Status.FAIL : QuestionOutcome.Status.PASS;
            String error = turnFailed
                    ? turns.stream().map(AguiDriver.SseTurn::error).filter(e -> e != null)
                            .findFirst().orElse("轮次失败") : null;
            return new QuestionOutcome(question, status, null, error, username, threadId,
                    System.currentTimeMillis() - start, turns, dimensions, verdict,
                    transcript.tokenUsage(), transcript.assistantText());
        } catch (Exception e) {
            return new QuestionOutcome(question, QuestionOutcome.Status.ERROR, null,
                    e.getClass().getSimpleName() + ": " + e.getMessage(), username, threadId,
                    System.currentTimeMillis() - start, List.of(), List.of(), null, null, null);
        }
    }

    private static String readResource(String path) {
        try (InputStream in = EvalRunner.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("评估资源缺失: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("评估资源读取失败: " + path, e);
        }
    }
}
