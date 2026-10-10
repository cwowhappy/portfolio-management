package com.portfolio.invest.application.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.portfolio.invest.application.intelligence.IntelligenceChatPort;
import com.portfolio.invest.application.journal.JournalApplicationService;
import com.portfolio.invest.application.journal.TimelineEventView;
import com.portfolio.invest.application.journal.TimelineEventType;
import com.portfolio.invest.domain.memory.MemoryDigestCursor;
import com.portfolio.invest.domain.memory.MemoryDigestCursorRepository;
import com.portfolio.invest.domain.user.User;
import com.portfolio.invest.domain.user.UserRepository;
import com.portfolio.invest.domain.user.UserRole;
import com.portfolio.invest.domain.user.UserStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 摘要链用例（MS-31 B6，设计规格 §4.2）：窗口传递（Instant→上海时区 LocalDate 两端钉住）、
 * 空事件零 LLM、LLM empty/全空白不写不报错、成功节格式与决议 #9 逐字、单用户失败 WARN
 * 跳过但批完成仍推进游标（Review Focus #3 红线）、全批顶层异常游标不动、单事件超长截断。
 */
class MemoryDigestServiceTest {

    /** 窗口起 = 上海 2026-10-09 21:07:00（首跑种子后首次运行的典型游标时刻）。 */
    private static final Instant WINDOW_START = Instant.parse("2026-10-09T13:07:00Z");

    /** 批执行时刻（时钟钉死）= 上海 2026-10-10 21:07:30。 */
    private static final Instant NOW = Instant.parse("2026-10-10T13:07:30Z");

    private static final LocalDate FROM = LocalDate.of(2026, 10, 9);
    private static final LocalDate TO = LocalDate.of(2026, 10, 10);

    private final JournalApplicationService journal = mock(JournalApplicationService.class);
    private final IntelligenceChatPort chatPort = mock(IntelligenceChatPort.class);
    private final WorkspaceMemoryPort memoryPort = mock(WorkspaceMemoryPort.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final MemoryDigestCursorRepository cursorRepository = mock(MemoryDigestCursorRepository.class);

    private MemoryDigestService service;

    @BeforeEach
    void setUp() {
        service = new MemoryDigestService(journal, chatPort, memoryPort, userRepository,
                cursorRepository, Clock.fixed(NOW, ZoneId.of("Asia/Shanghai")));
        when(cursorRepository.find()).thenReturn(Optional.of(new MemoryDigestCursor(WINDOW_START)));
    }

    private static User user(long id) {
        return User.reconstitute(id, "user" + id, "hash", UserRole.USER,
                UserStatus.APPROVED, true, Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"));
    }

    private static User userWithStatus(long id, UserStatus status, boolean enabled) {
        return User.reconstitute(id, "user" + id, "hash", UserRole.USER,
                status, enabled, Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"));
    }

    private static TimelineEventView event(TimelineEventType type, String date, String title,
                                           String description) {
        return new TimelineEventView(type, LocalDate.parse(date), title, description,
                "600519", "贵州茅台", 7L, "TRADE");
    }

    private static TimelineEventView buyEvent() {
        return event(TimelineEventType.BUY, "2026-10-10", "买入贵州茅台", "理由与计划");
    }

    private void givenOneApprovedUser() {
        when(userRepository.findAll()).thenReturn(List.of(user(1L)));
    }

    private void givenLlmReturns(String text) {
        when(chatPort.complete(any(), any())).thenReturn(Optional.of(
                new IntelligenceChatPort.ChatOutcome(text, 123)));
    }

    @Test
    @DisplayName("窗口传递：timeline 收到游标时刻→批时刻的上海 LocalDate 段（10-09 → 10-10）")
    void givenWindow_whenDigestAll_thenTimelineReceivesShanghaiLocalDates() {
        givenOneApprovedUser();
        when(journal.timeline(1L, FROM, TO)).thenReturn(List.of(buyEvent()));
        givenLlmReturns("- 买入贵州茅台 600519");

        service.digestAll(WINDOW_START);

        verify(journal).timeline(1L, FROM, TO);
        verify(journal, times(1)).timeline(anyLong(), any(), any()); // 仅一次（单用户）
    }

    @Test
    @DisplayName("用户过滤：仅 APPROVED 且 enabled 用户入批（PENDING/停用零取数零 LLM）")
    void givenMixedUsers_whenDigestAll_thenOnlyCanLoginUsersProcessed() {
        when(userRepository.findAll()).thenReturn(List.of(
                userWithStatus(2L, UserStatus.PENDING, true),
                userWithStatus(3L, UserStatus.APPROVED, false),
                user(1L)));
        when(journal.timeline(1L, FROM, TO)).thenReturn(List.of(buyEvent()));
        givenLlmReturns("- 买入贵州茅台 600519");

        service.digestAll(WINDOW_START);

        verify(journal).timeline(1L, FROM, TO);
        verify(journal, times(1)).timeline(anyLong(), any(), any()); // 2L/3L 未取数
        verify(memoryPort).appendExternalEvent(anyLong(), any(), any());
    }

    @Test
    @DisplayName("timeline 空事件：零 LLM 调用、不写文件、批完成游标照常推进")
    void givenEmptyTimeline_whenDigestAll_thenNoLlmNoWriteCursorAdvances() {
        givenOneApprovedUser();
        when(journal.timeline(1L, FROM, TO)).thenReturn(List.of());

        service.digestAll(WINDOW_START);

        verifyNoInteractions(chatPort);
        verify(memoryPort, never()).appendExternalEvent(anyLong(), any(), any());
        verify(cursorRepository).save(new MemoryDigestCursor(NOW));
    }

    @Test
    @DisplayName("LLM 返回 empty：不写文件、不报错（端口契约静默跳批）、游标推进")
    void givenLlmEmpty_whenDigestAll_thenNoWriteNoThrowCursorAdvances() {
        givenOneApprovedUser();
        when(journal.timeline(1L, FROM, TO)).thenReturn(List.of(buyEvent()));
        when(chatPort.complete(any(), any())).thenReturn(Optional.empty());

        assertThatCode(() -> service.digestAll(WINDOW_START)).doesNotThrowAnyException();

        verify(memoryPort, never()).appendExternalEvent(anyLong(), any(), any());
        verify(cursorRepository).save(new MemoryDigestCursor(NOW));
    }

    @Test
    @DisplayName("LLM 返回全空白文本（present(\"\") 已知边界）：不写空节、游标推进")
    void givenLlmBlankText_whenDigestAll_thenNoWriteCursorAdvances() {
        givenOneApprovedUser();
        when(journal.timeline(1L, FROM, TO)).thenReturn(List.of(buyEvent()));
        givenLlmReturns("  \n\n");

        service.digestAll(WINDOW_START);

        verify(memoryPort, never()).appendExternalEvent(anyLong(), any(), any());
        verify(cursorRepository).save(new MemoryDigestCursor(NOW));
    }

    @Test
    @DisplayName("成功：节内容与决议 #9 写入格式逐字（节头分钟精度窗口 + bullet 行）")
    void givenLlmBullets_whenDigestAll_thenSectionFormatVerbatim() {
        givenOneApprovedUser();
        when(journal.timeline(1L, FROM, TO)).thenReturn(List.of(buyEvent()));
        givenLlmReturns("- 买入贵州茅台(600519) 100股，金额15万\n- 卖出五粮液(000858) 200股");

        service.digestAll(WINDOW_START);

        String expected = "## 外部事件摘要 — 2026-10-10（2026-10-09T21:07 ~ 2026-10-10T21:07）\n"
                + "- 买入贵州茅台(600519) 100股，金额15万\n"
                + "- 卖出五粮液(000858) 200股";
        verify(memoryPort).appendExternalEvent(1L, LocalDate.of(2026, 10, 10), expected);
        verify(cursorRepository).save(new MemoryDigestCursor(NOW));
    }

    @Test
    @DisplayName("成功：system 提示词逐字为 DigestPrompts.SYSTEM（版本资产 digest.memory 同源）")
    void givenLlmBullets_whenDigestAll_thenSystemPromptIsDigestPromptsSystem() {
        givenOneApprovedUser();
        when(journal.timeline(1L, FROM, TO)).thenReturn(List.of(buyEvent()));
        givenLlmReturns("- 买入贵州茅台 600519");

        service.digestAll(WINDOW_START);

        verify(chatPort).complete(eq(DigestPrompts.SYSTEM), any());
    }

    @Test
    @DisplayName("LLM 行变体归一为「- 」bullet：裸行/无空格短横/数字序号/空白行")
    void givenLlmLineVariants_whenDigestAll_thenBulletsNormalized() {
        givenOneApprovedUser();
        when(journal.timeline(1L, FROM, TO)).thenReturn(List.of(buyEvent()));
        givenLlmReturns("-买入贵州茅台 600519\n2. 加仓五粮液 000858\n\n裸行：分红到账\n");

        service.digestAll(WINDOW_START);

        String expected = "## 外部事件摘要 — 2026-10-10（2026-10-09T21:07 ~ 2026-10-10T21:07）\n"
                + "- 买入贵州茅台 600519\n"
                + "- 加仓五粮液 000858\n"
                + "- 裸行：分红到账";
        verify(memoryPort).appendExternalEvent(1L, LocalDate.of(2026, 10, 10), expected);
    }

    @Test
    @DisplayName("负数开头正文不被误剥（如 -5% 回撤行保留原样只加 bullet 前缀）")
    void givenNegativeNumberLeadingLine_whenDigestAll_thenContentPreserved() {
        givenOneApprovedUser();
        when(journal.timeline(1L, FROM, TO)).thenReturn(List.of(buyEvent()));
        givenLlmReturns("- -5% 回撤触发止损提醒");

        service.digestAll(WINDOW_START);

        String expected = "## 外部事件摘要 — 2026-10-10（2026-10-09T21:07 ~ 2026-10-10T21:07）\n"
                + "- -5% 回撤触发止损提醒";
        verify(memoryPort).appendExternalEvent(1L, LocalDate.of(2026, 10, 10), expected);
    }

    @Test
    @DisplayName("单用户失败 WARN 跳过：其余用户照常写、批完成仍推进游标（Review Focus #3 红线）")
    void givenOneUserFails_whenDigestAll_thenOthersWrittenCursorStillAdvances() {
        when(userRepository.findAll()).thenReturn(List.of(user(1L), user(2L)));
        when(journal.timeline(1L, FROM, TO)).thenThrow(new IllegalStateException("journal 查询失败"));
        when(journal.timeline(2L, FROM, TO)).thenReturn(List.of(buyEvent()));
        givenLlmReturns("- 买入贵州茅台 600519");

        assertThatCode(() -> service.digestAll(WINDOW_START)).doesNotThrowAnyException();

        // 失败用户零写入，成功用户照常写
        verify(memoryPort, never()).appendExternalEvent(eq(1L), any(), any());
        verify(memoryPort).appendExternalEvent(eq(2L), any(), any());
        // 批完成即推进（非全成功）——失败用户该窗口不重试：游标已推进到批时刻，
        // 次日窗口自批时刻起，其当日事件不再覆盖（损失该用户当日摘要，终审 F-3 口径）
        verify(cursorRepository).save(new MemoryDigestCursor(NOW));
    }

    @Test
    @DisplayName("全批顶层异常（findAll 抛出）：游标不动、零 LLM")
    void givenFindAllThrows_whenDigestAll_thenCursorNotMovedNoLlm() {
        when(userRepository.findAll()).thenThrow(new IllegalStateException("库不可用"));

        assertThatThrownBy(() -> service.digestAll(WINDOW_START))
                .isInstanceOf(IllegalStateException.class);

        verify(cursorRepository, never()).save(any());
        verifyNoInteractions(chatPort);
    }

    @Test
    @DisplayName("游标单行缺失（V7 seed 不变量破坏）：抛出且游标仓库零写入")
    void givenCursorMissing_whenDigestAll_thenThrowsWithoutSave() {
        givenOneApprovedUser();
        when(journal.timeline(1L, FROM, TO)).thenReturn(List.of(buyEvent()));
        givenLlmReturns("- 买入贵州茅台 600519");
        when(cursorRepository.find()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.digestAll(WINDOW_START))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("memory_digest_cursor");

        verify(cursorRepository, never()).save(any());
    }

    @Test
    @DisplayName("窗口起点时区边界：上海 23:59:59 属当日、跨入 00:00 属次日（from 钉住）")
    void givenWindowStartAroundShanghaiMidnight_whenDigestAll_thenFromDateFollowsShanghai() {
        givenOneApprovedUser();
        when(journal.timeline(1L, LocalDate.of(2026, 10, 9), TO)).thenReturn(List.of(buyEvent()));
        when(journal.timeline(1L, LocalDate.of(2026, 10, 10), TO)).thenReturn(List.of(buyEvent()));
        givenLlmReturns("- 买入贵州茅台 600519");

        // 15:59:59Z = 上海 10-09 23:59:59 → from=10-09
        service.digestAll(Instant.parse("2026-10-09T15:59:59Z"));
        // 16:00:00Z = 上海 10-10 00:00:00 → from=10-10
        service.digestAll(Instant.parse("2026-10-09T16:00:00Z"));

        verify(journal).timeline(1L, LocalDate.of(2026, 10, 9), TO);
        verify(journal).timeline(1L, LocalDate.of(2026, 10, 10), TO);
    }

    @Test
    @DisplayName("userPrompt：窗口行 + 事件 JSON 形态（type/date 逐字）+ 单事件超长截断保护")
    void givenOversizedEvent_whenDigestAll_thenUserPromptTruncatesSingleEvent() {
        givenOneApprovedUser();
        String huge = "超长复盘".repeat(1500); // 6000 字符 description
        when(journal.timeline(1L, FROM, TO)).thenReturn(List.of(
                buyEvent(),
                event(TimelineEventType.RESEARCH_NOTE, "2026-10-09", "巨长研究笔记", huge)));
        givenLlmReturns("- 买入贵州茅台 600519");

        service.digestAll(WINDOW_START);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatPort).complete(any(), promptCaptor.capture());
        String userPrompt = promptCaptor.getValue();
        // 窗口行（与节头同源的分钟精度上海时间）
        assertThat(userPrompt).contains("时间窗口：2026-10-09T21:07 ~ 2026-10-10T21:07");
        // 正常事件 JSON 逐字形态（Jackson 序列化：枚举名/ISO 日期）
        assertThat(userPrompt).contains("\"type\":\"BUY\"")
                .contains("\"date\":\"2026-10-10\"")
                .contains("\"title\":\"买入贵州茅台\"")
                .contains("\"stockCode\":\"600519\"");
        // 超长事件被截断：完整原文不出现，截断标记出现
        assertThat(userPrompt).doesNotContain(huge)
                .contains(MemoryDigestService.TRUNCATION_MARKER);
        // 截断后单事件 JSON 有界（≤ 阈值 + 标记 + 少量余量）
        int markerIndex = userPrompt.indexOf(MemoryDigestService.TRUNCATION_MARKER);
        assertThat(userPrompt.lastIndexOf("\"type\":\"RESEARCH_NOTE\""))
                .isLessThan(markerIndex);
        assertThat(markerIndex).isLessThanOrEqualTo(
                MemoryDigestService.MAX_EVENT_JSON_CHARS + userPrompt.indexOf("\"type\":\"RESEARCH_NOTE\""));
    }

    @Test
    @DisplayName("单事件未超阈值：JSON 原样入 prompt（不误截）")
    void givenNormalSizedEvents_whenDigestAll_thenJsonNotTruncated() {
        givenOneApprovedUser();
        when(journal.timeline(1L, FROM, TO)).thenReturn(List.of(buyEvent()));
        givenLlmReturns("- 买入贵州茅台 600519");

        service.digestAll(WINDOW_START);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatPort).complete(any(), promptCaptor.capture());
        assertThat(promptCaptor.getValue())
                .doesNotContain(MemoryDigestService.TRUNCATION_MARKER)
                .contains("\"title\":\"买入贵州茅台\"");
    }
}
