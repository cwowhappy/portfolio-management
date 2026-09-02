package com.portfolio.invest.application.journal;

import com.portfolio.invest.domain.journal.JournalEntry;
import com.portfolio.invest.domain.journal.JournalEntryRepository;
import com.portfolio.invest.domain.journal.JournalEntryType;
import com.portfolio.invest.domain.journal.JournalErrorCode;
import com.portfolio.invest.domain.journal.JournalException;
import com.portfolio.invest.domain.portfolio.Dividend;
import com.portfolio.invest.domain.portfolio.DividendType;
import com.portfolio.invest.domain.portfolio.Portfolio;
import com.portfolio.invest.domain.portfolio.PortfolioRepository;
import com.portfolio.invest.domain.portfolio.Position;
import com.portfolio.invest.domain.portfolio.Trade;
import com.portfolio.invest.domain.portfolio.TradeType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JournalApplicationService {

    private final JournalEntryRepository repository;
    private final PortfolioRepository portfolioRepository;

    public JournalApplicationService(JournalEntryRepository repository,
                                     PortfolioRepository portfolioRepository) {
        this.repository = repository;
        this.portfolioRepository = portfolioRepository;
    }

    public List<JournalEntryView> entries(Long userId, JournalEntryType type) {
        return repository.findByUserId(userId, type).stream().map(JournalEntryView::from).toList();
    }

    @Transactional
    public JournalEntryView createEntry(Long userId, CreateJournalEntryCommand cmd) {
        ResolvedStock resolved = resolveTrade(userId, cmd.tradeId(), cmd.stockCode(), cmd.stockName());
        JournalEntry entry = JournalEntry.create(userId, cmd.type(), resolved.stockCode(), resolved.stockName(),
                cmd.tradeId(), cmd.title().trim(), cmd.content(), cmd.targetPrice(), cmd.stopLoss(),
                cmd.periodType(), cmd.periodStart(), cmd.periodEnd(), cmd.eventDate(), Instant.now());
        return JournalEntryView.from(repository.save(entry));
    }

    public JournalEntryView getEntry(Long userId, Long entryId) {
        return JournalEntryView.from(requireEntry(userId, entryId));
    }

    @Transactional
    public JournalEntryView updateEntry(Long userId, Long entryId, UpdateJournalEntryCommand cmd) {
        JournalEntry existing = requireEntry(userId, entryId);
        ResolvedStock resolved = resolveTrade(userId, cmd.tradeId(), cmd.stockCode(), cmd.stockName());
        JournalEntry updated = existing.update(resolved.stockCode(), resolved.stockName(),
                cmd.tradeId(), cmd.title().trim(), cmd.content(), cmd.targetPrice(), cmd.stopLoss(),
                cmd.periodType(), cmd.periodStart(), cmd.periodEnd(), cmd.eventDate());
        return JournalEntryView.from(repository.save(updated));
    }

    @Transactional
    public void deleteEntry(Long userId, Long entryId) {
        requireEntry(userId, entryId);
        repository.deleteById(entryId);
    }

    public List<TimelineEventView> timeline(Long userId, LocalDate from, LocalDate to) {
        List<TimelineEventView> events = new ArrayList<>();

        for (JournalEntry e : repository.findByUserId(userId, null)) {
            if (inRange(e.eventDate(), from, to)) {
                events.add(journalEvent(e));
            }
        }

        var portfolio = portfolioRepository.findPortfolioByUserId(userId);
        if (portfolio.isPresent()) {
            for (Position pos : portfolioRepository.findPositionsByPortfolioId(portfolio.get().id())) {
                for (Trade t : portfolioRepository.findTradesByPositionId(pos.id())) {
                    if (inRange(t.tradeDate(), from, to)) {
                        events.add(tradeEvent(t, pos));
                    }
                }
                for (Dividend d : portfolioRepository.findDividendsByPositionId(pos.id())) {
                    if (inRange(d.exDate(), from, to)) {
                        events.add(dividendEvent(d, pos));
                    }
                }
            }
        }

        return events.stream()
                .sorted(Comparator.comparing(TimelineEventView::date).reversed())
                .toList();
    }

    private JournalEntry requireEntry(Long userId, Long entryId) {
        return repository.findByIdAndUserId(entryId, userId)
                .orElseThrow(() -> new JournalException(JournalErrorCode.NOT_FOUND, "记录不存在"));
    }

    /** 校验交易归属当前用户并回查股票；无 tradeId 时直接用客户端股票信息。 */
    private ResolvedStock resolveTrade(Long userId, Long tradeId, String clientStockCode, String clientStockName) {
        if (tradeId == null) {
            return new ResolvedStock(clientStockCode, clientStockName);
        }
        Portfolio portfolio = portfolioRepository.findPortfolioByUserId(userId)
                .orElseThrow(() -> new JournalException(JournalErrorCode.TRADE_NOT_FOUND, "关联交易不存在"));
        Trade trade = portfolioRepository.findTradeById(tradeId)
                .orElseThrow(() -> new JournalException(JournalErrorCode.TRADE_NOT_FOUND, "关联交易不存在"));
        Position position = portfolioRepository.findPositionByIdAndPortfolioId(trade.positionId(), portfolio.id())
                .orElseThrow(() -> new JournalException(JournalErrorCode.TRADE_NOT_FOUND, "关联交易不存在"));
        if (clientStockCode != null && !clientStockCode.equals(position.stockCode())) {
            throw new JournalException(JournalErrorCode.INVALID_INPUT, "股票代码与关联交易不一致");
        }
        return new ResolvedStock(position.stockCode(), position.stockName());
    }

    private static TimelineEventView journalEvent(JournalEntry e) {
        return new TimelineEventView(journalEventType(e.type()), e.eventDate(), e.title(),
                truncate(e.content(), 80), e.stockCode(), e.stockName(), e.id(), "JOURNAL");
    }

    private static TimelineEventView tradeEvent(Trade t, Position pos) {
        TimelineEventType type = t.type() == TradeType.BUY ? TimelineEventType.BUY : TimelineEventType.SELL;
        String action = t.type() == TradeType.BUY ? "买入" : "卖出";
        return new TimelineEventView(type, t.tradeDate(), pos.stockName(),
                action + " " + trimNum(t.quantity()) + " 股", pos.stockCode(), pos.stockName(), t.id(), "TRADE");
    }

    private static TimelineEventView dividendEvent(Dividend d, Position pos) {
        String desc = d.type() == DividendType.CASH
                ? "现金分红 " + trimNum(d.cashPerShare()) + " 元/股"
                : "送股 " + trimNum(d.stockRatio()) + " 股/股";
        return new TimelineEventView(TimelineEventType.DIVIDEND, d.exDate(), pos.stockName(),
                desc, pos.stockCode(), pos.stockName(), d.id(), "DIVIDEND");
    }

    private static TimelineEventType journalEventType(JournalEntryType t) {
        return switch (t) {
            case BUY_MEMO -> TimelineEventType.BUY_MEMO;
            case SELL_MEMO -> TimelineEventType.SELL_MEMO;
            case RESEARCH_NOTE -> TimelineEventType.RESEARCH_NOTE;
            case REVIEW -> TimelineEventType.REVIEW;
        };
    }

    private static boolean inRange(LocalDate date, LocalDate from, LocalDate to) {
        if (from != null && date.isBefore(from)) {
            return false;
        }
        if (to != null && date.isAfter(to)) {
            return false;
        }
        return true;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String trimNum(BigDecimal v) {
        return v == null ? "" : v.stripTrailingZeros().toPlainString();
    }

    private record ResolvedStock(String stockCode, String stockName) {}
}
