package com.portfolio.invest.domain.portfolio;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 持仓组合仓库端口：归属过滤（userId/portfolioId）在用例层双重保障。 */
public interface PortfolioRepository {

    // Portfolio
    Optional<Portfolio> findPortfolioByUserId(Long userId);
    /** 幂等插入组合：已存在则跳过；用于并发首次访问的原子创建。 */
    void insertPortfolioIfAbsent(Long userId);

    // HoldingGroup
    List<HoldingGroup> findGroupsByPortfolioId(Long portfolioId);
    Optional<HoldingGroup> findGroupByIdAndPortfolioId(Long id, Long portfolioId);
    /** 组行悲观锁（SELECT ... FOR UPDATE）：现金写路径（buy/现金转出）先锁组行再读算现金，消除 TOCTOU；锁不到与 find 语义一致返回空。 */
    Optional<HoldingGroup> lockGroupByIdAndPortfolioId(Long id, Long portfolioId);
    HoldingGroup saveGroup(HoldingGroup group);
    void deleteGroup(Long id);

    // Position
    List<Position> findPositionsByPortfolioId(Long portfolioId);
    List<Position> findPositionsByGroupId(Long groupId);
    Optional<Position> findPositionByIdAndPortfolioId(Long id, Long portfolioId);
    Optional<Position> findPositionByPortfolioIdAndGroupIdAndStockCode(Long portfolioId, Long groupId, String stockCode);
    Position savePosition(Position position);
    void deletePosition(Long id);

    // Trade
    List<Trade> findTradesByPositionId(Long positionId);
    /** 组合级批量：全组合（跨持仓跨分组）流水一次取回，消除逐持仓 N+1。 */
    List<Trade> findTradesByPortfolioId(Long portfolioId);
    /** 组合级区间批量：日期条件下推（两端含边界，from/to 可空），消除无界载入后内存过滤。 */
    List<Trade> findTradesByPortfolioIdInRange(Long portfolioId, LocalDate from, LocalDate to);
    Optional<Trade> findTradeById(Long id);
    Trade saveTrade(Trade trade);

    // Dividend
    List<Dividend> findDividendsByPositionId(Long positionId);
    /** 组合级批量：全组合（跨持仓跨分组）分红一次取回，消除逐持仓 N+1。 */
    List<Dividend> findDividendsByPortfolioId(Long portfolioId);
    /** 组合级区间批量：除息日条件下推（两端含边界，from/to 可空），消除无界载入后内存过滤。 */
    List<Dividend> findDividendsByPortfolioIdInRange(Long portfolioId, LocalDate from, LocalDate to);
    Dividend saveDividend(Dividend dividend);

    // CashTransaction
    List<CashTransaction> findCashTransactionsByGroupId(Long groupId);
    CashTransaction saveCashTransaction(CashTransaction tx);
}
