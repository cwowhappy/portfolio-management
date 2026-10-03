package com.portfolio.invest.application.intelligence;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.portfolio.invest.application.intelligence.CollectorRunInspectPort.TaskRunSummary;
import com.portfolio.invest.domain.intelligence.MacroRepository;
import com.portfolio.invest.domain.intelligence.SourceSwitch;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 社融降级巡检（D19 决策 #25，月度聚组口径 = MS-22 探测报告 §4.2）：两连续失败月
 * 留痕+告警恰一次（状态翻转语义）、单失败月不触发、空月/成功月打断连续、已降级态
 * 幂等不重复、恢复月回切留痕不告警、partial 计成功、上海时区月分组、无 run 不判定、
 * 调度顶层吞异常。
 */
class MacroHealthServiceTest {

    /** 既有降级留痕（最新行 to=m2 = 降级态）。 */
    private static final SourceSwitch DEGRADED =
            new SourceSwitch("socfin", "m2", Instant.parse("2026-09-01T00:30:00Z"));
    /** 既有回切留痕（最新行 to=socfin = 健康态）。 */
    private static final SourceSwitch RECOVERED =
            new SourceSwitch("m2", "socfin", Instant.parse("2026-09-20T00:30:00Z"));

    private final CollectorRunInspectPort runInspect = mock(CollectorRunInspectPort.class);
    private final MacroRepository macroRepository = mock(MacroRepository.class);
    private final IntelligencePushPort pushPort = mock(IntelligencePushPort.class);
    private MacroHealthService service;

    @BeforeEach
    void setUp() {
        service = new MacroHealthService(runInspect, macroRepository, pushPort);
        when(runInspect.runsOf(anyString(), anyInt())).thenReturn(List.of());
        when(macroRepository.findLatestSwitch(anyString())).thenReturn(Optional.empty());
        when(pushPort.sendToGroup(anyString(), anyString(), anyList())).thenReturn(true);
    }

    /** 终态 run 夹具：status / source_used（失败行 NULL）/ started_at（UTC 解析）。 */
    private static TaskRunSummary run(String status, String sourceUsed, String startedAtUtc) {
        return new TaskRunSummary(status, sourceUsed, Instant.parse(startedAtUtc));
    }

    /** 两个日历相邻失败月：8 月全败（source_used NULL）、9 月 m2 兜底成功（对 socfin 仍是失败月）。 */
    private static List<TaskRunSummary> twoConsecutiveFailedMonths() {
        return List.of(
                run("failed", null, "2026-08-12T03:00:00Z"),
                run("failed", null, "2026-08-13T03:00:00Z"),
                run("success", "m2", "2026-09-14T03:00:00Z"),
                run("failed", null, "2026-09-15T03:00:00Z"));
    }

    @Test
    @DisplayName("给定两日历相邻失败月且无留痕，when巡检，then留痕 socfin→m2（原因含两月）+ 群告警恰一次")
    void givenTwoConsecutiveFailedMonths_whenCheck_thenSwitchLoggedAndAlertedOnce() {
        when(runInspect.runsOf(anyString(), anyInt())).thenReturn(twoConsecutiveFailedMonths());

        service.check();

        // 65 天回看窗（探测报告 §4.2：覆盖 ≥2 个完整发布期）
        verify(runInspect).runsOf("macro_afmi", 65);
        verify(macroRepository).insertSourceSwitch(eq("AFMI"), eq("socfin"), eq("m2"),
                contains("2026-08"));
        verify(macroRepository).insertSourceSwitch(eq("AFMI"), eq("socfin"), eq("m2"),
                contains("2026-09"));
        // 告警文案：两月标识 + M2 口径 + 人工检查指引（红色告警卡）
        verify(pushPort).sendToGroup(contains("社融数据源降级"), eq("red"), argThat(argThatLines(
                "连续 2 个发布期失败（2026-08、2026-09）", "M2 口径", "社融数据页")));
        // 告警恰一次：除查态/留痕/单次群推外无更多交互
        verify(macroRepository).findLatestSwitch("AFMI");
        org.mockito.Mockito.verifyNoMoreInteractions(macroRepository, pushPort);
    }

    @Test
    @DisplayName("给定已降级态（最新留痕 to=m2）再连续两失败月，when巡检，then幂等不重复留痕不重复告警")
    void givenAlreadyDegraded_whenCheck_thenNoDuplicateLogOrAlert() {
        when(runInspect.runsOf(anyString(), anyInt())).thenReturn(twoConsecutiveFailedMonths());
        when(macroRepository.findLatestSwitch("AFMI")).thenReturn(Optional.of(DEGRADED));

        service.check();

        verify(macroRepository, never()).insertSourceSwitch(anyString(), anyString(), anyString(), anyString());
        verify(pushPort, never()).sendToGroup(anyString(), anyString(), anyList());
    }

    @Test
    @DisplayName("给定单失败月（前一月 socfin 成功），when巡检，then不触发")
    void givenSingleFailedMonth_whenCheck_thenNoAction() {
        when(runInspect.runsOf(anyString(), anyInt())).thenReturn(List.of(
                run("success", "socfin", "2026-08-14T03:00:00Z"),
                run("failed", null, "2026-09-14T03:00:00Z")));

        service.check();

        verify(macroRepository, never()).insertSourceSwitch(anyString(), anyString(), anyString(), anyString());
        verify(pushPort, never()).sendToGroup(anyString(), anyString(), anyList());
    }

    @Test
    @DisplayName("给定空月打断（7 月与 9 月各失败、8 月无 run），when巡检，then日历月不相邻不视为连续")
    void givenGapMonthBreaksStreak_whenCheck_thenNoAction() {
        when(runInspect.runsOf(anyString(), anyInt())).thenReturn(List.of(
                run("failed", null, "2026-07-14T03:00:00Z"),
                run("failed", null, "2026-09-14T03:00:00Z")));

        service.check();

        verify(macroRepository, never()).insertSourceSwitch(anyString(), anyString(), anyString(), anyString());
        verify(pushPort, never()).sendToGroup(anyString(), anyString(), anyList());
    }

    @Test
    @DisplayName("给定已降级且最新月 socfin 成功，when巡检，then回切留痕 m2→socfin 不告警")
    void givenDegradedThenSuccessMonth_whenCheck_thenRecoveryLoggedWithoutAlert() {
        when(runInspect.runsOf(anyString(), anyInt())).thenReturn(List.of(
                run("failed", null, "2026-08-14T03:00:00Z"),
                run("success", "socfin", "2026-09-14T03:00:00Z")));
        when(macroRepository.findLatestSwitch("AFMI")).thenReturn(Optional.of(DEGRADED));

        service.check();

        verify(macroRepository).insertSourceSwitch("AFMI", "m2", "socfin", "社融源恢复");
        verify(pushPort, never()).sendToGroup(anyString(), anyString(), anyList());
    }

    @Test
    @DisplayName("给定健康态（无留痕或已回切）且最新月成功，when巡检，then无动作不重复回切")
    void givenHealthyAndSuccessMonth_whenCheck_thenNoAction() {
        when(runInspect.runsOf(anyString(), anyInt())).thenReturn(List.of(
                run("success", "socfin", "2026-09-14T03:00:00Z")));
        when(macroRepository.findLatestSwitch("AFMI")).thenReturn(Optional.of(RECOVERED));

        service.check();

        verify(macroRepository, never()).insertSourceSwitch(anyString(), anyString(), anyString(), anyString());
        verify(pushPort, never()).sendToGroup(anyString(), anyString(), anyList());
    }

    @Test
    @DisplayName("给定降级后恢复（最新留痕 to=socfin）再连续两失败月，when巡检，then状态翻转再次留痕+告警")
    void givenRecoveredThenTwoFailedMonths_whenCheck_thenAlertsAgainOnFlip() {
        when(runInspect.runsOf(anyString(), anyInt())).thenReturn(twoConsecutiveFailedMonths());
        when(macroRepository.findLatestSwitch("AFMI")).thenReturn(Optional.of(RECOVERED));

        service.check();

        verify(macroRepository).insertSourceSwitch(eq("AFMI"), eq("socfin"), eq("m2"),
                contains("2026-09"));
        verify(pushPort).sendToGroup(anyString(), eq("red"), anyList());
    }

    @Test
    @DisplayName("给定 8 月 socfin partial（计成功）+ 9 月失败，when巡检，then单失败月不触发")
    void givenPartialCountsAsSuccess_whenCheck_thenNoAction() {
        when(runInspect.runsOf(anyString(), anyInt())).thenReturn(List.of(
                run("partial", "socfin", "2026-08-14T03:00:00Z"),
                run("failed", null, "2026-09-14T03:00:00Z")));

        service.check();

        verify(macroRepository, never()).insertSourceSwitch(anyString(), anyString(), anyString(), anyString());
        verify(pushPort, never()).sendToGroup(anyString(), anyString(), anyList());
    }

    @Test
    @DisplayName("给定 UTC 月末 17:30 的失败 run（上海 9 月 1 日），when巡检，then按上海自然月聚组（8 月空月打断）")
    void givenUtcMonthBoundaryRun_whenCheck_thenGroupedByShanghaiMonth() {
        when(runInspect.runsOf(anyString(), anyInt())).thenReturn(List.of(
                // 2026-08-31T17:30Z = 上海 2026-09-01 01:30 → 归 9 月，8 月无 run
                run("failed", null, "2026-08-31T17:30:00Z"),
                run("failed", null, "2026-09-10T03:00:00Z")));

        service.check();

        // 若按 UTC 聚组则 8/9 两月相邻全败会触发；上海口径下 8 月是空月 → 不触发
        verify(macroRepository, never()).insertSourceSwitch(anyString(), anyString(), anyString(), anyString());
        verify(pushPort, never()).sendToGroup(anyString(), anyString(), anyList());
    }

    @Test
    @DisplayName("给定窗口内无任何 run，when巡检，then不判定不留痕不告警")
    void givenNoRuns_whenCheck_thenNoVerdict() {
        when(runInspect.runsOf(anyString(), anyInt())).thenReturn(List.of());

        service.check();

        verifyNoInteractions(macroRepository, pushPort);
    }

    @Test
    @DisplayName("给定取数端口抛异常，when调度入口，then顶层吞异常不外抛")
    void givenPortThrows_whenPatrol_thenExceptionSwallowed() {
        when(runInspect.runsOf(anyString(), anyInt())).thenThrow(new RuntimeException("collector 表不可读"));

        assertThatCode(service::patrol).doesNotThrowAnyException();
    }

    /** 群告警正文断言：拼接全部行后须同时含各关键短语。 */
    private static org.mockito.ArgumentMatcher<List<String>> argThatLines(String... phrases) {
        return lines -> {
            String joined = String.join("\n", lines);
            for (String phrase : phrases) {
                if (!joined.contains(phrase)) {
                    return false;
                }
            }
            return true;
        };
    }
}
