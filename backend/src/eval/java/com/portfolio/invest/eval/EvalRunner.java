package com.portfolio.invest.eval;

import com.portfolio.invest.InvestAgentApplication;
import com.portfolio.invest.application.market.MarketDataService;
import com.portfolio.invest.domain.user.UserRepository;
import com.portfolio.invest.infrastructure.eval.EvalPostgresProvisioner;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Agent 效果评估入口（方案 §5.4 批次 4）：周期性诊断仪，评估对象是真实 DeepSeek——
 * JVM 内起完整生产上下文（同库独立 schema PG + 真实 investModel），仅行情门面替换为逐题注入的桩。
 *
 * <p>两模式：stub（默认，本批次实现）JVM 内 MockMvc 驱动 /agui/run；real 只留口子
 * （--real 本期打印未实现提示后正常退出）。报告恒生成、退出码恒 0（诊断不挂门槛；
 * 仅 DEEPSEEK_API_KEY 缺失、端点探活失败与题库校验失败这类框架性错误退出 1）。顺序逐题、
 * 无并发、每题独立注册用户 + 独立 threadId（state 干净），单轮异步超时默认 120s（LLM 延迟）。
 * mcp 类题自动懒启动内嵌桩 MCP server 并为该题用户 seed 扩展源配置（{@link EvalMcpSupport}）。
 */
public final class EvalRunner {

    /**
     * 轨道分派参数（MS-30 跟进②，终审 I-2 拍板「并入」）：evalBootJar 单 Start-Class
     * （本类）不变，argv 含 {@code --track=extraction} 时 main 转发
     * {@link ExtractionEvalRunner}（两轨独立 main 语义保留、本地 {@code make eval-extraction}
     * 仍直调 Extraction main）。轨别参数从剩余 argv <b>剥离</b>——两轨参数解析均拒未知参数，
     * 透传即炸（对话轨 Options.parse / 抽取轨 run 均如此）。取值：{@code agent}（缺省，对话轨）/
     * {@code extraction}（抽取轨，纯 JVM 无 Spring 无 DB，22 题 flash 分钟级）。
     */
    record TrackDispatch(String track, String[] remaining) {

        static final String TRACK_AGENT = "agent";
        static final String TRACK_EXTRACTION = "extraction";

        static TrackDispatch parse(String[] args) {
            String track = TRACK_AGENT;
            List<String> remaining = new ArrayList<>();
            for (String arg : args) {
                if (arg.startsWith("--track=")) {
                    track = arg.substring("--track=".length()).toLowerCase(Locale.ROOT);
                } else {
                    remaining.add(arg);
                }
            }
            if (!TRACK_AGENT.equals(track) && !TRACK_EXTRACTION.equals(track)) {
                throw new IllegalArgumentException("未知 --track 取值: --track=" + track
                        + "（支持 --track=agent（对话轨，缺省）/ --track=extraction（抽取轨））");
            }
            return new TrackDispatch(track, remaining.toArray(String[]::new));
        }
    }

    /** 命令行参数（--list / --real / --compare=&lt;path&gt; / --timeout-ms=&lt;n&gt; / --invest.eval.data-root=&lt;path&gt;；
     *  轨别参数 --track=&lt;v&gt; 由 {@link TrackDispatch} 先行剥离，不进本 Options）。 */
    record Options(boolean list, boolean real, Path compare, long timeoutMs, String dataRoot) {

        /** 评测数据目录默认值：state/workspace 相对 backend/build（Gradle JavaExec 工作目录成立）；部署机裸进程须显式传绝对路径。 */
        static final String DEFAULT_DATA_ROOT = "build/eval-agent";

        static Options parse(String[] args) {
            boolean list = false;
            boolean real = false;
            Path compare = null;
            long timeoutMs = 120_000;
            String dataRoot = DEFAULT_DATA_ROOT;
            for (String arg : args) {
                if ("--list".equals(arg)) list = true;
                else if ("--real".equals(arg)) real = true;
                else if (arg.startsWith("--compare=")) compare = Path.of(arg.substring("--compare=".length()));
                else if (arg.startsWith("--timeout-ms=")) timeoutMs = Long.parseLong(arg.substring("--timeout-ms=".length()));
                else if (arg.startsWith("--invest.eval.data-root=")) dataRoot = arg.substring("--invest.eval.data-root=".length());
                else throw new IllegalArgumentException("未知参数: " + arg + "（支持 --list / --real / --compare=<path> / --timeout-ms=<n> / --invest.eval.data-root=<path>）");
            }
            if (dataRoot == null || dataRoot.isBlank()) {
                throw new IllegalArgumentException("--invest.eval.data-root 不得为空（state/workspace/工具输出回读的根目录）");
            }
            return new Options(list, real, compare, timeoutMs, dataRoot);
        }
    }

    /**
     * 评测库连接参数（MS-30 B2，同库独立 schema）：{@code EVAL_DATASOURCE_*} 显式覆盖
     * （本地容器路径：自行 docker run 后 export 三变量指向容器 URL），空则回退主
     * datasource——环境变量名与默认值逐字对齐 application.yml（{@code SPRING_DATASOURCE_URL}
     * / {@code POSTGRES_USER} / {@code POSTGRES_PASSWORD}，.env 兜底同 {@link EnvSupport}）。
     * 不经 Spring 解析（reset 发生在上下文启动前），此处复刻 yml 占位符语义。
     */
    record EvalDatasource(String url, String username, String password) {

        /** application.yml 的 datasource 默认值复刻（缺省回退主库同实例）。 */
        private static final String DEFAULT_URL = "jdbc:postgresql://localhost:5432/invest";
        private static final String DEFAULT_USERNAME = "invest";
        private static final String DEFAULT_PASSWORD = "invest";

        static EvalDatasource resolve(Map<String, String> dotEnv) {
            return new EvalDatasource(
                    firstNonBlank(EnvSupport.resolve("EVAL_DATASOURCE_URL", dotEnv).orElse(null),
                            EnvSupport.resolve("SPRING_DATASOURCE_URL", dotEnv).orElse(null), DEFAULT_URL),
                    firstNonBlank(EnvSupport.resolve("EVAL_DATASOURCE_USERNAME", dotEnv).orElse(null),
                            EnvSupport.resolve("POSTGRES_USER", dotEnv).orElse(null), DEFAULT_USERNAME),
                    firstNonBlank(EnvSupport.resolve("EVAL_DATASOURCE_PASSWORD", dotEnv).orElse(null),
                            EnvSupport.resolve("POSTGRES_PASSWORD", dotEnv).orElse(null), DEFAULT_PASSWORD));
        }

        private static String firstNonBlank(String... candidates) {
            for (String candidate : candidates) {
                if (candidate != null && !candidate.isBlank()) return candidate;
            }
            throw new IllegalStateException("不可达：末位候选为非空默认值");
        }
    }

    public static void main(String[] args) {
        // —— 轨道分派（MS-30 跟进②）：--track=extraction 转抽取轨 main（其内 System.exit(0)
        //    即终止 JVM，不返回）；--track=agent/缺省走本类对话轨 ——
        TrackDispatch dispatch = TrackDispatch.parse(args);
        if (TrackDispatch.TRACK_EXTRACTION.equals(dispatch.track())) {
            ExtractionEvalRunner.main(dispatch.remaining());
            return; // 理论不可达：Extraction main 内恒 System.exit
        }
        int exitCode = new EvalRunner().run(Options.parse(dispatch.remaining()));
        System.exit(exitCode);
    }

    private int run(Options options) {
        // —— --list：只装载校验题库并打印清单（干跑，不起 LLM、不连任何外部端点） ——
        if (options.list()) {
            List<EvalQuestion> questions = QuestionLoader.loadFromClasspath();
            System.out.printf("%-34s %-12s %-6s %-5s %-28s %s%n",
                    "id", "category", "mode", "turns", "judge", "expect 维度");
            for (EvalQuestion q : questions) {
                System.out.printf("%-34s %-12s %-6s %-5d %-28s %s%n",
                        q.id(), q.category(), q.mode(), q.turns().size(), q.judge(),
                        String.join(",", q.expect().declaredDimensions()));
            }
            System.out.printf("[eval] --list 干跑通过：装载 %d 题，schema 校验全部通过（未起 LLM）%n",
                    questions.size());
            // 资产指纹干跑同源采集（零 Spring；真跑 runMeta 用同一实现，hash 必须一致）
            EvalAssetHasher.Collected fingerprint = EvalAssetHasher.collectStandalone();
            System.out.printf("[eval] 资产指纹：assetHashes %d 项（%s）、evalAssets %d 项、questionBankHash=%s%n",
                    fingerprint.assetHashes().size(), fingerprint.typeSummary(),
                    fingerprint.evalAssets().size(), fingerprint.questionBankHash());
            return 0;
        }

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

        // —— 运行指纹起点（runMeta 口径：起止时间覆盖装载→题库循环收尾，报告写出不计入） ——
        String runId = UUID.randomUUID().toString();
        ZonedDateTime startedAt = ZonedDateTime.now();
        System.out.printf("[eval] runId=%s（triggeredBy=MANUAL）%n", runId);

        // —— 题库装载（schema 校验 fail-fast） ——
        List<EvalQuestion> questions = QuestionLoader.loadFromClasspath();
        System.out.printf("[eval] 题库装载 %d 题；模式=stub；被评模型=deepseek/%s%n", questions.size(), model);

        // —— 资产指纹采集（装载时，§4.1 eval 侧回流：main 四类 + rubric/题库，零 Spring 干跑同源） ——
        EvalAssetHasher.Collected fingerprint = EvalAssetHasher.collectStandalone();
        System.out.printf("[eval] 资产指纹：assetHashes %d 项（%s）、evalAssets %d 项、questionBankHash=%s%n",
                fingerprint.assetHashes().size(), fingerprint.typeSummary(),
                fingerprint.evalAssets().size(), fingerprint.questionBankHash());

        // —— 运行前探活（同 judge 客户端，10s）：拥塞期白起评测 schema 重置 + 全上下文不值得 ——
        DeepSeekJudge judge = new DeepSeekJudge(baseUrl, model, apiKey, options.timeoutMs());
        String probeError = judge.probe();
        if (probeError != null) {
            System.err.println("模型端点不可用/拥塞（" + baseUrl + "，模型 " + model + "）: " + probeError);
            System.err.println("可 DEEPSEEK_MODEL=<其他模型> 临时换模型（make eval-agent DEEPSEEK_MODEL=...），"
                    + "或稍后重试——框架无法诊断时不启动评估上下文。");
            return 1;
        }
        System.out.printf("[eval] 端点探活通过：%s（模型 %s）%n", baseUrl, model);

        // —— 上下文启动：同库独立 schema 重置 + 完整生产装配 + 行情桩 + 真实 DeepSeek ——
        // 覆盖项必须走命令行参数（优先级高于 application.yml）；SpringApplicationBuilder.properties()
        // 只是 default properties（优先级最低），会被 application.yml 的 datasource/state-root 压掉
        // ——首跑即因此连上了本机 dev 库（已回滚清理），勿改回
        EvalDatasource evalDatasource = EvalDatasource.resolve(dotEnv);
        String evalJdbcUrl;
        try {
            // 每轮重置独立评测 schema（DROP CASCADE 重建，Flyway 随上下文启动全量迁移进去）；
            // 连的是同库的 eval_schema，主库其他 schema 数据不受影响
            evalJdbcUrl = EvalPostgresProvisioner.reset(
                    evalDatasource.url(), evalDatasource.username(), evalDatasource.password());
        } catch (IllegalStateException e) {
            System.err.println(e.getMessage());
            System.err.println("请检查 EVAL_DATASOURCE_URL（缺省回退 SPRING_DATASOURCE_URL→"
                    + "jdbc:postgresql://localhost:5432/invest）与凭据 EVAL_DATASOURCE_USERNAME/POSTGRES_USER、"
                    + "EVAL_DATASOURCE_PASSWORD/POSTGRES_PASSWORD 是否指向可用的 PostgreSQL（env 或仓库根 .env）。");
            return 1;
        }
        Path dataRoot = Path.of(options.dataRoot());
        EvalMcpSupport.configure(dataRoot); // mcp 题的笔记目录随数据根（须在懒启动前注入）
        System.out.printf("[eval] 评测库：%s（独立 schema %s，每轮重置）；数据目录：%s%n",
                evalDatasource.url(), EvalPostgresProvisioner.EVAL_SCHEMA, dataRoot.toAbsolutePath());
        ConfigurableApplicationContext context = new SpringApplicationBuilder()
                .sources(InvestAgentApplication.class, EvalMarketStubConfig.class)
                .run(
                        "--spring.datasource.url=" + evalJdbcUrl,
                        "--spring.datasource.username=" + evalDatasource.username(),
                        "--spring.datasource.password=" + evalDatasource.password(),
                        "--spring.datasource.hikari.maximum-pool-size=5",
                        "--spring.datasource.hikari.minimum-idle=1",
                        // 同名覆盖 cachedMarketDataService（行情桩）的前提
                        "--spring.main.allow-bean-definition-overriding=true",
                        // harness state/workspace 重定向到数据目录（不写仓库 .agentscope；根目录经
                        // --invest.eval.data-root 参数化——本地默认 build/eval-agent，部署机/调度器传绝对路径）
                        "--invest.mcp.harness.state-root=" + dataRoot.resolve("state"),
                        "--invest.mcp.harness.workspace=" + dataRoot.resolve("workspace"),
                        // 完整上下文启动前提（同测试任务的显式 key 注入）
                        "--REMEMBER_ME_KEY=eval-remember-me-key",
                        // §2.3 副作用禁用双保险②：eval 上下文禁调度（SchedulingConfig 条件化，
                        // 全部 @Scheduled cron 静默缺席；跑完即 System.exit，本就等不到 cron 点位）
                        "--Eval_MODE=true",
                        // 不占用 8080：MockMvc 驱动无需真实端口，嵌入式 Tomcat 随机端口兜底
                        "--server.port=0");
        try {
            // 覆盖项兜底校验：datasource 必须指向独立评测 schema（防优先级回归静默连上 dev/主库）
            EvalPostgresProvisioner.assertGuard(context.getEnvironment().getProperty("spring.datasource.url"));
            // §2.3 双保险②守卫：Eval_MODE=true 必须让调度后处理器缺席（SchedulingConfig 条件被
            // 误删/失效时 fail-fast，防评测上下文静默跑起定时副作用）
            if (context.getBeanNamesForType(
                    org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor.class).length > 0) {
                throw new IllegalStateException(
                        "Eval_MODE=true 未生效：调度后处理器仍在评测上下文（SchedulingConfig 条件化失效）");
            }
            EvalStubMarketService stub = (EvalStubMarketService)
                    EvalMarketStubConfig.expectStub(context.getBean(MarketDataService.class));
            // 守卫：investModel 必须是真实装配（AgentConfig 的 @ConditionalOnExpression 已触发）
            Object investModel = context.getBean("investModel");
            System.out.printf("[eval] 上下文就绪：行情桩=%s（逐题注入），LLM=真实 %s（%s）%n",
                    stub.getClass().getSimpleName(), investModel.getClass().getSimpleName(), model);

            AguiDriver driver = new AguiDriver((org.springframework.web.context.WebApplicationContext) context,
                    context.getBean(UserRepository.class));
            // 计算类断言容差（需求决策 #15）：配置在主上下文环境里（application.yml invest.eval.*），
            // 断言器是纯静态无 Spring——此处读出后逐题下传（CalcTolerance 纯函数收参数不读配置）
            int calcTolerancePct = resolveCalcTolerancePct(context);
            System.out.printf("[eval] 计算类断言容差：invest.eval.calc-tolerance-pct=%d%n", calcTolerancePct);
            // token 预算护栏（终审 I-1 执行端接线）：同上经主上下文环境读入，逐题累计题级
            // tokenUsage.totalTokens，超限中止剩余题（超时护栏 120min 仍是第一道防线）
            long tokenBudget = resolveTokenBudget(context);
            TokenBudgetGuard tokenGuard = new TokenBudgetGuard(tokenBudget);
            System.out.printf("[eval] token 预算护栏：invest.eval.token-budget=%d%n", tokenBudget);

            List<QuestionOutcome> outcomes = new ArrayList<>();
            String judgeRespondedModel = null;
            for (EvalQuestion question : questions) {
                if (!question.isStubMode()) {
                    outcomes.add(new QuestionOutcome(question, QuestionOutcome.Status.SKIPPED,
                            "real 轨题目在 stub 基线下跳过（--real 本期未实现）", null,
                            null, null, 0, List.of(), List.of(), null, null, null, null));
                    System.out.printf("[eval] %-32s SKIPPED（real 轨预留）%n", question.id());
                    continue;
                }
                if (tokenGuard.exceeded()) {
                    // 预算耗尽：剩余题不再起 LLM，占位口径同 real 轨预留跳过
                    outcomes.add(tokenGuard.skippedOutcome(question));
                    System.out.printf("[eval] %-32s SKIPPED（token 预算超限中止）%n", question.id());
                    continue;
                }
                QuestionOutcome outcome = runOne(question, driver, stub, judge, options, context,
                        calcTolerancePct);
                outcomes.add(outcome);
                boolean wasExceeded = tokenGuard.exceeded();
                tokenGuard.accumulate(outcome);
                if (!wasExceeded && tokenGuard.exceeded()) {
                    System.out.printf("[eval] token 预算超限：%s（剩余 %d 题中止，runMeta 标 PARTIAL）%n",
                            tokenGuard.skipReason(), questions.size() - outcomes.size());
                }
                if (outcome.judge() != null && outcome.judge().respondedModel() != null) {
                    judgeRespondedModel = outcome.judge().respondedModel();
                }
                System.out.printf("[eval] %-32s %-7s（%.1fs，事件 %d）%n", question.id(),
                        outcome.status(), outcome.durationMs() / 1000.0, outcome.eventCount());
            }

            ReportWriter.Written written;
            try {
                // runMeta（报告 schema v2，§2.4）：runner 正常收尾 FULL（超时/非零退出的 PARTIAL 由
                // 收割侧落库）；token 预算超限中止例外——runner 侧即标 PARTIAL + tokenBudgetExceeded
                // （收割按 PARTIAL 语义不任 baseline 并注理由行）。triggeredBy 子进程不可知触发来源，
                // 缺省 MANUAL（落库口径归 Task 6）
                ZonedDateTime finishedAt = ZonedDateTime.now();
                boolean tokenBudgetExceeded = tokenGuard.exceeded();
                ReportWriter.RunMeta runMeta = new ReportWriter.RunMeta(runId,
                        startedAt.toString(), finishedAt.toString(), "MANUAL",
                        fingerprint.assetHashes(), fingerprint.evalAssets(), fingerprint.questionBankHash(),
                        Duration.between(startedAt, finishedAt).toMillis(),
                        tokenBudgetExceeded ? "PARTIAL" : "FULL",
                        tokenBudgetExceeded ? Boolean.TRUE : null);
                written = new ReportWriter(Path.of("build", "reports", "eval-agent"))
                        .write(outcomes, new ReportWriter.Meta("stub", model, baseUrl, model,
                                judgeRespondedModel, (int) options.timeoutMs()), runMeta, options.compare());
            } catch (java.io.IOException e) {
                throw new IllegalStateException("评估报告写出失败", e);
            }
            System.out.printf("[eval] 报告已生成：%s%n[eval]           %s%n", written.json(), written.md());
        } finally {
            EvalMcpSupport.shutdown(); // mcp 题才启动过；幂等，未启动时为 no-op
            context.close();
        }
        return 0;
    }

    /**
     * 计算类断言容差解析：读主上下文环境的 invest.eval.calc-tolerance-pct（application.yml
     * 默认 2，部署可覆盖），未配置时回退 {@link AssertionEngine#DEFAULT_CALC_TOLERANCE_PCT}。
     */
    private static int resolveCalcTolerancePct(ConfigurableApplicationContext context) {
        Integer pct = context.getEnvironment().getProperty("invest.eval.calc-tolerance-pct", Integer.class);
        return pct != null ? pct : AssertionEngine.DEFAULT_CALC_TOLERANCE_PCT;
    }

    /**
     * token 预算解析（终审 I-1 执行端接线，沿 calc-tolerance-pct resolve 先例）：读主上下文
     * 环境的 invest.eval.token-budget（application.yml 默认 7,500,000 = 64 题实测 2.45M 的
     * ~3 倍，部署可覆盖），未配置时回退 {@link TokenBudgetGuard#DEFAULT_TOKEN_BUDGET}。
     */
    private static long resolveTokenBudget(ConfigurableApplicationContext context) {
        Long budget = context.getEnvironment().getProperty("invest.eval.token-budget", Long.class);
        return budget != null ? budget : TokenBudgetGuard.DEFAULT_TOKEN_BUDGET;
    }

    /**
     * 单题执行：桩注入 → 独立用户/threadId →（mcp 题为该用户 seed 扩展源）→ 逐轮 SSE →
     * 断言器（结构分）→ judge（主观细项）。
     */
    private QuestionOutcome runOne(EvalQuestion question, AguiDriver driver, EvalStubMarketService stub,
                                   DeepSeekJudge judge, Options options,
                                   ConfigurableApplicationContext context, int calcTolerancePct) {
        long start = System.currentTimeMillis();
        String safeId = question.id().replaceAll("[^a-zA-Z0-9_-]", "_").toLowerCase(Locale.ROOT);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String username = "eval_" + safeId + "_" + suffix;
        String threadId = "eval-" + safeId + "-" + suffix;
        try {
            stub.inject(question.stubData());
            var session = driver.registerApproveAndLogin(username);
            if ("mcp".equals(question.category())) {
                // UserToolkitFactory 每次运行按当前配置现查现装配——run 前 seed 即生效
                Long userId = context.getBean(UserRepository.class)
                        .findByUsername(username).orElseThrow().id();
                EvalMcpSupport.enableForUser(context.getBean(org.springframework.jdbc.core.JdbcTemplate.class),
                        userId);
            }
            List<AguiDriver.SseTurn> turns = new ArrayList<>();
            for (int i = 0; i < question.turns().size(); i++) {
                turns.add(driver.run(session, threadId, threadId + "-turn-" + (i + 1),
                        question.turns().get(i), i, options.timeoutMs()));
            }
            AguiEventExtractor.Transcript transcript = AguiEventExtractor.extract(turns);
            List<AssertionEngine.DimensionResult> dimensions =
                    AssertionEngine.evaluate(question, transcript, calcTolerancePct);
            // LLM 实际所见的 TOOL 输出（state 落盘读回）：图表类调用 SSE 只有全量 ChartSpec，
            // 其摘要被双通道 skipSet 跳过——judge 数值核对的事实源须取模型真正看到的文本。
            // state 路径与上下文注入的 state-root 同源（--invest.eval.data-root 解析值）
            Map<String, String> llmSeen = AguiEventExtractor.llmToolOutputs(
                    Path.of(options.dataRoot()).resolve("state"), threadId);

            DeepSeekJudge.Verdict verdict = judge.judge(question.judge(),
                    readResource("rubric/" + question.judge() + ".md"),
                    readResource("rubric/judge-prompt-template.md"),
                    Map.of("question", String.join("\n", question.turns()),
                            "answer", transcript.assistantText(),
                            // 工具入参 + 返回摘要（judge 数值核对的唯一事实源；ChartSpec 由 forJudge
                            // 剥离、换 state 里的 LLM 所见摘要；非图表结果截 2000 防刷屏）
                            "tools", transcript.toolCalls().isEmpty() ? "（无工具调用）"
                                    : String.join("\n", transcript.toolCalls().stream()
                                            .map(c -> c.forJudge(2000, llmSeen.get(c.toolCallId()))).toList())));

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
                    transcript.tokenUsage(), transcript.trustStats(), transcript.assistantText());
        } catch (Exception e) {
            return new QuestionOutcome(question, QuestionOutcome.Status.ERROR, null,
                    e.getClass().getSimpleName() + ": " + e.getMessage(), username, threadId,
                    System.currentTimeMillis() - start, List.of(), List.of(), null, null, null, null);
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
