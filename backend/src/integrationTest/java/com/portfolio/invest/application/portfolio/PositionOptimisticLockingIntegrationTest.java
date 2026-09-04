package com.portfolio.invest.application.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.portfolio.invest.domain.portfolio.PortfolioRepository;
import com.portfolio.invest.domain.portfolio.Position;
import com.portfolio.invest.domain.portfolio.Trade;
import com.portfolio.invest.domain.portfolio.TradeType;
import com.portfolio.invest.support.ConcurrencyTestSupport;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 乐观锁回归：两个并发「读-改-整行 merge 写」buy 同一持仓，须一个成功、一个乐观锁冲突，
 * 且最终 position 与 trade 历史一致（无丢更新、无幽灵成交）。
 *
 * <p>确定性编排：先让「败者」事务读到 position（version=1）并挂起，再让「胜者」buy 提交
 * （version 1→2），最后放行败者以陈旧的 version=1 写回——无 @Version 时会静默覆盖胜者，
 * 有 @Version 时 UPDATE ... WHERE version=1 命中 0 行 → ObjectOptimisticLockingFailureException。
 */
@SpringBootTest
class PositionOptimisticLockingIntegrationTest extends ConcurrencyTestSupport {

    @Autowired
    private PortfolioApplicationService service;

    @Autowired
    private PortfolioRepository portfolioRepository;

    @Autowired
    private PlatformTransactionManager txManager;

    private static final long USER_ID = SENTINEL_ID_9003;
    private static final long PORTFOLIO_ID = SENTINEL_ID_9003;
    private static final long GROUP_ID = SENTINEL_ID_9003;
    private static final long POSITION_ID = SENTINEL_ID_9003;
    private static final String STOCK = "600519";

    @BeforeEach
    void seed() {
        insertUser(USER_ID, "opt-lock");
        jdbcTemplate.update(
                "INSERT INTO portfolio(id, user_id, cost_method, created_at, updated_at, version) "
                        + "VALUES (?, ?, 'WEIGHTED_AVG', now(), now(), 1)",
                PORTFOLIO_ID, USER_ID);
        jdbcTemplate.update(
                "INSERT INTO holding_group(id, portfolio_id, name, type, created_at, version) "
                        + "VALUES (?, ?, '华泰', 'ACCOUNT', now(), 1)",
                GROUP_ID, PORTFOLIO_ID);
        jdbcTemplate.update(
                "INSERT INTO position(id, portfolio_id, group_id, stock_code, stock_name, quantity, "
                        + "cost_basis, total_buy_cost, cumulative_cash_dividend, realized_pnl, net_cash_flow, "
                        + "created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, ?, 100, 10000, 10000, 0, 0, -10000, now(), now(), 1)",
                POSITION_ID, PORTFOLIO_ID, GROUP_ID, STOCK, "贵州茅台");
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM trade WHERE position_id = ?", POSITION_ID);
        jdbcTemplate.update("DELETE FROM dividend WHERE position_id = ?", POSITION_ID);
        jdbcTemplate.update("DELETE FROM position WHERE id = ?", POSITION_ID);
        jdbcTemplate.update("DELETE FROM holding_group WHERE id = ?", GROUP_ID);
        jdbcTemplate.update("DELETE FROM portfolio WHERE id = ?", PORTFOLIO_ID);
        jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", USER_ID);
    }

    @DisplayName("并发买入同一持仓一个成功一个乐观锁冲突")
    @Test
    void givenTwoConcurrentBuys_whenExecute_thenOneSucceedsOneConflict() throws Exception {
        CountDownLatch loserRead = new CountDownLatch(1);
        CountDownLatch winnerDone = new CountDownLatch(1);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Void> loser = pool.submit((Callable<Void>) () -> {
                TransactionTemplate tx = new TransactionTemplate(txManager);
                tx.executeWithoutResult(status -> {
                    Position p = portfolioRepository
                            .findPositionByIdAndPortfolioId(POSITION_ID, PORTFOLIO_ID).orElseThrow();
                    Position updated = p.applyBuy(new BigDecimal("10"), new BigDecimal("30"), new BigDecimal("0"));
                    loserRead.countDown();
                    try {
                        if (!winnerDone.await(30, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("等待胜者提交超时");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    portfolioRepository.savePosition(updated);
                    portfolioRepository.saveTrade(new Trade(
                            null, POSITION_ID, TradeType.BUY,
                            LocalDate.of(2026, 8, 28), new BigDecimal("10"),
                            new BigDecimal("30"), new BigDecimal("0"), Instant.now()));
                });
                return null;
            });

            assertThat(loserRead.await(30, TimeUnit.SECONDS)).isTrue();
            // 胜者：先提交，version 1→2，并写入一笔 trade
            service.buy(USER_ID, new BuyCommand(GROUP_ID, STOCK, "贵州茅台",
                    LocalDate.of(2026, 8, 28), new BigDecimal("10"),
                    new BigDecimal("20"), new BigDecimal("0")));
            winnerDone.countDown();

            try {
                loser.get(30, TimeUnit.SECONDS);
                fail("败者应因乐观锁冲突失败，却成功提交（丢更新未拦截）");
            } catch (java.util.concurrent.ExecutionException e) {
                assertThat(e.getCause()).isInstanceOf(OptimisticLockingFailureException.class);
            }
        } finally {
            winnerDone.countDown();
            pool.shutdownNow();
        }

        // 账本与交易历史一致：只有胜者那笔成交
        BigDecimal quantity = jdbcTemplate.queryForObject(
                "SELECT quantity FROM position WHERE id = ?", BigDecimal.class, POSITION_ID);
        assertThat(quantity).isEqualByComparingTo("120"); // 100 + 胜者 20
        Integer version = jdbcTemplate.queryForObject(
                "SELECT version FROM position WHERE id = ?", Integer.class, POSITION_ID);
        assertThat(version).isEqualTo(2);
        Integer tradeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM trade WHERE position_id = ?", Integer.class, POSITION_ID);
        assertThat(tradeCount).isEqualTo(1);
    }
}
