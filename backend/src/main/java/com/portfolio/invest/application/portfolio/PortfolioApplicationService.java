package com.portfolio.invest.application.portfolio;

import com.portfolio.invest.application.market.MarketDataService;
import com.portfolio.invest.domain.portfolio.CashTransaction;
import com.portfolio.invest.domain.portfolio.CashTransactionType;
import com.portfolio.invest.domain.portfolio.GroupType;
import com.portfolio.invest.domain.portfolio.HoldingGroup;
import com.portfolio.invest.domain.portfolio.Portfolio;
import com.portfolio.invest.domain.portfolio.PortfolioErrorCode;
import com.portfolio.invest.domain.portfolio.PortfolioException;
import com.portfolio.invest.domain.portfolio.PortfolioRepository;
import com.portfolio.invest.domain.valuation.ValuationRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PortfolioApplicationService {

    private final PortfolioRepository repository;
    private final MarketDataService marketDataService;
    private final ValuationRepository valuationRepository;

    public PortfolioApplicationService(PortfolioRepository repository,
                                       MarketDataService marketDataService,
                                       ValuationRepository valuationRepository) {
        this.repository = repository;
        this.marketDataService = marketDataService;
        this.valuationRepository = valuationRepository;
    }

    /** 单组合/用户：首次访问自动创建。 */
    private Portfolio getOrCreatePortfolio(Long userId) {
        return repository.findPortfolioByUserId(userId)
                .orElseGet(() -> repository.savePortfolio(Portfolio.create(userId, Instant.now())));
    }

    public List<GroupView> groups(Long userId) {
        Portfolio p = getOrCreatePortfolio(userId);
        return repository.findGroupsByPortfolioId(p.id()).stream()
                .map(g -> GroupView.from(g,
                        repository.findPositionsByGroupId(g.id()).size(),
                        cashBalance(g.id(), repository.findPositionsByGroupId(g.id()),
                                repository.findCashTransactionsByGroupId(g.id()))))
                .toList();
    }

    @Transactional
    public GroupView createGroup(Long userId, CreateGroupCommand cmd) {
        Portfolio p = getOrCreatePortfolio(userId);
        HoldingGroup saved = repository.saveGroup(
                HoldingGroup.create(p.id(), cmd.name().trim(), cmd.type(), Instant.now()));
        return GroupView.from(saved, 0, BigDecimal.ZERO);
    }

    @Transactional
    public void deleteGroup(Long userId, Long groupId) {
        Portfolio p = getOrCreatePortfolio(userId);
        requireGroup(p.id(), groupId);
        if (!repository.findPositionsByGroupId(groupId).isEmpty()) {
            throw new PortfolioException(PortfolioErrorCode.GROUP_NOT_EMPTY, "分组内还有持仓，请先清空");
        }
        repository.deleteGroup(groupId);
    }

    @Transactional
    public CashTransactionView addCashTransaction(Long userId, CashTransactionCommand cmd) {
        Portfolio p = getOrCreatePortfolio(userId);
        requireGroup(p.id(), cmd.groupId());
        CashTransaction tx = repository.saveCashTransaction(new CashTransaction(
                null, cmd.groupId(), cmd.type(), cmd.amount(), cmd.txDate(), cmd.note(), Instant.now()));
        return CashTransactionView.from(tx);
    }

    public List<CashTransactionView> cashTransactions(Long userId, Long groupId) {
        Portfolio p = getOrCreatePortfolio(userId);
        requireGroup(p.id(), groupId);
        return repository.findCashTransactionsByGroupId(groupId).stream()
                .map(CashTransactionView::from).toList();
    }

    /** 账户现金 = Σ(转入−转出) + Σ该账户持仓.netCashFlow。 */
    private BigDecimal cashBalance(Long groupId,
                                   List<com.portfolio.invest.domain.portfolio.Position> positions,
                                   List<CashTransaction> cashTxs) {
        BigDecimal flow = positions.stream()
                .map(com.portfolio.invest.domain.portfolio.Position::netCashFlow)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal transfers = cashTxs.stream()
                .map(t -> t.type() == CashTransactionType.DEPOSIT ? t.amount() : t.amount().negate())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return flow.add(transfers);
    }

    private HoldingGroup requireGroup(Long portfolioId, Long groupId) {
        return repository.findGroupByIdAndPortfolioId(groupId, portfolioId)
                .orElseThrow(() -> new PortfolioException(PortfolioErrorCode.NOT_FOUND, "分组不存在"));
    }
}
