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
import com.portfolio.invest.support.PostgresTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// @DataJpaTest 切片 + 真实 PG：@ServiceConnection 复用 testFixtures 的 JVM 单例容器，整个测试进程只起一个 Postgres。
// Boot 4 的 @DataJpaTest 不含 Flyway 自动配置（schema 由 Flyway 管），需 @ImportAutoConfiguration 显式引入；
// RepositoryImpl 适配器不在切片扫描范围内，用 @Import 显式装配。
// @DataJpaTest 默认每个用例事务回滚：@BeforeEach 植入的固定 app_user 行（42/43/44）随事务回滚，不污染其他用例。
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(PortfolioRepositoryImpl.class)
class PortfolioRepositoryImplTest {

    @ServiceConnection
    static PostgreSQLContainer<?> postgres = PostgresTestSupport.postgres();

    @Autowired
    private PortfolioRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

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

    @DisplayName("组合按用户保存与查询")
    @Test
    void whenSavePortfolio_thenQueryableByUser() {
        Portfolio saved = savePortfolio(42L);
        assertThat(saved.id()).isNotNull();
        assertThat(repository.findPortfolioByUserId(42L)).isPresent();
    }

    @DisplayName("分组与持仓按组合查询")
    @Test
    void givenGroupAndPosition_whenQueryByPortfolio_thenReturned() {
        Portfolio p = savePortfolio(43L);
        HoldingGroup g = repository.saveGroup(HoldingGroup.create(p.id(), "华泰", GroupType.ACCOUNT, Instant.now()));

        Position pos = Position.create(p.id(), g.id(), "600519", "贵州茅台", Instant.now())
                .applyBuy(new BigDecimal("1500"), new BigDecimal("100"), new BigDecimal("5"));
        Position savedPos = repository.savePosition(pos);

        assertThat(repository.findGroupsByPortfolioId(p.id())).hasSize(1);
        assertThat(repository.findPositionsByPortfolioId(p.id())).hasSize(1);
        assertThat(savedPos.avgCost()).isEqualByComparingTo("1500.05");
    }

    @DisplayName("删除分组前先清空持仓")
    @Test
    void givenGroupWithPosition_whenDeletePositionThenGroup_thenGroupDeleted() {
        Portfolio p = savePortfolio(44L);
        HoldingGroup g = repository.saveGroup(HoldingGroup.create(p.id(), "东财", GroupType.ACCOUNT, Instant.now()));
        Position savedPos = repository.savePosition(Position.create(p.id(), g.id(), "000858", "五粮液", Instant.now()));

        // position.group_id 外键无 ON DELETE CASCADE（DB 层兜底）：须先清空持仓再删分组（P2 语义）。
        repository.deletePosition(savedPos.id());
        repository.deleteGroup(g.id());

        assertThat(repository.findPositionsByGroupId(g.id())).isEmpty();
        assertThat(repository.findGroupsByPortfolioId(p.id())).isEmpty();
    }

    @DisplayName("交易分红现金流水往返")
    @Test
    void whenSaveTradeDividendAndCashTx_thenReadBack() {
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

    private void seedTrade(Long positionId, LocalDate tradeDate) {
        repository.saveTrade(new Trade(null, positionId, TradeType.BUY,
                tradeDate, new BigDecimal("10"), new BigDecimal("100"), BigDecimal.ZERO, Instant.now()));
    }

    private void seedCashDividend(Long positionId, LocalDate exDate) {
        repository.saveDividend(new Dividend(null, positionId, DividendType.CASH,
                exDate, new BigDecimal("1.5"), null, Instant.now()));
    }

    @DisplayName("组合级批量流水：跨持仓跨分组全量返回，且只含本组合")
    @Test
    void givenPositionsAcrossGroups_whenFindFlowsByPortfolioId_thenAllFlowsOfThatPortfolioOnly() {
        Portfolio p = savePortfolio(42L);
        HoldingGroup g1 = repository.saveGroup(HoldingGroup.create(p.id(), "华泰", GroupType.ACCOUNT, Instant.now()));
        HoldingGroup g2 = repository.saveGroup(HoldingGroup.create(p.id(), "东财", GroupType.ACCOUNT, Instant.now()));
        Position posA = repository.savePosition(Position.create(p.id(), g1.id(), "600519", "贵州茅台", Instant.now()));
        Position posB = repository.savePosition(Position.create(p.id(), g2.id(), "000858", "五粮液", Instant.now()));
        // 他人组合的流水：不应混入本组合的批量结果
        Portfolio other = savePortfolio(43L);
        HoldingGroup otherGroup = repository.saveGroup(HoldingGroup.create(other.id(), "国君", GroupType.ACCOUNT, Instant.now()));
        Position otherPos = repository.savePosition(
                Position.create(other.id(), otherGroup.id(), "600036", "招商银行", Instant.now()));
        seedTrade(otherPos.id(), LocalDate.of(2026, 8, 1));

        seedTrade(posA.id(), LocalDate.of(2026, 8, 1));
        seedTrade(posB.id(), LocalDate.of(2026, 8, 2));
        seedCashDividend(posA.id(), LocalDate.of(2026, 8, 3));

        assertThat(repository.findTradesByPortfolioId(p.id()))
                .extracting(Trade::positionId)
                .containsExactlyInAnyOrder(posA.id(), posB.id());
        assertThat(repository.findDividendsByPortfolioId(p.id()))
                .extracting(Dividend::positionId)
                .containsExactly(posA.id());
    }

    @DisplayName("组合级交易区间下推：两端含边界，from/to 可空四分支")
    @Test
    void givenTradesAroundBounds_whenFindTradesByPortfolioIdInRange_thenBoundsInclusiveFourBranches() {
        Portfolio p = savePortfolio(42L);
        HoldingGroup g = repository.saveGroup(HoldingGroup.create(p.id(), "华泰", GroupType.ACCOUNT, Instant.now()));
        Position posA = repository.savePosition(Position.create(p.id(), g.id(), "600519", "贵州茅台", Instant.now()));
        Position posB = repository.savePosition(Position.create(p.id(), g.id(), "000858", "五粮液", Instant.now()));
        LocalDate before = LocalDate.of(2026, 8, 1);
        LocalDate from = LocalDate.of(2026, 8, 10);
        LocalDate mid = LocalDate.of(2026, 8, 20);
        LocalDate to = LocalDate.of(2026, 8, 31);
        LocalDate after = LocalDate.of(2026, 9, 5);
        // 两持仓交错落流水：区间下推须跨持仓生效
        seedTrade(posA.id(), before);
        seedTrade(posB.id(), from);
        seedTrade(posA.id(), mid);
        seedTrade(posB.id(), to);
        seedTrade(posA.id(), after);

        assertThat(repository.findTradesByPortfolioIdInRange(p.id(), from, to))
                .extracting(Trade::tradeDate).containsExactlyInAnyOrder(from, mid, to);
        assertThat(repository.findTradesByPortfolioIdInRange(p.id(), from, null))
                .extracting(Trade::tradeDate).containsExactlyInAnyOrder(from, mid, to, after);
        assertThat(repository.findTradesByPortfolioIdInRange(p.id(), null, to))
                .extracting(Trade::tradeDate).containsExactlyInAnyOrder(before, from, mid, to);
        assertThat(repository.findTradesByPortfolioIdInRange(p.id(), null, null))
                .extracting(Trade::tradeDate).containsExactlyInAnyOrder(before, from, mid, to, after);
    }

    @DisplayName("组合级分红区间下推：两端含边界，from/to 可空四分支")
    @Test
    void givenDividendsAroundBounds_whenFindDividendsByPortfolioIdInRange_thenBoundsInclusiveFourBranches() {
        Portfolio p = savePortfolio(42L);
        HoldingGroup g = repository.saveGroup(HoldingGroup.create(p.id(), "华泰", GroupType.ACCOUNT, Instant.now()));
        Position posA = repository.savePosition(Position.create(p.id(), g.id(), "600519", "贵州茅台", Instant.now()));
        Position posB = repository.savePosition(Position.create(p.id(), g.id(), "000858", "五粮液", Instant.now()));
        LocalDate before = LocalDate.of(2026, 8, 1);
        LocalDate from = LocalDate.of(2026, 8, 10);
        LocalDate mid = LocalDate.of(2026, 8, 20);
        LocalDate to = LocalDate.of(2026, 8, 31);
        LocalDate after = LocalDate.of(2026, 9, 5);
        seedCashDividend(posA.id(), before);
        seedCashDividend(posB.id(), from);
        seedCashDividend(posA.id(), mid);
        seedCashDividend(posB.id(), to);
        seedCashDividend(posA.id(), after);

        assertThat(repository.findDividendsByPortfolioIdInRange(p.id(), from, to))
                .extracting(Dividend::exDate).containsExactlyInAnyOrder(from, mid, to);
        assertThat(repository.findDividendsByPortfolioIdInRange(p.id(), from, null))
                .extracting(Dividend::exDate).containsExactlyInAnyOrder(from, mid, to, after);
        assertThat(repository.findDividendsByPortfolioIdInRange(p.id(), null, to))
                .extracting(Dividend::exDate).containsExactlyInAnyOrder(before, from, mid, to);
        assertThat(repository.findDividendsByPortfolioIdInRange(p.id(), null, null))
                .extracting(Dividend::exDate).containsExactlyInAnyOrder(before, from, mid, to, after);
    }

    /**
     * MS-28 P2-B3 组行悲观锁：T1 取锁未提交期间，T2 对同一组行 lock 必须（阻塞在
     * SELECT ... FOR UPDATE 上）拿不到；T1 提交后 T2 立即获锁并读到该行。
     * 断言形态选型：不用 SQL 文本断言（脆弱、不证行为），用 CountDownLatch 控制
     * T1 持锁窗口 + T2 future.get(500ms) 超时证明阻塞——T1 持锁期间 T2 不可能完成，方向性确定。
     */
    @DisplayName("组行悲观锁：第二个事务阻塞直至第一个提交")
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED) // 关闭测试托管事务：种子须为提交态，并发事务才可见
    void whenLockSameGroupInTwoTransactions_thenSecondBlocksUntilFirstCommits() throws Exception {
        // NOT_SUPPORTED 下本用例（含 @BeforeEach 的 42/43/44）均为即时提交，收尾须手动清理共享容器
        jdbcTemplate.update(
                "INSERT INTO app_user(id, username, password_hash, role, status) VALUES (?, ?, ?, ?, ?)",
                45L, "portfolio-t4-45", "h", "USER", "PENDING");
        Long portfolioId = null;
        try {
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            // 种子放同一提交态事务（@Modifying insertPortfolioIfAbsent 需写事务；提交后并发事务才可见）
            final HoldingGroup g = tx.execute(status -> {
                repository.insertPortfolioIfAbsent(45L);
                Portfolio p = repository.findPortfolioByUserId(45L).orElseThrow();
                return repository.saveGroup(HoldingGroup.create(p.id(), "锁组", GroupType.ACCOUNT, Instant.now()));
            });
            portfolioId = g.portfolioId();

            CountDownLatch locked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch secondStarted = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                // T1：取组行锁后持锁等待，release 被唤起才返回（返回即提交、释放锁）
                Future<?> first = pool.submit(() -> tx.executeWithoutResult(s -> {
                    assertThat(repository.lockGroupByIdAndPortfolioId(g.id(), g.portfolioId())).isPresent();
                    locked.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
                assertThat(locked.await(10, TimeUnit.SECONDS)).as("T1 应先取得组行锁").isTrue();

                // T2：已起跑但 500ms 内必须仍被挡在 T1 的行锁上（T1 未提交）
                Future<?> second = pool.submit(() -> {
                    secondStarted.countDown();
                    tx.executeWithoutResult(s ->
                            assertThat(repository.lockGroupByIdAndPortfolioId(g.id(), g.portfolioId())).isPresent());
                });
                assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> second.get(500, TimeUnit.MILLISECONDS))
                        .as("T1 持锁未提交，T2 的 FOR UPDATE 应阻塞")
                        .isInstanceOf(TimeoutException.class);

                release.countDown();
                first.get(10, TimeUnit.SECONDS);   // T1 提交、释放行锁
                second.get(10, TimeUnit.SECONDS);  // T2 获锁并读到已提交的组行
            } finally {
                release.countDown();
                pool.shutdownNow();
            }
        } finally {
            // 手动清理（本用例数据不走事务回滚）：子表 holding_group → portfolio → app_user
            if (portfolioId != null) {
                jdbcTemplate.update("DELETE FROM holding_group WHERE portfolio_id = ?", portfolioId);
                jdbcTemplate.update("DELETE FROM portfolio WHERE id = ?", portfolioId);
            }
            jdbcTemplate.update("DELETE FROM app_user WHERE id IN (42, 43, 44, 45)");
        }
    }
}
