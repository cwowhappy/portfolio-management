package com.portfolio.invest.application.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.eval.EvalRunRepository;
import com.portfolio.invest.domain.eval.EvalRunRow;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.core.env.Environment;

/**
 * 调度器单元守护（MS-30 B4，设计规格 §2.2 五步 + 跟进②两轨顺序）：env 白名单剔除
 * seeder/通知凭证与 POSTGRES_*（用户名/密码走调度器显式 EVAL_DATASOURCE_* 三元组注入，
 * 不依赖白名单外的回退链）；缺 DEEPSEEK_API_KEY 静默跳过留痕（沿 AgentScopeIntelligenceChatPort
 * 空 key 先例）；手动/定时互斥（二次触发抛 EvalRunInProgressException，互斥窗覆盖两轨全程）；
 * 互斥窗内顺序两轨——对话轨（timeout-minutes）→ 抽取轨（--track=extraction，
 * extraction-timeout-minutes 独立超时），各自落一行 eval_run（track 列区分）、任一轨失败
 * 不挡另一轨收割；超时 destroyForcibly → 收割 PARTIAL 语义；启动失败行标 FAILED 且互斥旗
 * 释放。进程/执行器/父环境全注入假件，单元层零真进程零 Docker。
 */
class EvalSchedulerTest {

    private final EvalRunRepository runRepository = mock(EvalRunRepository.class);
    private final EvalHarvester harvester = mock(EvalHarvester.class);
    private final Environment environment = mock(Environment.class);

    private InvestProperties props;
    private Map<String, String> parentEnv;
    private final List<ProcessBuilder> started = new ArrayList<>();
    private final List<Runnable> watchTasks = new ArrayList<>();

    @TempDir
    Path dataDir;

    /** 对话轨报告固定产出位（runner 相对 cwd 的 ReportWriter 输出目录）。 */
    private Path agentReport() {
        return dataDir.toAbsolutePath()
                .resolve(Path.of("build", "reports", "eval-agent", "eval-report.json"));
    }

    /** 抽取轨报告固定产出位（ExtractionEvalRunner 相对 cwd 的 REPORT_DIR）。 */
    private Path extractionReport() {
        return dataDir.toAbsolutePath()
                .resolve(Path.of("build", "reports", "eval-extraction", "eval-report.json"));
    }

    @BeforeEach
    void setUp() {
        props = new InvestProperties();
        props.getEval().setEnabled(true);
        props.getEval().setJarPath("/tmp/ms30/app-eval.jar");
        props.getEval().setDataRoot(dataDir.toString());
        props.getEval().setTimeoutMinutes(1);
        props.getEval().setExtractionTimeoutMinutes(5);
        parentEnv = new HashMap<>();
        parentEnv.put("DEEPSEEK_API_KEY", "sk-test");
        parentEnv.put("TZ", "Asia/Shanghai");
        when(environment.getProperty("spring.datasource.url"))
                .thenReturn("jdbc:postgresql://localhost:5432/invest");
        when(environment.getProperty("spring.datasource.username")).thenReturn("invest");
        when(environment.getProperty("spring.datasource.password")).thenReturn("invest");
        when(runRepository.insertRunning(anyString(), anyString(), anyString())).thenReturn(7L);
    }

    private EvalScheduler scheduler() {
        return new EvalScheduler(runRepository, harvester, props, environment,
                () -> parentEnv,
                pb -> {
                    started.add(pb);
                    return new FakeProcess(0, true);
                },
                watchTasks::add);
    }

    /** 调度器全量白名单源环境：应放行键 + 应剔除的 seeder/通知/回退凭证。 */
    private static Map<String, String> sourceEnvWithSecrets() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("DEEPSEEK_API_KEY", "sk-x");
        env.put("DEEPSEEK_MODEL", "deepseek-v4-flash");
        env.put("DEEPSEEK_BASE_URL", "https://api.deepseek.com");
        env.put("SPRING_DATASOURCE_URL", "jdbc:postgresql://localhost:5432/invest");
        env.put("SPRING_DATASOURCE_USERNAME", "invest");
        env.put("SPRING_DATASOURCE_PASSWORD", "invest");
        env.put("SPRING_PROFILES_ACTIVE", "prod");
        env.put("TZ", "Asia/Shanghai");
        env.put("EVAL_DATASOURCE_URL", "jdbc:postgresql://localhost:5432/invest");
        env.put("EVAL_ARGS", "--list");
        env.put("ADMIN_USERNAME", "admin");
        env.put("ADMIN_PASSWORD", "secret");
        env.put("E2E_DEV_SEED", "true");
        env.put("E2E_HITL_MCP_URL", "http://localhost:9999/mcp");
        env.put("FEISHU_APP_ID", "cli_x");
        env.put("FEISHU_APP_SECRET", "feishu-secret");
        env.put("MAIL_HOST", "smtp.example.com");
        env.put("ALERT_MAIL_TO", "ops@example.com");
        env.put("POSTGRES_USER", "invest");
        env.put("POSTGRES_PASSWORD", "invest");
        return env;
    }

    @Test
    @DisplayName("给定含 seeder/通知/回退凭证的父环境，when构造子进程环境，then仅白名单键放行（EVAL_* 前缀透传）")
    void givenParentEnvWithSecrets_whenNewProcessBuilder_thenOnlyAllowlistedKeysPass() {
        ProcessBuilder pb = scheduler().newProcessBuilder(List.of("java", "-version"), sourceEnvWithSecrets());

        Map<String, String> env = pb.environment();
        assertThat(env).containsKeys("DEEPSEEK_API_KEY", "SPRING_DATASOURCE_URL", "TZ",
                "DEEPSEEK_MODEL", "DEEPSEEK_BASE_URL", "SPRING_PROFILES_ACTIVE",
                "EVAL_DATASOURCE_URL", "EVAL_ARGS");
        assertThat(env).doesNotContainKeys("ADMIN_USERNAME", "ADMIN_PASSWORD", "E2E_DEV_SEED",
                "E2E_HITL_MCP_URL", "FEISHU_APP_ID", "FEISHU_APP_SECRET", "MAIL_HOST",
                "ALERT_MAIL_TO", "POSTGRES_USER", "POSTGRES_PASSWORD");
        // 白名单外一律剔除（环境里只剩白名单命中的 10 键，无任何夹带）
        assertThat(env).hasSize(10);
    }

    @Test
    @DisplayName("给定调度未启用，when定时入口执行，then直接跳过（不落 RUNNING 行不起进程）")
    void givenEvalDisabled_whenScheduledOnce_thenSkipsWithoutTrigger() {
        props.getEval().setEnabled(false);

        scheduler().scheduledOnce();

        verifyNoInteractions(runRepository, harvester);
        assertThat(started).isEmpty();
    }

    @Test
    @DisplayName("给定启用但 DEEPSEEK_API_KEY 缺席，when定时入口执行，then静默跳过留痕不触发（沿空 key 先例）")
    void givenEnabledButApiKeyMissing_whenScheduledOnce_thenSkipsSilently() {
        parentEnv.remove("DEEPSEEK_API_KEY");

        scheduler().scheduledOnce();

        verifyNoInteractions(runRepository, harvester);
        assertThat(started).isEmpty();
    }

    @Test
    @DisplayName("给定评测运行进行中，when再次触发，then抛 EvalRunInProgressException（手动/定时互斥）")
    void givenRunInProgress_whenTriggerNowAgain_thenThrowsInProgress() {
        EvalScheduler scheduler = scheduler();
        scheduler.triggerNow("MANUAL");

        assertThatThrownBy(() -> scheduler.triggerNow("SCHEDULED"))
                .isInstanceOf(EvalRunInProgressException.class);
        // 首次触发仅落对话轨 RUNNING 行（抽取轨行在看护任务内插），被拒的二次触发不产生新行
        verify(runRepository).insertRunning(eq("MANUAL"), eq(EvalRunRow.TRACK_AGENT), anyString());
        verify(runRepository, never()).insertRunning(eq("SCHEDULED"), anyString(), anyString());
    }

    @Test
    @DisplayName("给定触发，when命令构造，then java -jar 仅一参 data-root（绝对路径）+ cwd=data-root + 库三元组注入")
    void givenTriggerNow_whenCommandCaptured_thenJavaJarWithSingleDataRootArgAndDatasourceTriple() {
        scheduler().triggerNow("SCHEDULED");

        assertThat(started).hasSize(1);
        ProcessBuilder pb = started.get(0);
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        assertThat(pb.command()).containsExactly(java, "-jar", "/tmp/ms30/app-eval.jar",
                "--invest.eval.data-root=" + dataDir.toAbsolutePath());
        assertThat(pb.directory()).isEqualTo(dataDir.toAbsolutePath().toFile());
        // 库位置显式化：invest.eval.datasource.url 优先/缺省回退主 datasource，注入 EVAL_DATASOURCE_*
        // 三元组（与 runner 取值链首位一致——用户名/密码不依赖白名单外的 POSTGRES_*）
        assertThat(pb.environment())
                .containsEntry("EVAL_DATASOURCE_URL", "jdbc:postgresql://localhost:5432/invest")
                .containsEntry("EVAL_DATASOURCE_USERNAME", "invest")
                .containsEntry("EVAL_DATASOURCE_PASSWORD", "invest");
        // RUNNING 行先插（triggeredBy 落真值；报告路径为 data-root 下 runner 固定产出位）
        verify(runRepository).insertRunning("SCHEDULED", EvalRunRow.TRACK_AGENT, agentReport().toString());
    }

    @Test
    @DisplayName("给定显式评测库 URL，when触发，then覆盖主 datasource 回退（EVAL_DATASOURCE_URL 显式指定）")
    void givenExplicitEvalDatasourceUrl_whenTriggerNow_thenOverridesPrimaryFallback() {
        props.getEval().getDatasource().setUrl("jdbc:postgresql://evalhost:5432/invest");

        scheduler().triggerNow("SCHEDULED");

        assertThat(started.get(0).environment())
                .containsEntry("EVAL_DATASOURCE_URL", "jdbc:postgresql://evalhost:5432/invest");
    }

    @Test
    @DisplayName("给定子进程正常退出，when看护执行，then两轨各起一进程各落一行各自收割且互斥旗释放")
    void givenWatchInlineAndProcessesExit_whenTriggerNow_thenTwoTracksHarvestedAndFlagReleased() {
        when(runRepository.insertRunning(anyString(), anyString(), anyString()))
                .thenReturn(7L, 8L, 21L, 22L);
        EvalScheduler scheduler = new EvalScheduler(runRepository, harvester, props, environment,
                () -> parentEnv,
                pb -> {
                    started.add(pb);
                    return new FakeProcess(0, true);
                },
                Runnable::run);

        scheduler.triggerNow("SCHEDULED");

        // 两轨顺序：对话轨（data-root 一参）→ 抽取轨（--track=extraction，纯 JVM 无 data-root——
        // 抽取 runner 拒未知参数，报告落 cwd 相对位 build/reports/eval-extraction）
        assertThat(started).hasSize(2);
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        assertThat(started.get(0).command()).containsExactly(java, "-jar", "/tmp/ms30/app-eval.jar",
                "--invest.eval.data-root=" + dataDir.toAbsolutePath());
        assertThat(started.get(1).command()).containsExactly(java, "-jar", "/tmp/ms30/app-eval.jar",
                "--track=extraction");
        // 两轨各自落一行 eval_run：triggeredBy 同值、track 列区分、报告路径各归各
        verify(runRepository).insertRunning("SCHEDULED", EvalRunRow.TRACK_AGENT, agentReport().toString());
        verify(runRepository).insertRunning("SCHEDULED", EvalRunRow.TRACK_EXTRACT,
                extractionReport().toString());
        // 各自收割（独立报告路径 + 退出码）；抽取轨子进程同享 cwd 与库三元组（环境构造同通道）
        verify(harvester).harvest(7L, agentReport(), 0, false);
        verify(harvester).harvest(8L, extractionReport(), 0, false);
        assertThat(started.get(1).directory()).isEqualTo(dataDir.toAbsolutePath().toFile());
        assertThat(started.get(1).environment())
                .containsEntry("EVAL_DATASOURCE_URL", "jdbc:postgresql://localhost:5432/invest");
        // 顺序性：对话轨收割先于抽取轨插行（顺序两轨，非并行）
        InOrder inOrder = inOrder(runRepository, harvester);
        inOrder.verify(harvester).harvest(eq(7L), eq(agentReport()), eq(0), eq(false));
        inOrder.verify(runRepository).insertRunning(eq("SCHEDULED"), eq(EvalRunRow.TRACK_EXTRACT),
                eq(extractionReport().toString()));

        // 旗已释放：紧接的再次触发不再互斥拒绝（新两轨正常收割）
        scheduler.triggerNow("SCHEDULED");
        verify(harvester).harvest(21L, agentReport(), 0, false);
        verify(harvester).harvest(22L, extractionReport(), 0, false);
    }

    @Test
    @DisplayName("给定两轨进程先后挂起，when看护执行，then各按各的超时值 destroyForcibly 并以超时口径收割")
    void givenBothProcessesHang_whenAwaitAndHarvest_thenDestroyedWithIndependentTimeouts() {
        List<FakeProcess> processes = new ArrayList<>();
        when(runRepository.insertRunning(anyString(), anyString(), anyString()))
                .thenReturn(7L, 8L);
        EvalScheduler scheduler = new EvalScheduler(runRepository, harvester, props, environment,
                () -> parentEnv,
                pb -> {
                    started.add(pb);
                    FakeProcess process = new FakeProcess(137, false);
                    processes.add(process);
                    return process;
                },
                Runnable::run);

        scheduler.triggerNow("SCHEDULED");

        // 独立超时：对话轨 timeout-minutes=1，抽取轨 extraction-timeout-minutes=5（waitFor 入参实证）
        assertThat(processes).hasSize(2);
        assertThat(processes.get(0).destroyed).isTrue();
        assertThat(processes.get(0).lastWaitTimeoutMinutes).isEqualTo(1);
        assertThat(processes.get(1).destroyed).isTrue();
        assertThat(processes.get(1).lastWaitTimeoutMinutes).isEqualTo(5);
        verify(harvester).harvest(eq(7L), eq(agentReport()), eq(137), eq(true));
        verify(harvester).harvest(eq(8L), eq(extractionReport()), eq(137), eq(true));
    }

    @Test
    @DisplayName("给定抽取轨子进程启动失败，when看护执行，then抽取行标 FAILED 但对话轨收割不受挡（互斥旗仍释放）")
    void givenExtractionStartFails_whenWatchRuns_thenExtractionRowFailedButAgentHarvested() {
        AtomicInteger starts = new AtomicInteger();
        when(runRepository.insertRunning(anyString(), anyString(), anyString()))
                .thenReturn(7L, 8L);
        EvalScheduler scheduler = new EvalScheduler(runRepository, harvester, props, environment,
                () -> parentEnv,
                pb -> {
                    started.add(pb);
                    if (starts.incrementAndGet() == 2) { // 恰在抽取轨那次起进程失败（第 1 次=对话轨）
                        throw new IOException("抽取轨 jar 启动失败");
                    }
                    return new FakeProcess(0, true);
                },
                Runnable::run);

        scheduler.triggerNow("SCHEDULED");

        // 对话轨收割照常完成
        verify(harvester).harvest(7L, agentReport(), 0, false);
        // 抽取行已插（8L）标 FAILED + 引导性理由留痕
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> reasons = ArgumentCaptor.forClass((Class) List.class);
        verify(runRepository).markFailed(eq(8L), reasons.capture());
        assertThat(reasons.getValue().get(0)).contains("抽取轨");
        verify(harvester, never()).harvest(eq(8L), any(Path.class), anyInt(), anyBoolean());
        // 旗已释放：再次触发不被卡死（第二次触发两轨各一行——首轮 2 行 + 本轮 2 行）
        scheduler.triggerNow("MANUAL");
        verify(runRepository, times(4)).insertRunning(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("给定收割自身异常，when看护执行两轨，then互斥旗仍释放（下轮触发不被卡死）")
    void givenHarvestThrows_whenAwaitAndHarvest_thenFlagStillReleased() {
        doThrow(new IllegalStateException("收割崩溃"))
                .when(harvester).harvest(anyLong(), any(Path.class), anyInt(), anyBoolean());
        EvalScheduler scheduler = new EvalScheduler(runRepository, harvester, props, environment,
                () -> parentEnv, pb -> new FakeProcess(0, true), Runnable::run);

        scheduler.triggerNow("SCHEDULED"); // 两轨收割均抛均被吞，抽取轨照常起跑

        scheduler.triggerNow("SCHEDULED"); // 不抛 InProgress 即旗已释放
        // 每次触发两行（AGENT + EXTRACT）× 2 次 = 4 行
        verify(runRepository, times(4)).insertRunning(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("给定子进程启动失败，when触发，then行标 FAILED、互斥旗释放并抛出引导异常")
    void givenStarterIoError_whenTriggerNow_thenRowMarkedFailedAndFlagReleased() {
        EvalScheduler scheduler = new EvalScheduler(runRepository, harvester, props, environment,
                () -> parentEnv,
                pb -> {
                    throw new IOException("jar 不存在");
                },
                Runnable::run);

        assertThatThrownBy(() -> scheduler.triggerNow("SCHEDULED"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("评测子进程启动失败");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> reasons = ArgumentCaptor.forClass((Class) List.class);
        verify(runRepository).markFailed(eq(7L), reasons.capture());
        assertThat(reasons.getValue().get(0)).contains("评测子进程启动失败");

        // 旗已释放：换可启动的假件后可再次触发（抽取轨行在看护内插，此处仅对话轨行）
        EvalScheduler retry = new EvalScheduler(runRepository, harvester, props, environment,
                () -> parentEnv, pb -> new FakeProcess(0, true), watchTasks::add);
        retry.triggerNow("SCHEDULED");
        verify(runRepository, times(2)).insertRunning(anyString(), anyString(), anyString());
    }

    // ———— evalBootJar 派生（部署关键路径） ————

    @Test
    @DisplayName("给定生产 jar/dev classes/nested 三形态 code source，when解析目录，then各取所在目录")
    void givenBootJarCodeSource_whenDirectoryOf_thenAppJarDirectory() {
        assertThat(EvalScheduler.directoryOf("jar:file:/opt/invest/app.jar!/BOOT-INF/classes!/"))
                .isEqualTo(Path.of("/opt/invest"));
        // Boot 3.2+ fat jar 的 nested 形态（审查 C3 实证）：jar:nested:<dir>/app.jar/!BOOT-INF/classes!/
        assertThat(EvalScheduler.directoryOf("jar:nested:/opt/invest/app.jar/!BOOT-INF/classes!/"))
                .isEqualTo(Path.of("/opt/invest"));
        assertThat(EvalScheduler.directoryOf("file:/repo/backend/build/classes/java/main"))
                .isEqualTo(Path.of("/repo/backend/build/classes/java"));
    }

    @Test
    @DisplayName("给定上一跑残留的同名报告（两轨），when再次触发，then先删旧报告（json+md）防跨跑污染")
    void givenStaleReportFromPreviousRun_whenTriggerNow_thenStaleReportDeleted() throws Exception {
        Path agentDir = dataDir.resolve(Path.of("build", "reports", "eval-agent"));
        Path extractionDir = dataDir.resolve(Path.of("build", "reports", "eval-extraction"));
        Files.createDirectories(agentDir);
        Files.createDirectories(extractionDir);
        Path staleJson = agentDir.resolve("eval-report.json");
        Path staleMd = agentDir.resolve("eval-report.md");
        Files.writeString(staleJson, "上一跑残留");
        Files.writeString(staleMd, "上一跑残留");
        Path staleExtractionJson = extractionDir.resolve("eval-report.json");
        Path staleExtractionMd = extractionDir.resolve("eval-report.md");
        Files.writeString(staleExtractionJson, "上一跑残留");
        Files.writeString(staleExtractionMd, "上一跑残留");

        scheduler().triggerNow("SCHEDULED");

        // 起子进程前已删两轨旧报告——假 starter 捕获的时点即真实 ProcessBuilder.start() 前夜
        assertThat(started).hasSize(1);
        assertThat(staleJson).doesNotExist();
        assertThat(staleMd).doesNotExist();
        assertThat(staleExtractionJson).doesNotExist();
        assertThat(staleExtractionMd).doesNotExist();
    }

    @Test
    @DisplayName("给定配置的数据根目录不存在，when触发，then先建目录再起子进程（防 start IOException）")
    void givenDataRootMissing_whenTriggerNow_thenDirectoryCreatedBeforeStart() {
        props.getEval().setDataRoot(dataDir.resolve("not-yet-created/deeper").toString());

        scheduler().triggerNow("SCHEDULED");

        assertThat(started).hasSize(1);
        assertThat(dataDir.resolve("not-yet-created/deeper")).isDirectory();
    }

    @Test
    @DisplayName("给定运行中子进程，when停机清理，then destroyForcibly 活进程（不留孤儿烧 LLM 预算）")
    void givenLiveProcess_whenShutdown_thenProcessDestroyedForcibly() {
        FakeProcess process = new FakeProcess(0, false);
        EvalScheduler scheduler = new EvalScheduler(runRepository, harvester, props, environment,
                () -> parentEnv, pb -> process, watchTasks::add); // 看护不执行 → 进程保持「活」
        scheduler.triggerNow("SCHEDULED");

        scheduler.shutdown();

        assertThat(process.destroyed).isTrue();
    }

    @Test
    @DisplayName("给定互斥窗覆盖两轨，when对话轨未收割时再触发，then仍互斥拒绝；两轨全终态后旗才释放")
    void givenMutexWindowCoversBothTracks_whenSecondTriggerDuringWindow_thenRejectedUntilBothDone() {
        EvalScheduler scheduler = scheduler();
        long runId = scheduler.triggerNow("SCHEDULED");

        assertThat(runId).isEqualTo(7L);
        // 看护未执行：对话轨仍在跑，互斥窗内二次触发被拒（窗覆盖两轨全程，非仅对话轨）
        assertThatThrownBy(() -> scheduler.triggerNow("MANUAL"))
                .isInstanceOf(EvalRunInProgressException.class);

        watchTasks.forEach(Runnable::run); // 执行看护：对话轨收割 → 抽取轨起跑收割 → 旗释放

        scheduler.triggerNow("MANUAL"); // 不抛 InProgress 即两轨全程后旗已释放
    }

    @Test
    @DisplayName("给定停机落在对话轨执行中（审查 I1），when看护执行，then对话轨照常收割但抽取轨跳过不插行")
    void givenShutdownDuringAgentTrack_whenWatchRuns_thenExtractionSkippedWithoutRow() {
        FakeProcess agentProcess = new FakeProcess(0, true);
        EvalScheduler scheduler = new EvalScheduler(runRepository, harvester, props, environment,
                () -> parentEnv,
                pb -> {
                    started.add(pb);
                    return agentProcess;
                },
                watchTasks::add); // 看护不自动执行 → 模拟对话轨仍在跑
        scheduler.triggerNow("SCHEDULED");

        // 停机：destroyForcibly 对话轨 + 置停机旗（executor 为测试注入 lambda 非 ExecutorService，
        // 不触发 service.shutdown——真实停机同样从不中断看护线程，I1 的盲区正于此）
        scheduler.shutdown();
        assertThat(agentProcess.destroyed).isTrue();

        watchTasks.forEach(Runnable::run); // 看护：对话轨强杀后 waitFor 正常返回照常收割 → 停机旗真 → 抽取轨跳过

        // 对话轨收割不受挡（destroyForcibly 后按退出码 0 正常收割）
        verify(harvester).harvest(7L, agentReport(), 0, false);
        // 抽取轨未起子进程、EXTRACT 行未插（防停机窗口孤儿烧预算 + RUNNING 行滞留）
        assertThat(started).hasSize(1);
        verify(runRepository, never()).insertRunning(anyString(), eq(EvalRunRow.TRACK_EXTRACT),
                anyString());
        // 互斥旗仍释放（看护收尾 finally）
        scheduler.triggerNow("MANUAL");
    }

    @Test
    @DisplayName("给定目录内唯一 *-eval.jar，when派生，then命中；零/多候选抛引导异常")
    void givenDirectoryWithSingleEvalJar_whenResolveEvalJar_thenUniqueCandidateOrGuidedFailure(
            @TempDir Path dir) throws Exception {
        Path jar = dir.resolve("invest-agent-backend-0.1.0-eval.jar");
        Files.writeString(jar, "stub");

        assertThat(EvalScheduler.resolveEvalJar(dir)).isEqualTo(jar);

        Files.createDirectory(dir.resolve("empty"));
        assertThatThrownBy(() -> EvalScheduler.resolveEvalJar(dir.resolve("empty")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invest.eval.jar-path");

        Files.writeString(dir.resolve("another-eval.jar"), "stub");
        assertThatThrownBy(() -> EvalScheduler.resolveEvalJar(dir))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("多个");
    }

    // ———— 假件 ————

    /** 可脚本化子进程：waitFor(long,unit) 返回值与退出码可控（并记最后收到的超时分钟数），destroyForcibly 留痕。 */
    private static final class FakeProcess extends Process {
        private final int exitCode;
        private final boolean finishesWithinTimeout;
        boolean destroyed;
        long lastWaitTimeoutMinutes = -1;

        private FakeProcess(int exitCode, boolean finishesWithinTimeout) {
            this.exitCode = exitCode;
            this.finishesWithinTimeout = finishesWithinTimeout;
        }

        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public boolean waitFor(long timeout, TimeUnit unit) {
            lastWaitTimeoutMinutes = unit.toMinutes(timeout);
            return finishesWithinTimeout;
        }
        @Override public int waitFor() { return exitCode; }
        @Override public int exitValue() { return exitCode; }
        @Override public void destroy() { }
        @Override public Process destroyForcibly() { destroyed = true; return this; }
        @Override public boolean isAlive() { return !finishesWithinTimeout && !destroyed; }
    }
}
