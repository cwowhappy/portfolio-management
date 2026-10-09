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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.eval.EvalRunRepository;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.core.env.Environment;

/**
 * 调度器单元守护（MS-30 B4，设计规格 §2.2 五步）：env 白名单剔除 seeder/通知凭证与
 * POSTGRES_*（用户名/密码走调度器显式 EVAL_DATASOURCE_* 三元组注入，不依赖白名单外的
 * 回退链）；缺 DEEPSEEK_API_KEY 静默跳过留痕（沿 AgentScopeIntelligenceChatPort 空 key
 * 先例）；手动/定时互斥（二次触发抛 EvalRunInProgressException）；超时 destroyForcibly
 * → 收割 PARTIAL 语义；启动失败行标 FAILED 且互斥旗释放。进程/执行器/父环境全注入假件，
 * 单元层零真进程零 Docker。
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

    @BeforeEach
    void setUp() {
        props = new InvestProperties();
        props.getEval().setEnabled(true);
        props.getEval().setJarPath("/tmp/ms30/app-eval.jar");
        props.getEval().setDataRoot(dataDir.toString());
        props.getEval().setTimeoutMinutes(1);
        parentEnv = new HashMap<>();
        parentEnv.put("DEEPSEEK_API_KEY", "sk-test");
        parentEnv.put("TZ", "Asia/Shanghai");
        when(environment.getProperty("spring.datasource.url"))
                .thenReturn("jdbc:postgresql://localhost:5432/invest");
        when(environment.getProperty("spring.datasource.username")).thenReturn("invest");
        when(environment.getProperty("spring.datasource.password")).thenReturn("invest");
        when(runRepository.insertRunning(anyString(), anyString())).thenReturn(7L);
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
        // 首次触发仅落一行 RUNNING，被拒的二次触发不产生新行
        verify(runRepository).insertRunning(eq("MANUAL"), anyString());
        verify(runRepository, never()).insertRunning(eq("SCHEDULED"), anyString());
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
        Path report = dataDir.toAbsolutePath()
                .resolve(Path.of("build", "reports", "eval-agent", "eval-report.json"));
        verify(runRepository).insertRunning("SCHEDULED", report.toString());
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
    @DisplayName("给定子进程正常退出，when看护执行，then收割收到退出码且互斥旗释放")
    void givenWatchInlineAndProcessExits_whenAwaitAndHarvest_thenHarvestCalledWithExitCodeAndFlagReleased() {
        when(runRepository.insertRunning(anyString(), anyString())).thenReturn(7L, 14L);
        EvalScheduler scheduler = new EvalScheduler(runRepository, harvester, props, environment,
                () -> parentEnv,
                pb -> {
                    started.add(pb);
                    return new FakeProcess(0, true);
                },
                Runnable::run);

        scheduler.triggerNow("SCHEDULED");

        Path report = dataDir.toAbsolutePath()
                .resolve(Path.of("build", "reports", "eval-agent", "eval-report.json"));
        verify(harvester).harvest(7L, report, 0, false);
        // 旗已释放：紧接的再次触发不再互斥拒绝（新 runId 正常收割）
        scheduler.triggerNow("SCHEDULED");
        verify(harvester).harvest(14L, report, 0, false);
    }

    @Test
    @DisplayName("给定子进程超时未退，when看护执行，then destroyForcibly 并以超时口径收割")
    void givenProcessHangs_whenAwaitAndHarvest_thenDestroyForciblyAndHarvestTimedOut() {
        FakeProcess process = new FakeProcess(137, false);
        EvalScheduler scheduler = new EvalScheduler(runRepository, harvester, props, environment,
                () -> parentEnv, pb -> process, Runnable::run);

        scheduler.triggerNow("SCHEDULED");

        assertThat(process.destroyed).isTrue();
        verify(harvester).harvest(eq(7L), any(Path.class), eq(137), eq(true));
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

        // 旗已释放：换可启动的假件后可再次触发
        EvalScheduler retry = new EvalScheduler(runRepository, harvester, props, environment,
                () -> parentEnv, pb -> new FakeProcess(0, true), watchTasks::add);
        retry.triggerNow("SCHEDULED");
        verify(runRepository, times(2)).insertRunning(anyString(), anyString());
    }

    @Test
    @DisplayName("给定收割自身异常，when看护执行，then互斥旗仍释放（下轮触发不被卡死）")
    void givenHarvestThrows_whenAwaitAndHarvest_thenFlagStillReleased() {
        doThrow(new IllegalStateException("收割崩溃"))
                .when(harvester).harvest(anyLong(), any(Path.class), anyInt(), anyBoolean());
        EvalScheduler scheduler = new EvalScheduler(runRepository, harvester, props, environment,
                () -> parentEnv, pb -> new FakeProcess(0, true), Runnable::run);

        scheduler.triggerNow("SCHEDULED");

        scheduler.triggerNow("SCHEDULED"); // 不抛 InProgress 即旗已释放
        verify(runRepository, times(2)).insertRunning(anyString(), anyString());
    }

    // ———— evalBootJar 派生（部署关键路径） ————

    @Test
    @DisplayName("给定生产 jar/dev classes 两形态 code source，when解析目录，then各取所在目录")
    void givenBootJarCodeSource_whenDirectoryOf_thenAppJarDirectory() {
        assertThat(EvalScheduler.directoryOf("jar:file:/opt/invest/app.jar!/BOOT-INF/classes!/"))
                .isEqualTo(Path.of("/opt/invest"));
        assertThat(EvalScheduler.directoryOf("file:/repo/backend/build/classes/java/main"))
                .isEqualTo(Path.of("/repo/backend/build/classes/java"));
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

    /** 可脚本化子进程：waitFor(long,unit) 返回值与退出码可控，destroyForcibly 留痕。 */
    private static final class FakeProcess extends Process {
        private final int exitCode;
        private final boolean finishesWithinTimeout;
        boolean destroyed;

        private FakeProcess(int exitCode, boolean finishesWithinTimeout) {
            this.exitCode = exitCode;
            this.finishesWithinTimeout = finishesWithinTimeout;
        }

        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public boolean waitFor(long timeout, TimeUnit unit) { return finishesWithinTimeout; }
        @Override public int waitFor() { return exitCode; }
        @Override public int exitValue() { return exitCode; }
        @Override public void destroy() { }
        @Override public Process destroyForcibly() { destroyed = true; return this; }
        @Override public boolean isAlive() { return !finishesWithinTimeout && !destroyed; }
    }
}
