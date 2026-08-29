package com.portfolio.invest.domain.portfolio;

import java.util.List;
import java.util.Optional;

/** 持仓组合仓库端口：归属过滤（userId/portfolioId）在用例层双重保障。 */
public interface PortfolioRepository {

    // Portfolio
    Optional<Portfolio> findPortfolioByUserId(Long userId);
    Portfolio savePortfolio(Portfolio portfolio);
    /** 幂等插入组合：已存在则跳过；用于并发首次访问的原子创建。 */
    void insertPortfolioIfAbsent(Long userId);

    // HoldingGroup
    List<HoldingGroup> findGroupsByPortfolioId(Long portfolioId);
    Optional<HoldingGroup> findGroupByIdAndPortfolioId(Long id, Long portfolioId);
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
    Trade saveTrade(Trade trade);

    // Dividend
    List<Dividend> findDividendsByPositionId(Long positionId);
    Dividend saveDividend(Dividend dividend);

    // CashTransaction
    List<CashTransaction> findCashTransactionsByGroupId(Long groupId);
    CashTransaction saveCashTransaction(CashTransaction tx);
}
