package com.portfolio.invest.application.memory;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.memory.MemoryDigestCursor;
import com.portfolio.invest.domain.memory.MemoryDigestCursorRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 调度器三态守护（MS-31 B6，设计规格 §4.1 三段式沿 EvalScheduler:154-177 模板）：
 * digest-enabled 开关缺席跳过、DEEPSEEK_API_KEY 缺席静默跳过留痕（缺席时整批不起——
 * 端口会全程 empty，若照跑会把窗口事件「成功」跳过并推进游标造成永久漏摘要）、
 * 游标缺失 WARN 跳过（V7 seed 不变量破坏可观测）、批异常顶层吞掉护调度线程。
 */
class MemoryDigestSchedulerTest {

    private static final Instant LAST_SUCCESS = Instant.parse("2026-10-09T13:07:00Z");

    private final MemoryDigestService digestService = mock(MemoryDigestService.class);
    private final MemoryDigestCursorRepository cursorRepository = mock(MemoryDigestCursorRepository.class);
    private final InvestProperties props = new InvestProperties();
    private final Map<String, String> parentEnv = new HashMap<>();

    private MemoryDigestScheduler scheduler;

    @BeforeEach
    void setUp() {
        parentEnv.put("DEEPSEEK_API_KEY", "sk-test");
        scheduler = new MemoryDigestScheduler(digestService, cursorRepository, props,
                () -> parentEnv);
        when(cursorRepository.find()).thenReturn(Optional.of(new MemoryDigestCursor(LAST_SUCCESS)));
    }

    @Test
    @DisplayName("给定 digest-enabled=false，when定时入口执行，then直接跳过（不读游标不调 service）")
    void givenDigestDisabled_whenScheduledOnce_thenSkipsWithoutServiceCall() {
        props.getProfile().setDigestEnabled(false);

        scheduler.scheduledOnce();

        verifyNoInteractions(digestService, cursorRepository);
    }

    @Test
    @DisplayName("给定启用但 DEEPSEEK_API_KEY 缺席，when定时入口执行，then静默跳过留痕不调 service")
    void givenEnabledButApiKeyMissing_whenScheduledOnce_thenSkipsSilently() {
        parentEnv.remove("DEEPSEEK_API_KEY");

        scheduler.scheduledOnce();

        verifyNoInteractions(digestService, cursorRepository);
    }

    @Test
    @DisplayName("给定启用且有 key 与游标，when定时入口执行，then以游标时刻为窗口起调 digestAll")
    void givenEnabledWithCursor_whenScheduledOnce_thenDigestAllFromCursor() {
        scheduler.scheduledOnce();

        verify(digestService).digestAll(LAST_SUCCESS);
    }

    @Test
    @DisplayName("给定游标单行缺失（seed 不变量破坏），when定时入口执行，thenWARN 跳过不调 service")
    void givenCursorMissing_whenScheduledOnce_thenSkipsWithoutServiceCall() {
        when(cursorRepository.find()).thenReturn(Optional.empty());

        scheduler.scheduledOnce();

        verifyNoInteractions(digestService);
    }

    @Test
    @DisplayName("给定批处理抛出顶层异常，when定时入口执行，then异常不外抛（护调度线程）")
    void givenDigestAllThrows_whenRunScheduled_thenExceptionSwallowed() {
        doThrow(new IllegalStateException("库不可用")).when(digestService).digestAll(any());

        assertThatCode(() -> scheduler.runScheduled()).doesNotThrowAnyException();
    }
}
