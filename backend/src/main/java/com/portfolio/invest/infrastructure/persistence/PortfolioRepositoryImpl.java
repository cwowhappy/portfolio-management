package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.portfolio.CashTransaction;
import com.portfolio.invest.domain.portfolio.Dividend;
import com.portfolio.invest.domain.portfolio.HoldingGroup;
import com.portfolio.invest.domain.portfolio.Portfolio;
import com.portfolio.invest.domain.portfolio.PortfolioRepository;
import com.portfolio.invest.domain.portfolio.Position;
import com.portfolio.invest.domain.portfolio.Trade;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

// 事务边界在 application 层（P2）；本类不挂 @Transactional，底层 JpaRepository 自带事务。
@Repository
public class PortfolioRepositoryImpl implements PortfolioRepository {

    private final PortfolioJpaRepository portfolioJpa;
    private final HoldingGroupJpaRepository groupJpa;
    private final PositionJpaRepository positionJpa;
    private final TradeJpaRepository tradeJpa;
    private final DividendJpaRepository dividendJpa;
    private final CashTransactionJpaRepository cashTxJpa;

    public PortfolioRepositoryImpl(PortfolioJpaRepository portfolioJpa,
                                   HoldingGroupJpaRepository groupJpa,
                                   PositionJpaRepository positionJpa,
                                   TradeJpaRepository tradeJpa,
                                   DividendJpaRepository dividendJpa,
                                   CashTransactionJpaRepository cashTxJpa) {
        this.portfolioJpa = portfolioJpa;
        this.groupJpa = groupJpa;
        this.positionJpa = positionJpa;
        this.tradeJpa = tradeJpa;
        this.dividendJpa = dividendJpa;
        this.cashTxJpa = cashTxJpa;
    }

    @Override
    public Optional<Portfolio> findPortfolioByUserId(Long userId) {
        return portfolioJpa.findByUserId(userId).map(PortfolioJpaEntity::toDomain);
    }

    @Override
    public Portfolio savePortfolio(Portfolio portfolio) {
        return portfolioJpa.save(PortfolioJpaEntity.fromDomain(portfolio)).toDomain();
    }

    @Override
    public void insertPortfolioIfAbsent(Long userId) {
        portfolioJpa.insertPortfolioIfAbsent(userId);
    }

    @Override
    public List<HoldingGroup> findGroupsByPortfolioId(Long portfolioId) {
        return groupJpa.findByPortfolioIdOrderByIdAsc(portfolioId).stream()
                .map(HoldingGroupJpaEntity::toDomain).toList();
    }

    @Override
    public Optional<HoldingGroup> findGroupByIdAndPortfolioId(Long id, Long portfolioId) {
        return groupJpa.findByIdAndPortfolioId(id, portfolioId).map(HoldingGroupJpaEntity::toDomain);
    }

    @Override
    public HoldingGroup saveGroup(HoldingGroup group) {
        return groupJpa.save(HoldingGroupJpaEntity.fromDomain(group)).toDomain();
    }

    @Override
    public void deleteGroup(Long id) {
        groupJpa.deleteById(id);
    }

    @Override
    public List<Position> findPositionsByPortfolioId(Long portfolioId) {
        return positionJpa.findByPortfolioId(portfolioId).stream()
                .map(PositionJpaEntity::toDomain).toList();
    }

    @Override
    public List<Position> findPositionsByGroupId(Long groupId) {
        return positionJpa.findByGroupId(groupId).stream()
                .map(PositionJpaEntity::toDomain).toList();
    }

    @Override
    public Optional<Position> findPositionByIdAndPortfolioId(Long id, Long portfolioId) {
        return positionJpa.findByIdAndPortfolioId(id, portfolioId).map(PositionJpaEntity::toDomain);
    }

    @Override
    public Optional<Position> findPositionByPortfolioIdAndGroupIdAndStockCode(Long portfolioId, Long groupId, String stockCode) {
        return positionJpa.findByPortfolioIdAndGroupIdAndStockCode(portfolioId, groupId, stockCode)
                .map(PositionJpaEntity::toDomain);
    }

    @Override
    public Position savePosition(Position position) {
        return positionJpa.save(PositionJpaEntity.fromDomain(position)).toDomain();
    }

    @Override
    public void deletePosition(Long id) {
        positionJpa.deleteById(id);
    }

    @Override
    public List<Trade> findTradesByPositionId(Long positionId) {
        return tradeJpa.findByPositionIdOrderByIdAsc(positionId).stream()
                .map(TradeJpaEntity::toDomain).toList();
    }

    @Override
    public Optional<Trade> findTradeById(Long id) {
        return tradeJpa.findById(id).map(TradeJpaEntity::toDomain);
    }

    @Override
    public Trade saveTrade(Trade trade) {
        return tradeJpa.save(TradeJpaEntity.fromDomain(trade)).toDomain();
    }

    @Override
    public List<Dividend> findDividendsByPositionId(Long positionId) {
        return dividendJpa.findByPositionIdOrderByIdAsc(positionId).stream()
                .map(DividendJpaEntity::toDomain).toList();
    }

    @Override
    public Dividend saveDividend(Dividend dividend) {
        return dividendJpa.save(DividendJpaEntity.fromDomain(dividend)).toDomain();
    }

    @Override
    public List<CashTransaction> findCashTransactionsByGroupId(Long groupId) {
        return cashTxJpa.findByGroupIdOrderByIdAsc(groupId).stream()
                .map(CashTransactionJpaEntity::toDomain).toList();
    }

    @Override
    public CashTransaction saveCashTransaction(CashTransaction tx) {
        return cashTxJpa.save(CashTransactionJpaEntity.fromDomain(tx)).toDomain();
    }
}
