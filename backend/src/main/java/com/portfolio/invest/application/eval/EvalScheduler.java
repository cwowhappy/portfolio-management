package com.portfolio.invest.application.eval;

import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.eval.EvalRunRepository;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 评测调度器（MS-30 B4，设计规格 §2.2 调度五步）：每晚定时 + 手动触发统一入口——
 * 构造 env 白名单子进程、超时看护、退出后收割。五个要点：
 * <ol>
 *   <li><b>互斥锁</b>：单实例 {@code AtomicBoolean}（手动与定时互斥，进行中再触发抛
 *       {@link EvalRunInProgressException}——手动端映射 409）；</li>
 *   <li><b>env 白名单</b>：{@link #ENV_ALLOWLIST} 精确键 + {@code EVAL_*} 前缀透传，
 *       {@code ProcessBuilder.environment()} 先 clear 再放行——三个数据面 seeder
 *       （AdminSeedRunner/DevMarketDataSeed/HitlE2eSeed）读的环境变量缺席即静默跳过，
 *       零代码改动被禁；飞书/邮件凭证缺席 → 子进程外呼天然不可能；</li>
 *   <li><b>命令构造</b>（Task 2 裁定）：{@code java -jar <evalJar> --invest.eval.data-root=<dataDir>}
 *       仅此一参——datasource/hikari/allow-bean-overriding 等覆盖项由 runner 内部构造
 *       （Options.parse 拒未知参数）；库位置经 env 注入 {@code EVAL_DATASOURCE_*} 三元组
 *       （invest.eval.datasource.url 优先、缺省回退主 datasource 解析值——用户名/密码不
 *       依赖白名单外的 POSTGRES_* 回退链）；子进程 cwd=dataDir（报告落
 *       {@code <dataDir>/build/reports/eval-agent/}，runner 相对路径产出位）；</li>
 *   <li><b>超时看护</b>：{@code waitFor(timeoutMinutes)} 超时 {@code destroyForcibly} →
 *       收割按 PARTIAL 语义；</li>
 *   <li><b>退出后</b>：调 {@link EvalHarvester#harvest(long, Path, int, boolean)}。</li>
 * </ol>
 *
 * <p>触发即返回 runId（ProcessBuilder.start() 非阻塞；等待/收割在专属单线程守护 executor
 * 上进行——不引入 @Async/@EnableAsync，调度线程与手动端点都不被整跑时长占住）。RUNNING 行
 * 在触发时先插（triggeredBy 落真值，报告内恒 MANUAL 不采信）。eval 子进程上下文不注册本
 * bean 依赖的调度设施（SchedulingConfig 条件化缺席 → @Scheduled 注解惰性），无需再挂类级条件。
 */
@Component
public class EvalScheduler {

    private static final Logger log = LoggerFactory.getLogger(EvalScheduler.class);

    /**
     * env 白名单精确键（{@code EVAL_*} 前缀另经 {@link #allowed} 透传）：DEEPSEEK 三键 +
     * 主库指向三键 + SPRING_PROFILES_ACTIVE + TZ——seeder/通知凭证/POSTGRES_* 回退键全剔除
     * （库凭据走调度器显式 EVAL_DATASOURCE_* 注入，见类注释第 3 条）。
     */
    public static final Set<String> ENV_ALLOWLIST = Set.of(
            "DEEPSEEK_API_KEY", "DEEPSEEK_MODEL", "DEEPSEEK_BASE_URL",
            "SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME", "SPRING_DATASOURCE_PASSWORD",
            "SPRING_PROFILES_ACTIVE", "TZ");

    private static final String EVAL_ENV_PREFIX = "EVAL_";

    /** 评测数据根缺省（与 EvalRunner.Options.DEFAULT_DATA_ROOT 对齐；调度侧恒以绝对路径传递）。 */
    private static final String DEFAULT_DATA_ROOT = "build/eval-agent";

    /** 子进程报告固定产出位（runner 相对 cwd 的 ReportWriter 输出目录）。 */
    private static final Path REPORT_SUBPATH = Path.of("build", "reports", "eval-agent", "eval-report.json");

    private final EvalRunRepository runRepository;
    private final EvalHarvester harvester;
    private final InvestProperties props;
    private final Environment environment;
    private final Supplier<Map<String, String>> parentEnv;
    private final ProcessStarter starter;
    private final Executor watchExecutor;
    private final java.util.concurrent.atomic.AtomicBoolean running =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * 主构造器（@Autowired：存在测试专用重载构造器时需显式指定注入入口）。父环境经
     * {@code Supplier} 注入（生产装配 infrastructure 的 SystemParentEnvironment——A3/E4
     * 禁 application 直调 System.getenv；测试注入固定 map）。
     */
    @Autowired
    public EvalScheduler(EvalRunRepository runRepository, EvalHarvester harvester,
                         InvestProperties props, Environment environment,
                         Supplier<Map<String, String>> parentEnv) {
        this(runRepository, harvester, props, environment, parentEnv,
                ProcessBuilder::start, singleThreadWatchExecutor());
    }

    /** 测试便利构造：父环境/进程启动/看护执行器全注入假件（单元层零真进程）。 */
    EvalScheduler(EvalRunRepository runRepository, EvalHarvester harvester, InvestProperties props,
                  Environment environment, Supplier<Map<String, String>> parentEnv,
                  ProcessStarter starter, Executor watchExecutor) {
        this.runRepository = runRepository;
        this.harvester = harvester;
        this.props = props;
        this.environment = environment;
        this.parentEnv = parentEnv;
        this.starter = starter;
        this.watchExecutor = watchExecutor;
    }

    /** 进程启动 seam（测试注入假件）。 */
    @FunctionalInterface
    public interface ProcessStarter {
        Process start(ProcessBuilder builder) throws IOException;
    }

    /**
     * 定时入口（02:17 错峰：02 点档现有任务全空，最近为 04:07 清理）：顶层 try/catch 护调度
     * 线程沿 10 任务先例——未启用/缺 key 静默跳过留痕（沿 AgentScopeIntelligenceChatPort
     * 空 key 先例：评测须真实 LLM，无 key 白起子进程必 exit 1）。
     */
    @Scheduled(cron = "0 17 2 * * *", zone = "Asia/Shanghai")
    public void runScheduled() {
        try {
            scheduledOnce();
        } catch (Exception e) { // 调度保护：eval 触发绝不能炸调度线程
            log.error("eval 定时调度异常", e);
        }
    }

    void scheduledOnce() {
        if (!props.getEval().isEnabled()) {
            log.info("eval 定时调度未启用（invest.eval.enabled=false），跳过");
            return;
        }
        if (isBlank(parentEnv.get().get("DEEPSEEK_API_KEY"))) {
            log.info("DEEPSEEK_API_KEY 缺席，eval 定时调度静默跳过（评测须真实 LLM，沿情报域空 key 先例）");
            return;
        }
        try {
            triggerNow("SCHEDULED");
        } catch (EvalRunInProgressException e) { // 与手动运行互斥：本轮跳过是正常语义非异常
            log.info("eval 定时触发与进行中运行互斥，本轮跳过：{}", e.getMessage());
        }
    }

    /**
     * 统一触发入口（定时/手动）：互斥 CAS → 插 RUNNING 行 → 起子进程 → 看护移交 executor
     * 后立即返回 runId。未启用/缺 key 抛 {@link IllegalStateException}（手动端友好失败）；
     * 进行中抛 {@link EvalRunInProgressException}；启动失败行标 FAILED 后抛出。
     */
    public long triggerNow(String triggeredBy) {
        if (!props.getEval().isEnabled()) {
            throw new IllegalStateException("eval 调度未启用（invest.eval.enabled=false）");
        }
        if (isBlank(parentEnv.get().get("DEEPSEEK_API_KEY"))) {
            throw new IllegalStateException("缺少 DEEPSEEK_API_KEY：评测子进程须真实 LLM，请在服务环境变量配置后重启");
        }
        if (!running.compareAndSet(false, true)) {
            throw new EvalRunInProgressException("评测运行进行中（手动与定时互斥），请稍后再试");
        }
        boolean handedOff = false;
        try {
            Path dataDir = resolveDataDir();
            Path reportPath = dataDir.resolve(REPORT_SUBPATH);
            long runId = runRepository.insertRunning(triggeredBy, reportPath.toString());
            Path evalJar = resolveEvalJar();
            ProcessBuilder builder = newProcessBuilder(command(evalJar, dataDir), parentEnv.get());
            builder.directory(dataDir.toFile());
            builder.inheritIO(); // 子进程输出并入服务日志（运维可见逐题进度；不设管道防缓冲死锁）
            applyDatasourceOverlay(builder.environment());
            Process process;
            try {
                process = starter.start(builder);
            } catch (IOException e) {
                runRepository.markFailed(runId, List.of("评测子进程启动失败: " + e.getMessage()));
                throw new IllegalStateException("评测子进程启动失败（" + evalJar + "）", e);
            }
            log.info("eval 子进程已启动：runId={} triggeredBy={} jar={} dataRoot={}",
                    runId, triggeredBy, evalJar, dataDir);
            watchExecutor.execute(() -> awaitAndHarvest(runId, process, reportPath));
            handedOff = true;
            return runId;
        } finally {
            if (!handedOff) { // 未移交看护（插行/启动失败）即就地释放互斥旗
                running.set(false);
            }
        }
    }

    /**
     * 超时看护 + 收割（看护线程执行）：{@code waitFor(timeoutMinutes)} 超时
     * {@code destroyForcibly} 收尸后以 timedOut=true 收割（PARTIAL 语义）；收割自身吞异常
     * （Harvester 内兜底标 FAILED），互斥旗恒在 finally 释放。
     */
    void awaitAndHarvest(long runId, Process process, Path reportPath) {
        boolean timedOut = false;
        int exitCode;
        try {
            if (!process.waitFor(props.getEval().getTimeoutMinutes(), TimeUnit.MINUTES)) {
                timedOut = true;
                log.error("eval 子进程超时（{} 分钟）runId={}，destroyForcibly 后按 PARTIAL 收割",
                        props.getEval().getTimeoutMinutes(), runId);
                process.destroyForcibly();
                process.waitFor();
            }
            exitCode = process.exitValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // 看护线程被中断（停机）：强杀并按超时口径收割
            timedOut = true;
            process.destroyForcibly();
            exitCode = exitCodeOf(process);
        }
        try {
            harvester.harvest(runId, reportPath, exitCode, timedOut);
        } catch (Exception e) { // harvest 内已兜底标 FAILED；此处防御兜底日志（互斥旗仍须释放）
            log.error("eval 收割调用异常（runId={}）", runId, e);
        } finally {
            running.set(false);
        }
    }

    /**
     * 子进程环境构造（白名单过滤）：先 clear 再放行——{@link #ENV_ALLOWLIST} 精确键 +
     * {@code EVAL_*} 前缀，其余父环境变量全剔除。测试/调用方可显式传源环境。
     */
    public ProcessBuilder newProcessBuilder(List<String> command) {
        return newProcessBuilder(command, parentEnv.get());
    }

    ProcessBuilder newProcessBuilder(List<String> command, Map<String, String> sourceEnv) {
        ProcessBuilder builder = new ProcessBuilder(command);
        Map<String, String> env = builder.environment();
        env.clear();
        sourceEnv.forEach((key, value) -> {
            if (allowed(key)) {
                env.put(key, value);
            }
        });
        return builder;
    }

    /** 白名单判定：精确键命中或 EVAL_ 前缀（EVAL_DATASOURCE_ 与 EVAL_ARGS 等透传）。 */
    static boolean allowed(String key) {
        return ENV_ALLOWLIST.contains(key) || key.startsWith(EVAL_ENV_PREFIX);
    }

    /**
     * 库位置显式化（§2.2.3）：invest.eval.datasource.url 优先，缺省回退主 datasource 解析值
     * （Environment 已完成 ${SPRING_DATASOURCE_URL}/${POSTGRES_USER} 占位符解析）；注入
     * {@code EVAL_DATASOURCE_*} 三元组——runner 侧取值链首位，凭据不依赖白名单外的
     * POSTGRES_* 回退键。URL 的双段 currentSchema 由 provisioner 规范化产出，此处传原始形态。
     */
    private void applyDatasourceOverlay(Map<String, String> childEnv) {
        String url = firstNonBlank(props.getEval().getDatasource().getUrl(),
                environment.getProperty("spring.datasource.url"));
        if (url == null) {
            return; // 无 datasource 可解析（理论不可达：application.yml 有缺省值）
        }
        childEnv.put("EVAL_DATASOURCE_URL", url);
        childEnv.put("EVAL_DATASOURCE_USERNAME",
                firstNonBlank(environment.getProperty("spring.datasource.username"), "invest"));
        childEnv.put("EVAL_DATASOURCE_PASSWORD",
                firstNonBlank(environment.getProperty("spring.datasource.password"), "invest"));
    }

    /** 命令构造：java -jar &lt;evalJar&gt; --invest.eval.data-root=&lt;绝对路径&gt;（仅此一参，Task 2 裁定）。 */
    private static List<String> command(Path evalJar, Path dataDir) {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-jar");
        command.add(evalJar.toString());
        command.add("--invest.eval.data-root=" + dataDir.toAbsolutePath());
        return command;
    }

    /** 数据根解析：配置空 = build/eval-agent（本地）；恒转绝对路径（子进程 cwd=dataDir，
     *  相对路径会在子进程侧二次解析造成嵌套 data-root）。 */
    private Path resolveDataDir() {
        String configured = props.getEval().getDataRoot();
        Path dataDir = Path.of(isBlank(configured) ? DEFAULT_DATA_ROOT : configured);
        return dataDir.toAbsolutePath();
    }

    /**
     * evalBootJar 产物定位：配置显式路径优先；空则从本类 code source 派生——生产 jar 内路径
     * 形如 jar:file:&lt;dir&gt;/app.jar!/BOOT-INF/classes!/，取 &lt;dir&gt; 下唯一 {@code *-eval.jar}
     * （§2.1「app.jar 旁分发 app-eval.jar」的运行时实现）。找不到/多义即抛引导性异常
     * （本地 bootRun 的 classes 目录无 jar，须显式配 invest.eval.jar-path）。
     */
    Path resolveEvalJar() {
        String configured = props.getEval().getJarPath();
        if (!isBlank(configured)) {
            return Path.of(configured);
        }
        return resolveEvalJar(applicationJarDirectory());
    }

    /** 目录内唯一 {@code *-eval.jar} 定位（纯函数，供直测）：零候选/多候选均抛引导性异常。 */
    static Path resolveEvalJar(Path directory) {
        List<Path> candidates = new ArrayList<>();
        try (var stream = Files.list(directory)) {
            stream.filter(p -> p.getFileName().toString().endsWith("-eval.jar")
                            && Files.isRegularFile(p))
                    .forEach(candidates::add);
        } catch (IOException e) {
            throw new IllegalStateException("evalBootJar 产物目录不可读（" + directory
                    + "），请显式配置 invest.eval.jar-path", e);
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        throw new IllegalStateException(candidates.isEmpty()
                ? "未在 " + directory + " 找到 *-eval.jar（部署形态须与 app.jar 同目录分发，"
                        + "或显式配置 invest.eval.jar-path）"
                : directory + " 下存在多个 *-eval.jar（" + candidates + "），请显式配置 invest.eval.jar-path");
    }

    /** 本类 code source 所在目录（生产 jar / dev classes 两形态）。 */
    private static Path applicationJarDirectory() {
        try {
            return directoryOf(EvalScheduler.class.getProtectionDomain()
                    .getCodeSource().getLocation().toString());
        } catch (Exception e) {
            throw new IllegalStateException("无法定位应用 jar 目录（evalBootJar 派生失败）", e);
        }
    }

    /**
     * code source 位置串 → 所在目录（纯函数，供直测）：BootJar 内类路径
     * {@code jar:file:<dir>/app.jar!/BOOT-INF/classes!/} 取 jar 文件目录；dev/classes 的
     * {@code file:<dir>/classes/java/main} 形态取目录本身父级。
     */
    static Path directoryOf(String locationSpec) {
        if (locationSpec.startsWith("jar:file:")) {
            String jarPath = locationSpec.substring("jar:file:".length());
            int bang = jarPath.indexOf('!');
            return Path.of(bang > 0 ? jarPath.substring(0, bang) : jarPath).getParent();
        }
        if (locationSpec.startsWith("file:")) {
            return Path.of(java.net.URI.create(locationSpec)).getParent();
        }
        throw new IllegalArgumentException("无法识别的 code source 位置: " + locationSpec);
    }

    private static int exitCodeOf(Process process) {
        try {
            return process.exitValue();
        } catch (IllegalThreadStateException e) { // 强杀未及收尸：以非零哨兵口径收割
            return -1;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (!isBlank(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static ExecutorService singleThreadWatchExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "eval-watch");
            thread.setDaemon(true); // 守护：不阻 JVM 退出（收割被打断的行由下次收割/人工核对）
            return thread;
        });
    }

    /** 停机清理：放弃排队看护任务（进行中的收割尽快结束；RUNNING 行留痕由运维/下轮核对）。 */
    @PreDestroy
    void shutdown() {
        if (watchExecutor instanceof ExecutorService service) {
            service.shutdown();
        }
    }
}
