package com.portfolio.invest.application.portfolio;

import com.portfolio.invest.application.market.MarketDataService;
import com.portfolio.invest.domain.market.Quote;
import com.portfolio.invest.domain.portfolio.CashTransaction;
import com.portfolio.invest.domain.portfolio.CashTransactionType;
import com.portfolio.invest.domain.portfolio.Dividend;
import com.portfolio.invest.domain.portfolio.DividendType;
import com.portfolio.invest.domain.portfolio.GroupType;
import com.portfolio.invest.domain.portfolio.HoldingGroup;
import com.portfolio.invest.domain.portfolio.Portfolio;
import com.portfolio.invest.domain.portfolio.PortfolioErrorCode;
import com.portfolio.invest.domain.portfolio.PortfolioException;
import com.portfolio.invest.domain.portfolio.PortfolioRepository;
import com.portfolio.invest.domain.portfolio.Position;
import com.portfolio.invest.domain.portfolio.Trade;
import com.portfolio.invest.domain.portfolio.TradeType;
import com.portfolio.invest.domain.valuation.ValuationRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PortfolioApplicationService {

    private final PortfolioRepository repository;
    private final MarketDataService marketDataService;
    private final ValuationRepository valuationRepository;
    private final PortfolioCreationService portfolioCreation;

    public PortfolioApplicationService(PortfolioRepository repository,
                                       MarketDataService marketDataService,
                                       ValuationRepository valuationRepository,
                                       PortfolioCreationService portfolioCreation) {
        this.repository = repository;
        this.marketDataService = marketDataService;
        this.valuationRepository = valuationRepository;
        this.portfolioCreation = portfolioCreation;
    }

    /** 单组合/用户：首次访问自动创建（并发安全，幂等）。 */
    private Portfolio getOrCreatePortfolio(Long userId) {
        Optional<Portfolio> existing = repository.findPortfolioByUserId(userId);
        if (existing.isPresent()) {
            return existing.get();
        }
        portfolioCreation.ensureCreated(userId);
        return repository.findPortfolioByUserId(userId)
                .orElseThrow(() -> new PortfolioException(PortfolioErrorCode.NOT_FOUND, "组合创建失败"));
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
    public GroupView renameGroup(Long userId, Long groupId, RenameGroupCommand cmd) {
        Portfolio p = getOrCreatePortfolio(userId);
        HoldingGroup group = requireGroup(p.id(), groupId);
        HoldingGroup saved = repository.saveGroup(group.rename(cmd.name().trim()));
        var positions = repository.findPositionsByGroupId(groupId);
        return GroupView.from(saved, positions.size(),
                cashBalance(groupId, positions, repository.findCashTransactionsByGroupId(groupId)));
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

    @Transactional
    public PositionView buy(Long userId, BuyCommand cmd) {
        Portfolio p = getOrCreatePortfolio(userId);
        requireGroup(p.id(), cmd.groupId());
        var position = repository
                .findPositionByPortfolioIdAndGroupIdAndStockCode(p.id(), cmd.groupId(), cmd.stockCode())
                .orElseGet(() -> Position.create(p.id(), cmd.groupId(), cmd.stockCode(), cmd.stockName(), Instant.now()));
        var updated = position.applyBuy(cmd.price(), cmd.quantity(), cmd.fee());
        // 关键：新建持仓时 updated.id() 为 null，必须用 savePosition 返回的已持久化实体（含生成的 id）
        var saved = repository.savePosition(updated);
        repository.saveTrade(new Trade(
                null, saved.id(), TradeType.BUY,
                cmd.tradeDate(), cmd.price(), cmd.quantity(), cmd.fee(), Instant.now()));
        return positionView(saved);
    }

    @Transactional
    public PositionView sell(Long userId, SellCommand cmd) {
        Portfolio p = getOrCreatePortfolio(userId);
        var position = requirePosition(p.id(), cmd.positionId());
        var updated = position.applySell(cmd.price(), cmd.quantity(), cmd.fee());
        repository.savePosition(updated);
        repository.saveTrade(new Trade(
                null, updated.id(), TradeType.SELL,
                cmd.tradeDate(), cmd.price(), cmd.quantity(), cmd.fee(), Instant.now()));
        return positionView(updated);
    }

    @Transactional
    public PositionView editTrade(Long userId, Long positionId, Long tradeId, EditTradeCommand cmd) {
        Portfolio p = getOrCreatePortfolio(userId);
        var position = requirePosition(p.id(), positionId);
        Trade trade = repository.findTradeById(tradeId)
                .orElseThrow(() -> new PortfolioException(PortfolioErrorCode.NOT_FOUND, "交易不存在"));
        // 仅可编辑属于该持仓的买入交易；卖出/他人交易一律不泄露存在性。
        if (!trade.positionId().equals(positionId) || trade.type() != TradeType.BUY) {
            throw new PortfolioException(PortfolioErrorCode.NOT_FOUND, "交易不存在");
        }
        repository.saveTrade(new Trade(
                trade.id(), positionId, TradeType.BUY,
                cmd.tradeDate(), cmd.price(), cmd.quantity(), cmd.fee(), trade.createdAt()));

        var replayed = replay(position,
                repository.findTradesByPositionId(positionId),
                repository.findDividendsByPositionId(positionId));
        var saved = repository.savePosition(replayed);
        return positionView(saved);
    }

    /** 按日期顺序重放全部交易与分红，重建持仓成本/盈亏；保留原 id 与 createdAt。 */
    private Position replay(Position original, List<Trade> trades, List<Dividend> dividends) {
        Position acc = Position.create(original.portfolioId(), original.groupId(),
                original.stockCode(), original.stockName(), original.createdAt());

        List<Trade> sortedTrades = trades.stream()
                .sorted(Comparator.comparing(Trade::tradeDate))
                .toList();
        List<Dividend> sortedDividends = dividends.stream()
                .sorted(Comparator.comparing(Dividend::exDate))
                .toList();

        int ti = 0, di = 0;
        while (ti < sortedTrades.size() || di < sortedDividends.size()) {
            Trade t = ti < sortedTrades.size() ? sortedTrades.get(ti) : null;
            Dividend d = di < sortedDividends.size() ? sortedDividends.get(di) : null;
            // 同日先交易后分红（买入/卖出先行，分红再按当时股数/成本作用）。
            if (d == null || (t != null && !t.tradeDate().isAfter(d.exDate()))) {
                acc = t.type() == TradeType.BUY
                        ? acc.applyBuy(t.price(), t.quantity(), t.fee())
                        : acc.applySell(t.price(), t.quantity(), t.fee());
                ti++;
            } else {
                acc = d.type() == DividendType.CASH
                        ? acc.applyCashDividend(d.cashPerShare().multiply(acc.quantity()))
                        : acc.applyStockDividend(d.stockRatio());
                di++;
            }
        }

        return Position.reconstitute(original.id(), original.portfolioId(), original.groupId(),
                original.stockCode(), original.stockName(),
                acc.quantity(), acc.costBasis(), acc.totalBuyCost(), acc.cumulativeCashDividend(),
                acc.realizedPnl(), acc.netCashFlow(), original.createdAt(), Instant.now(), original.version());
    }

    @Transactional
    public PositionView addCashDividend(Long userId, CashDividendCommand cmd) {
        Portfolio p = getOrCreatePortfolio(userId);
        var position = requirePosition(p.id(), cmd.positionId());
        repository.saveDividend(new Dividend(
                null, position.id(), DividendType.CASH,
                cmd.exDate(), cmd.cashPerShare(), null, Instant.now()));
        // 与 editTrade 同一 replay 路径：按除息日当时数量重放，避免补录历史分红后账本漂移
        var replayed = replay(position,
                repository.findTradesByPositionId(position.id()),
                repository.findDividendsByPositionId(position.id()));
        var saved = repository.savePosition(replayed);
        return positionView(saved);
    }

    @Transactional
    public PositionView addStockDividend(Long userId, StockDividendCommand cmd) {
        Portfolio p = getOrCreatePortfolio(userId);
        var position = requirePosition(p.id(), cmd.positionId());
        repository.saveDividend(new Dividend(
                null, position.id(), DividendType.STOCK,
                cmd.exDate(), null, cmd.stockRatio(), Instant.now()));
        // 与 editTrade 同一 replay 路径：按除息日当时数量重放
        var replayed = replay(position,
                repository.findTradesByPositionId(position.id()),
                repository.findDividendsByPositionId(position.id()));
        var saved = repository.savePosition(replayed);
        return positionView(saved);
    }

    @Transactional
    public void deletePosition(Long userId, Long positionId) {
        Portfolio p = getOrCreatePortfolio(userId);
        requirePosition(p.id(), positionId);
        repository.deletePosition(positionId);
    }

    public List<TradeView> trades(Long userId, Long positionId) {
        Portfolio p = getOrCreatePortfolio(userId);
        requirePosition(p.id(), positionId);
        return repository.findTradesByPositionId(positionId).stream().map(TradeView::from).toList();
    }

    public List<DividendView> dividends(Long userId, Long positionId) {
        Portfolio p = getOrCreatePortfolio(userId);
        requirePosition(p.id(), positionId);
        return repository.findDividendsByPositionId(positionId).stream().map(DividendView::from).toList();
    }

    public List<PositionView> positions(Long userId, Long groupId) {
        Portfolio p = getOrCreatePortfolio(userId);
        if (groupId != null) {
            requireGroup(p.id(), groupId);
        }
        List<Position> list = groupId == null
                ? repository.findPositionsByPortfolioId(p.id())
                : repository.findPositionsByGroupId(groupId);
        return list.stream().map(this::positionView).toList();
    }

    public PortfolioOverviewView overview(Long userId) {
        Portfolio p = getOrCreatePortfolio(userId);
        var positions = repository.findPositionsByPortfolioId(p.id());
        var groups = repository.findGroupsByPortfolioId(p.id());
        BigDecimal totalAssets = BigDecimal.ZERO, totalCost = BigDecimal.ZERO,
                totalPnl = BigDecimal.ZERO, todayPnl = BigDecimal.ZERO,
                cashTotal = BigDecimal.ZERO, totalCashDividend = BigDecimal.ZERO;

        for (var pos : positions) {
            var q = quoteQuietly(pos.stockCode());
            totalCost = totalCost.add(pos.totalBuyCost());
            totalCashDividend = totalCashDividend.add(pos.cumulativeCashDividend());
            if (q != null) {
                var price = BigDecimal.valueOf(q.price());
                totalAssets = totalAssets.add(price.multiply(pos.quantity()));
                totalPnl = totalPnl.add(price.multiply(pos.quantity()).subtract(pos.costBasis()).add(pos.realizedPnl()));
                todayPnl = todayPnl.add(price.subtract(BigDecimal.valueOf(q.prevClose())).multiply(pos.quantity()));
            } else {
                totalPnl = totalPnl.add(pos.realizedPnl());
            }
        }
        for (var g : groups) {
            if (g.type() == GroupType.ACCOUNT) {
                BigDecimal c = cashBalance(g.id(), repository.findPositionsByGroupId(g.id()),
                        repository.findCashTransactionsByGroupId(g.id()));
                cashTotal = cashTotal.add(c);
                totalAssets = totalAssets.add(c);
            }
        }
        return new PortfolioOverviewView(totalAssets, totalCost, totalPnl, todayPnl, cashTotal,
                totalCashDividend, positions.size(), groups.size());
    }

    public AssetAllocationView allocation(Long userId) {
        Portfolio p = getOrCreatePortfolio(userId);
        var positions = repository.findPositionsByPortfolioId(p.id());
        BigDecimal equity = BigDecimal.ZERO;
        for (var pos : positions) {
            var q = quoteQuietly(pos.stockCode());
            if (q != null) {
                equity = equity.add(BigDecimal.valueOf(q.price()).multiply(pos.quantity()));
            }
        }
        BigDecimal cash = BigDecimal.ZERO;
        for (var g : repository.findGroupsByPortfolioId(p.id())) {
            if (g.type() == GroupType.ACCOUNT) {
                cash = cash.add(cashBalance(g.id(), repository.findPositionsByGroupId(g.id()),
                        repository.findCashTransactionsByGroupId(g.id())));
            }
        }
        BigDecimal total = equity.add(cash);
        return new AssetAllocationView(List.of(
                new AssetAllocationView.Slice("权益", equity, ratio(equity, total)),
                new AssetAllocationView.Slice("现金", cash, ratio(cash, total))));
    }

    public IndustryDistributionView industryDistribution(Long userId) {
        Portfolio p = getOrCreatePortfolio(userId);
        var positions = repository.findPositionsByPortfolioId(p.id());
        var mapping = valuationRepository.findAllIndustryMappings().stream()
                .collect(Collectors.toMap(
                        m -> m.stockCode(), m -> m.industryName(), (a, b) -> a));

        Map<String, BigDecimal> byIndustry = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (var pos : positions) {
            String industry = mapping.get(pos.stockCode());
            if (industry == null) {
                continue; // ETF 或无映射的标的排除
            }
            var q = quoteQuietly(pos.stockCode());
            if (q == null) {
                continue;
            }
            var mv = BigDecimal.valueOf(q.price()).multiply(pos.quantity());
            byIndustry.merge(industry, mv, BigDecimal::add);
            total = total.add(mv);
        }
        final BigDecimal totalMarketValue = total;
        var slices = byIndustry.entrySet().stream()
                .map(e -> new IndustryDistributionView.Slice(e.getKey(), e.getValue(), ratio(e.getValue(), totalMarketValue)))
                .sorted(Comparator.comparing(IndustryDistributionView.Slice::marketValue).reversed())
                .toList();
        return new IndustryDistributionView(slices);
    }

    public ConcentrationView concentration(Long userId) {
        Portfolio p = getOrCreatePortfolio(userId);
        var positions = repository.findPositionsByPortfolioId(p.id()).stream()
                .map(this::positionView)
                .filter(v -> v.marketValue() != null)
                .sorted(Comparator.comparing(PositionView::marketValue).reversed())
                .toList();
        BigDecimal total = positions.stream().map(PositionView::marketValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var top5 = positions.stream().limit(5)
                .map(v -> new ConcentrationView.Holding(v.stockCode(), v.stockName(),
                        v.marketValue(), ratio(v.marketValue(), total)))
                .toList();
        var top5Ratio = top5.stream().map(ConcentrationView.Holding::ratio)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new ConcentrationView(top5, top5Ratio);
    }

    private Position requirePosition(Long portfolioId, Long positionId) {
        return repository.findPositionByIdAndPortfolioId(positionId, portfolioId)
                .orElseThrow(() -> new PortfolioException(PortfolioErrorCode.NOT_FOUND, "持仓不存在"));
    }

    private PositionView positionView(Position pos) {
        return PositionView.from(pos, quoteQuietly(pos.stockCode()));
    }

    private Quote quoteQuietly(String code) {
        try {
            return marketDataService.quote(code);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static BigDecimal ratio(BigDecimal part, BigDecimal total) {
        if (total.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return part.divide(total, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
    }
}
