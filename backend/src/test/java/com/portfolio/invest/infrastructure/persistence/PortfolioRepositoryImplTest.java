package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.portfolio.CashTransaction;
import com.portfolio.invest.domain.portfolio.CashTransactionType;
import com.portfolio.invest.domain.portfolio.Dividend;
import com.portfolio.invest.domain.portfolio.DividendType;
import com.portfolio.invest.domain.portfolio.GroupType;
import com.portfolio.invest.domain.portfolio.HoldingGroup;
import com.portfolio.invest.domain.portfolio.Portfolio;
import com.portfolio.invest.domain.portfolio.PortfolioRepository;
import com.portfolio.invest.domain.portfolio.Position;
import com.portfolio.invest.domain.portfolio.Trade;
import com.portfolio.invest.domain.portfolio.TradeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 每个用例事务回滚：@BeforeEach 植入的固定 app_user 行（42/43/44）随事务回滚，不污染其他用例。
@Transactional
class PortfolioRepositoryImplTest extends IntegrationTestBase {

    @Autowired
    private PortfolioRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** portfolio.user_id 外键引用 app_user(id)，需先植入带指定 id 的用户行。 */
    @BeforeEach
    void seedUsers() {
        jdbcTemplate.update(
                "INSERT INTO app_user(id, username, password_hash, role, status) VALUES (?, ?, ?, ?, ?)",
                42L, "portfolio-t4-42", "h", "USER", "PENDING");
        jdbcTemplate.update(
                "INSERT INTO app_user(id, username, password_hash, role, status) VALUES (?, ?, ?, ?, ?)",
                43L, "portfolio-t4-43", "h", "USER", "PENDING");
        jdbcTemplate.update(
                "INSERT INTO app_user(id, username, password_hash, role, status) VALUES (?, ?, ?, ?, ?)",
                44L, "portfolio-t4-44", "h", "USER", "PENDING");
    }

    private Portfolio savePortfolio(Long userId) {
        repository.insertPortfolioIfAbsent(userId);
        return repository.findPortfolioByUserId(userId).orElseThrow();
    }

    @Test
    void 组合按用户保存与查询() {
        Portfolio saved = savePortfolio(42L);
        assertThat(saved.id()).isNotNull();
        assertThat(repository.findPortfolioByUserId(42L)).isPresent();
    }

    @Test
    void 分组与持仓按组合查询() {
        Portfolio p = savePortfolio(43L);
        HoldingGroup g = repository.saveGroup(HoldingGroup.create(p.id(), "华泰", GroupType.ACCOUNT, Instant.now()));

        Position pos = Position.create(p.id(), g.id(), "600519", "贵州茅台", Instant.now())
                .applyBuy(new BigDecimal("1500"), new BigDecimal("100"), new BigDecimal("5"));
        Position savedPos = repository.savePosition(pos);

        assertThat(repository.findGroupsByPortfolioId(p.id())).hasSize(1);
        assertThat(repository.findPositionsByPortfolioId(p.id())).hasSize(1);
        assertThat(savedPos.avgCost()).isEqualByComparingTo("1500.05");
    }

    @Test
    void 删除分组前先清空持仓() {
        Portfolio p = savePortfolio(44L);
        HoldingGroup g = repository.saveGroup(HoldingGroup.create(p.id(), "东财", GroupType.ACCOUNT, Instant.now()));
        Position savedPos = repository.savePosition(Position.create(p.id(), g.id(), "000858", "五粮液", Instant.now()));

        // position.group_id 外键无 ON DELETE CASCADE（DB 层兜底）：须先清空持仓再删分组（P2 语义）。
        repository.deletePosition(savedPos.id());
        repository.deleteGroup(g.id());

        assertThat(repository.findPositionsByGroupId(g.id())).isEmpty();
        assertThat(repository.findGroupsByPortfolioId(p.id())).isEmpty();
    }

    @Test
    void 交易分红现金流水往返() {
        Portfolio p = savePortfolio(43L);
        HoldingGroup g = repository.saveGroup(HoldingGroup.create(p.id(), "华泰", GroupType.ACCOUNT, Instant.now()));
        Position savedPos = repository.savePosition(Position.create(p.id(), g.id(), "600519", "贵州茅台", Instant.now())
                .applyBuy(new BigDecimal("1500"), new BigDecimal("100"), new BigDecimal("5")));

        Trade savedTrade = repository.saveTrade(new Trade(null, savedPos.id(), TradeType.BUY,
                LocalDate.of(2026, 8, 27), new BigDecimal("1500"), new BigDecimal("100"),
                new BigDecimal("5"), Instant.now()));
        assertThat(savedTrade.id()).isNotNull();
        List<Trade> trades = repository.findTradesByPositionId(savedPos.id());
        assertThat(trades).hasSize(1);
        assertThat(trades.get(0).type()).isEqualTo(TradeType.BUY);

        Dividend savedDividend = repository.saveDividend(new Dividend(null, savedPos.id(), DividendType.CASH,
                LocalDate.of(2026, 8, 28), new BigDecimal("1.5"), null, Instant.now()));
        assertThat(savedDividend.id()).isNotNull();
        assertThat(repository.findDividendsByPositionId(savedPos.id())).hasSize(1);

        CashTransaction savedTx = repository.saveCashTransaction(new CashTransaction(null, g.id(),
                CashTransactionType.DEPOSIT, new BigDecimal("10000"), LocalDate.of(2026, 8, 27),
                "初始转入", Instant.now()));
        assertThat(savedTx.id()).isNotNull();
        List<CashTransaction> txs = repository.findCashTransactionsByGroupId(g.id());
        assertThat(txs).hasSize(1);
        assertThat(txs.get(0).type()).isEqualTo(CashTransactionType.DEPOSIT);
    }
}
