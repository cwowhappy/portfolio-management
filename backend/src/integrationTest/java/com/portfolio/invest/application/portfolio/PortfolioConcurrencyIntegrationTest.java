package com.portfolio.invest.application.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.domain.portfolio.CashTransactionType;
import com.portfolio.invest.domain.portfolio.GroupType;
import com.portfolio.invest.domain.portfolio.PortfolioErrorCode;
import com.portfolio.invest.domain.portfolio.PortfolioException;
import com.portfolio.invest.support.ConcurrencyTestSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 并发首次访问的幂等回归：前端 /portfolio 首次加载会并发触发多个读接口，
 * 各接口都走 getOrCreatePortfolio，修复前 find→save 非原子导致唯一约束冲突（400）。
 */
@SpringBootTest
class PortfolioConcurrencyIntegrationTest extends ConcurrencyTestSupport {

    @Autowired
    private PortfolioApplicationService service;

    private static final long USER_ID = SENTINEL_ID_9001;

    /** portfolio.user_id 外键引用 app_user(id)，需先植入带指定 id 的用户行（提交态，供并发线程可见）。 */
    @BeforeEach
    void seedUser() {
        insertUser(USER_ID, "portfolio-concurrency");
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM portfolio WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", USER_ID);
    }

    @DisplayName("并发首次访问组合只创建一行")
    @Test
    void whenConcurrentFirstAccess_thenSinglePortfolioRowCreated() throws Exception {
        // 任一并发请求抛异常（修复前为 DataIntegrityViolationException）都会在 race 的 future.get 暴露
        race(8, () -> {
            service.groups(USER_ID);
            return null;
        });

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM portfolio WHERE user_id = ?", Integer.class, USER_ID);
        assertThat(count).isEqualTo(1);
    }

    /**
     * MS-28 P2-B3 TOCTOU 回归：分组现金仅够一笔买入时，同组并发买两只不同股票。
     * 修复前（无组行锁）两个事务同时读到同一份现金快照、双双通过校验 → 双成交 → 负现金；
     * 修复后组行悲观锁串行化「锁→读算现金→写」，后到者重读后现金不足被拒：恰一笔成功。
     */
    @DisplayName("分组现金仅够一笔买入时并发买入恰一笔成功")
    @Test
    void whenCashCoversOnlyOneBuyAndTwoConcurrentBuys_thenExactlyOneSucceeds() throws Exception {
        var group = service.createGroup(USER_ID, new CreateGroupCommand("华泰", GroupType.ACCOUNT));
        // 转入恰好一笔买入成本（1500×100+5）：第二笔无论如何都应被拒
        service.addCashTransaction(USER_ID, new CashTransactionCommand(group.id(), CashTransactionType.DEPOSIT,
                new BigDecimal("150005"), LocalDate.now(), "仅够一笔"));

        String[] codes = {"600519", "000858"};
        String[] names = {"贵州茅台", "五粮液"};
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger seq = new AtomicInteger();
        List<PortfolioException> failures = Collections.synchronizedList(new ArrayList<>());

        // race 任一 Callable 抛异常会在 future.get 处直接炸测试，故此处各自捕获断言「恰一成功」
        race(2, () -> {
            int i = seq.getAndIncrement();
            try {
                service.buy(USER_ID, new BuyCommand(group.id(), codes[i], names[i],
                        LocalDate.now(), new BigDecimal("1500"), new BigDecimal("100"), new BigDecimal("5")));
                successes.incrementAndGet();
            } catch (PortfolioException e) {
                failures.add(e);
            }
            return null;
        });

        assertThat(successes.get()).as("同组并发买入恰一笔成功").isEqualTo(1);
        assertThat(failures).hasSize(1);
        assertThat(failures.get(0).code()).isEqualTo(PortfolioErrorCode.INSUFFICIENT_CASH);

        // 终态按 SQL 直查流水重算分组现金（转入−转出+卖出−买入）≥ 0：账本不得透支
        BigDecimal cash = jdbcTemplate.queryForObject(
                "SELECT (SELECT COALESCE(SUM(CASE WHEN type = 'DEPOSIT' THEN amount ELSE -amount END), 0) "
                        + "FROM cash_transaction WHERE group_id = ?) "
                        + "- (SELECT COALESCE(SUM(price * quantity + fee), 0) FROM trade "
                        + "WHERE type = 'BUY' AND position_id IN (SELECT id FROM position WHERE group_id = ?)) "
                        + "+ (SELECT COALESCE(SUM(price * quantity - fee), 0) FROM trade "
                        + "WHERE type = 'SELL' AND position_id IN (SELECT id FROM position WHERE group_id = ?))",
                BigDecimal.class, group.id(), group.id(), group.id());
        assertThat(cash).as("SQL 重算终态现金不为负").isNotNegative();
    }
}
