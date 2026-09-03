package com.portfolio.invest.application.journal;

import com.portfolio.invest.domain.journal.JournalEntry;
import com.portfolio.invest.domain.journal.JournalEntryRepository;
import com.portfolio.invest.domain.journal.JournalEntryType;
import com.portfolio.invest.domain.journal.JournalErrorCode;
import com.portfolio.invest.domain.journal.JournalException;
import com.portfolio.invest.domain.journal.PeriodType;
import com.portfolio.invest.domain.portfolio.CostMethod;
import com.portfolio.invest.domain.portfolio.Dividend;
import com.portfolio.invest.domain.portfolio.DividendType;
import com.portfolio.invest.domain.portfolio.Portfolio;
import com.portfolio.invest.domain.portfolio.PortfolioRepository;
import com.portfolio.invest.domain.portfolio.Position;
import com.portfolio.invest.domain.portfolio.Trade;
import com.portfolio.invest.domain.portfolio.TradeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JournalApplicationServiceTest {

    private final JournalEntryRepository repo = mock(JournalEntryRepository.class);
    private final PortfolioRepository portfolioRepo = mock(PortfolioRepository.class);
    private JournalApplicationService service;

    @BeforeEach
    void setUp() {
        service = new JournalApplicationService(repo, portfolioRepo);
    }

    private static Position pos() {
        return Position.reconstitute(100L, 1L, 1L, "600519", "贵州茅台",
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, Instant.now(), Instant.now());
    }

    private static Portfolio portfolio() {
        return Portfolio.reconstitute(1L, 1L, CostMethod.WEIGHTED_AVG, Instant.now(), Instant.now());
    }

    private static Trade buyTrade() {
        return new Trade(10L, 100L, TradeType.BUY, LocalDate.of(2026, 8, 1),
                new BigDecimal("1500"), new BigDecimal("100"), new BigDecimal("5"), Instant.now());
    }

    private static JournalEntry entry(long id) {
        return JournalEntry.reconstitute(id, 1L, JournalEntryType.BUY_MEMO, "600519", "贵州茅台", 10L,
                "买入茅台", "理由", new BigDecimal("1800"), new BigDecimal("1400"),
                null, null, null, LocalDate.of(2026, 8, 2), Instant.now(), Instant.now());
    }

    @Test
    void 创建备忘关联交易时回查股票() {
        when(portfolioRepo.findPortfolioByUserId(1L)).thenReturn(Optional.of(portfolio()));
        when(portfolioRepo.findTradeById(10L)).thenReturn(Optional.of(buyTrade()));
        when(portfolioRepo.findPositionByIdAndPortfolioId(100L, 1L)).thenReturn(Optional.of(pos()));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var view = service.createEntry(1L, new CreateJournalEntryCommand(JournalEntryType.BUY_MEMO,
                null, null, 10L, "买入茅台", "理由", null, null, null, null, null,
                LocalDate.of(2026, 8, 2)));

        assertThat(view.stockCode()).isEqualTo("600519");
        assertThat(view.stockName()).isEqualTo("贵州茅台");
        assertThat(view.tradeId()).isEqualTo(10L);
    }

    @Test
    void 关联非本人交易抛TRADE_NOT_FOUND() {
        when(portfolioRepo.findPortfolioByUserId(1L)).thenReturn(Optional.of(portfolio()));
        when(portfolioRepo.findTradeById(999L)).thenReturn(Optional.of(buyTrade()));
        when(portfolioRepo.findPositionByIdAndPortfolioId(100L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createEntry(1L, new CreateJournalEntryCommand(JournalEntryType.BUY_MEMO,
                null, null, 999L, "标题", "内容", null, null, null, null, null, LocalDate.now())))
                .isInstanceOfSatisfying(JournalException.class,
                        e -> assertThat(e.code()).isEqualTo(JournalErrorCode.TRADE_NOT_FOUND));
    }

    @Test
    void 客户端股票代码与交易不一致抛INVALID_INPUT() {
        when(portfolioRepo.findPortfolioByUserId(1L)).thenReturn(Optional.of(portfolio()));
        when(portfolioRepo.findTradeById(10L)).thenReturn(Optional.of(buyTrade()));
        when(portfolioRepo.findPositionByIdAndPortfolioId(100L, 1L)).thenReturn(Optional.of(pos()));

        assertThatThrownBy(() -> service.createEntry(1L, new CreateJournalEntryCommand(JournalEntryType.BUY_MEMO,
                "000001", null, 10L, "标题", "内容", null, null, null, null, null, LocalDate.now())))
                .isInstanceOfSatisfying(JournalException.class,
                        e -> assertThat(e.code()).isEqualTo(JournalErrorCode.INVALID_INPUT));
    }

    @Test
    void 非本人记录抛NOT_FOUND() {
        when(repo.findByIdAndUserId(5L, 1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.deleteEntry(1L, 5L))
                .isInstanceOfSatisfying(JournalException.class,
                        e -> assertThat(e.code()).isEqualTo(JournalErrorCode.NOT_FOUND));
    }

    @Test
    void 更新记录() {
        when(repo.findByIdAndUserId(7L, 1L)).thenReturn(Optional.of(entry(7L)));
        when(portfolioRepo.findPortfolioByUserId(1L)).thenReturn(Optional.of(portfolio()));
        when(portfolioRepo.findTradeById(10L)).thenReturn(Optional.of(buyTrade()));
        when(portfolioRepo.findPositionByIdAndPortfolioId(100L, 1L)).thenReturn(Optional.of(pos()));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var view = service.updateEntry(1L, 7L, new UpdateJournalEntryCommand(
                "600519", "贵州茅台", 10L, "新标题", "新内容", null, null, null, null, null,
                LocalDate.of(2026, 8, 3)));

        assertThat(view.title()).isEqualTo("新标题");
        assertThat(view.eventDate()).isEqualTo(LocalDate.of(2026, 8, 3));
    }

    @Test
    void 时间线合并journal与M08事件并按事件日倒序() {
        when(repo.findByUserIdInDateRange(1L, null, null)).thenReturn(List.of(
                entry(1L), // eventDate 2026-08-02
                JournalEntry.reconstitute(2L, 1L, JournalEntryType.REVIEW, null, null, null,
                        "复盘", "内容", null, null, PeriodType.QUARTERLY,
                        LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30),
                        LocalDate.of(2026, 9, 30), Instant.now(), Instant.now())));
        when(portfolioRepo.findPortfolioByUserId(1L)).thenReturn(Optional.of(portfolio()));
        when(portfolioRepo.findPositionsByPortfolioId(1L)).thenReturn(List.of(pos()));
        when(portfolioRepo.findTradesByPositionId(100L)).thenReturn(List.of(
                new Trade(10L, 100L, TradeType.BUY, LocalDate.of(2026, 8, 1),
                        new BigDecimal("1500"), new BigDecimal("100"), new BigDecimal("5"), Instant.now()),
                new Trade(11L, 100L, TradeType.SELL, LocalDate.of(2026, 8, 20),
                        new BigDecimal("1600"), new BigDecimal("50"), new BigDecimal("5"), Instant.now())));
        when(portfolioRepo.findDividendsByPositionId(100L)).thenReturn(List.of(
                new Dividend(20L, 100L, DividendType.CASH, LocalDate.of(2026, 8, 15),
                        new BigDecimal("1.5"), null, Instant.now())));

        var events = service.timeline(1L, null, null);

        assertThat(events).hasSize(5);
        assertThat(events).extracting(TimelineEventView::type).containsExactly(
                TimelineEventType.REVIEW, TimelineEventType.SELL,
                TimelineEventType.DIVIDEND, TimelineEventType.BUY_MEMO, TimelineEventType.BUY);
        assertThat(events.get(0).date()).isEqualTo(LocalDate.of(2026, 9, 30));
    }

    @Test
    void 时间线日期范围过滤() {
        when(repo.findByUserIdInDateRange(1L, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 1))).thenReturn(List.of());
        when(repo.findByUserIdInDateRange(1L, LocalDate.of(2026, 8, 2), null)).thenReturn(List.of(entry(1L)));
        when(portfolioRepo.findPortfolioByUserId(1L)).thenReturn(Optional.empty());

        assertThat(service.timeline(1L, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 1))).isEmpty();
        assertThat(service.timeline(1L, LocalDate.of(2026, 8, 2), null)).hasSize(1);
    }

    @Test
    void 时间线把日期范围下推给仓库查询() {
        LocalDate from = LocalDate.of(2026, 8, 1);
        LocalDate to = LocalDate.of(2026, 8, 31);
        when(repo.findByUserIdInDateRange(1L, from, to)).thenReturn(List.of(entry(1L)));
        when(portfolioRepo.findPortfolioByUserId(1L)).thenReturn(Optional.empty());

        var events = service.timeline(1L, from, to);

        assertThat(events).hasSize(1);
        verify(repo).findByUserIdInDateRange(1L, from, to);
    }

    @Test
    void 删除记录() {
        when(repo.findByIdAndUserId(7L, 1L)).thenReturn(Optional.of(entry(7L)));
        service.deleteEntry(1L, 7L);
        verify(repo).deleteById(7L);
    }
}
