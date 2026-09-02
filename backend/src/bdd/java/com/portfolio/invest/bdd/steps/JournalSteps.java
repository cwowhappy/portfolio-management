package com.portfolio.invest.bdd.steps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.invest.application.journal.CreateJournalEntryCommand;
import com.portfolio.invest.application.journal.JournalApplicationService;
import com.portfolio.invest.domain.journal.JournalEntryType;
import com.portfolio.invest.domain.journal.JournalException;
import com.portfolio.invest.domain.portfolio.PortfolioRepository;
import io.cucumber.java.zh_cn.当;
import io.cucumber.java.zh_cn.那么;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Autowired;

public class JournalSteps {

    @Autowired
    JournalApplicationService journalService;

    @Autowired
    PortfolioRepository portfolioRepository;

    @Autowired
    ScenarioContext ctx;

    @当("记录该持仓最近一笔买入交易的 ID")
    public void 记录交易ID() {
        var trades = portfolioRepository.findTradesByPositionId(ctx.getPositionId());
        assertThat(trades).isNotEmpty();
        ctx.setTradeId(trades.get(0).id());
    }

    @当("创建买入备忘 {string} 关联该交易，目标价 {bigdecimal}，止损价 {bigdecimal}")
    public void 创建关联备忘(String title, BigDecimal targetPrice, BigDecimal stopLoss) {
        var view = journalService.createEntry(ctx.getUserId(), new CreateJournalEntryCommand(
                JournalEntryType.BUY_MEMO, null, null, ctx.getTradeId(), title, "理由",
                targetPrice, stopLoss, null, null, null, LocalDate.now()));
        ctx.setJournalEntryId(view.id());
    }

    @当("创建买入备忘 {string} 关联交易 {int}")
    public void 创建关联不存在交易(String title, int tradeId) {
        assertThatThrownBy(() -> journalService.createEntry(ctx.getUserId(), new CreateJournalEntryCommand(
                JournalEntryType.BUY_MEMO, null, null, (long) tradeId, title, "理由",
                null, null, null, null, null, LocalDate.now())))
                .isInstanceOfSatisfying(JournalException.class,
                        e -> ctx.setJournalErrorCode(e.code()));
    }

    @那么("该用户应有 {int} 条记录")
    public void 记录数(int count) {
        assertThat(journalService.entries(ctx.getUserId(), null)).hasSize(count);
    }

    @那么("该备忘的股票代码为 {string}，股票名为 {string}")
    public void 备忘股票(String stockCode, String stockName) {
        var view = journalService.getEntry(ctx.getUserId(), ctx.getJournalEntryId());
        assertThat(view.stockCode()).isEqualTo(stockCode);
        assertThat(view.stockName()).isEqualTo(stockName);
    }

    @那么("时间线应包含 {string} 与 {string} 两类事件")
    public void 时间线包含(String t1, String t2) {
        var types = journalService.timeline(ctx.getUserId(), null, null).stream()
                .map(e -> e.type().name())
                .toList();
        assertThat(types).contains(t1, t2);
    }

    @那么("应抛出 {string} 错误")
    public void 应抛错误(String code) {
        assertThat(ctx.getJournalErrorCode()).isEqualTo(code);
    }
}
