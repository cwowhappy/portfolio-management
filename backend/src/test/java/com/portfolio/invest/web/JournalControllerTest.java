package com.portfolio.invest.web;

import com.portfolio.invest.application.journal.CreateJournalEntryCommand;
import com.portfolio.invest.application.journal.JournalApplicationService;
import com.portfolio.invest.application.journal.JournalEntryView;
import com.portfolio.invest.application.journal.TimelineEventType;
import com.portfolio.invest.application.journal.TimelineEventView;
import com.portfolio.invest.domain.journal.JournalEntryType;
import com.portfolio.invest.domain.journal.JournalErrorCode;
import com.portfolio.invest.domain.journal.JournalException;
import com.portfolio.invest.domain.user.User;
import com.portfolio.invest.domain.user.UserRole;
import com.portfolio.invest.domain.user.UserStatus;
import com.portfolio.invest.infrastructure.security.AuthenticatedUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class JournalControllerTest {

    private final JournalApplicationService service = mock(JournalApplicationService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new JournalController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private org.springframework.security.core.Authentication auth() {
        var user = User.reconstitute(1L, "u", "p", UserRole.USER, UserStatus.APPROVED, true,
                Instant.now(), Instant.now());
        var principal = new AuthenticatedUser(user);
        return new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities());
    }

    @DisplayName("创建备忘返回201")
    @Test
    void createMemoReturns201() throws Exception {
        when(service.createEntry(eq(1L), any(CreateJournalEntryCommand.class)))
                .thenReturn(new JournalEntryView(5L, JournalEntryType.BUY_MEMO, "600519", "贵州茅台", null,
                        "买入茅台", "理由", null, null, null, null, null,
                        LocalDate.of(2026, 9, 2), Instant.now(), Instant.now()));
        mvc.perform(post("/api/journal/entries").principal(auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"BUY_MEMO\",\"stockCode\":\"600519\",\"stockName\":\"贵州茅台\",\"title\":\"买入茅台\",\"content\":\"理由\",\"eventDate\":\"2026-09-02\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("买入茅台"))
                .andExpect(jsonPath("$.stockCode").value("600519"));
    }

    @DisplayName("列表按类型过滤返回200")
    @Test
    void listFilteredByTypeReturns200() throws Exception {
        when(service.entries(1L, JournalEntryType.REVIEW)).thenReturn(List.of(
                new JournalEntryView(5L, JournalEntryType.REVIEW, null, null, null, "复盘", "内容",
                        null, null, null, null, null, LocalDate.now(), Instant.now(), Instant.now())));
        mvc.perform(get("/api/journal/entries").principal(auth()).param("type", "REVIEW"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("REVIEW"));
    }

    @DisplayName("时间线返回200")
    @Test
    void timelineReturns200() throws Exception {
        when(service.timeline(1L, null, null)).thenReturn(List.of(
                new TimelineEventView(TimelineEventType.BUY, LocalDate.of(2026, 8, 1), "贵州茅台",
                        "买入 100 股", "600519", "贵州茅台", 10L, "TRADE")));
        mvc.perform(get("/api/journal/timeline").principal(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("BUY"))
                .andExpect(jsonPath("$[0].stockCode").value("600519"));
    }

    @DisplayName("非本人记录映射404")
    @Test
    void othersEntryMapsTo404() throws Exception {
        when(service.getEntry(1L, 99L)).thenThrow(new JournalException(JournalErrorCode.NOT_FOUND, "记录不存在"));
        mvc.perform(get("/api/journal/entries/99").principal(auth()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @DisplayName("关联交易不存在映射404")
    @Test
    void linkedTradeNotFoundMapsTo404() throws Exception {
        when(service.createEntry(eq(1L), any(CreateJournalEntryCommand.class)))
                .thenThrow(new JournalException(JournalErrorCode.TRADE_NOT_FOUND, "关联交易不存在"));
        mvc.perform(post("/api/journal/entries").principal(auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"BUY_MEMO\",\"tradeId\":999,\"title\":\"x\",\"content\":\"y\",\"eventDate\":\"2026-09-02\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRADE_NOT_FOUND"));
    }
}
