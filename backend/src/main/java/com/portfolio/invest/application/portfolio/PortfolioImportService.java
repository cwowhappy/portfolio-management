package com.portfolio.invest.application.portfolio;

import com.portfolio.invest.domain.portfolio.CashTransaction;
import com.portfolio.invest.domain.portfolio.CashTransactionType;
import com.portfolio.invest.domain.portfolio.Dividend;
import com.portfolio.invest.domain.portfolio.DividendType;
import com.portfolio.invest.domain.portfolio.GroupType;
import com.portfolio.invest.domain.portfolio.HoldingGroup;
import com.portfolio.invest.domain.portfolio.ImportRow;
import com.portfolio.invest.domain.portfolio.ImportRow.ImportRowType;
import com.portfolio.invest.domain.portfolio.ImportSimulator;
import com.portfolio.invest.domain.portfolio.Portfolio;
import com.portfolio.invest.domain.portfolio.PortfolioErrorCode;
import com.portfolio.invest.domain.portfolio.PortfolioException;
import com.portfolio.invest.domain.portfolio.PortfolioRepository;
import com.portfolio.invest.domain.portfolio.Position;
import com.portfolio.invest.domain.portfolio.Trade;
import com.portfolio.invest.domain.portfolio.TradeType;
import com.portfolio.invest.domain.valuation.ValuationRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CSV 导入编排服务（设计规格 §1.2 五层校验管线 + §1.4 执行）：
 * L1/L2/L4 由 {@link CsvImportParser} 完成；本层补 L3（分组名→实体，存在且 ACCOUNT；
 * 证券代码 ∈ 用户历史持仓 ∪ stock_valuation_daily 最新快照）与
 * L5（模拟重放通过后同事务执行）。执行阶段逐行调领域实体方法演化（applyBuy/applySell/
 * applyCashDividend/applyStockDividend），<b>不</b>逐条调 PortfolioApplicationService 的
 * buy()/sell()——那会每笔触发一次行情调用（quoteQuietly），2000 行上限会串行打满行情源。
 * 任何一层有错即全量拒绝（all-or-nothing），importedCount=0。
 */
@Service
public class PortfolioImportService {

    private final PortfolioRepository repository;
    private final ValuationRepository valuationRepository;

    /** 解析器无状态且非 Spring bean（Task 3 零注解交付），直接持有实例即可。 */
    private final CsvImportParser parser = new CsvImportParser();

    public PortfolioImportService(PortfolioRepository repository, ValuationRepository valuationRepository) {
        this.repository = repository;
        this.valuationRepository = valuationRepository;
    }

    @Transactional
    public ImportResult importCsv(Long userId, String csvContent) {
        // L1/L2/L4：解析层错误（含未来日期）直接透传，不触达 repository
        var parsed = parser.parse(csvContent, LocalDate.now());
        if (!parsed.errors().isEmpty()) {
            return new ImportResult(0, parsed.errors());
        }

        Portfolio portfolio = repository.findPortfolioByUserId(userId)
                .orElseThrow(() -> new PortfolioException(PortfolioErrorCode.NOT_FOUND, "组合不存在"));

        // L3：分组名→实体。同名多组取最早（holding_group.name 无唯一约束，文档化近似）；
        // 可导入映射只收 ACCOUNT，非 ACCOUNT 同名单列以报差异化文案（TAG 不可交易/不可挂现金）
        Map<String, HoldingGroup> accountGroups = new LinkedHashMap<>();
        Map<String, HoldingGroup> nonAccountGroups = new LinkedHashMap<>();
        for (HoldingGroup g : repository.findGroupsByPortfolioId(portfolio.id())) {
            (g.type() == GroupType.ACCOUNT ? accountGroups : nonAccountGroups).putIfAbsent(g.name(), g);
        }
        List<ImportSimulator.RowError> errors = new ArrayList<>();
        List<ImportRow> resolved = new ArrayList<>();
        // L3 证券代码存在性：允许集 = 该用户全部历史持仓（含已清仓）∪ stock_valuation_daily 最新快照。
        // 只有文件确有带代码的行才装集合（纯 DEPOSIT/WITHDRAW 文件零额外查询）
        Set<String> knownStockCodes = knownStockCodes(portfolio, parsed.rows());
        for (ImportRow raw : parsed.rows()) {
            HoldingGroup g = accountGroups.get(raw.groupName());
            if (g == null) {
                errors.add(new ImportSimulator.RowError(raw.rowNumber(), nonAccountGroups.containsKey(raw.groupName())
                        ? "分组「" + raw.groupName() + "」是标签分组（TAG），仅支持账户分组导入"
                        : "分组不存在「" + raw.groupName() + "」，请先在页面创建或修改 CSV"));
                continue;
            }
            if (raw.stockCode() != null && !knownStockCodes.contains(raw.stockCode())) {
                errors.add(new ImportSimulator.RowError(raw.rowNumber(),
                        "未知证券代码 " + raw.stockCode()));
                continue;
            }
            resolved.add(raw.withGroupId(g.id()));
        }
        if (!errors.isEmpty()) {
            return new ImportResult(0, errors);
        }

        // L5：在当前库态之上模拟重放，非空即全量拒绝（零落库）
        List<ImportSimulator.RowError> simulated = ImportSimulator.simulate(startState(resolved), resolved);
        if (!simulated.isEmpty()) {
            return new ImportResult(0, simulated);
        }

        // 执行：与模拟器同序逐行演化落库
        execute(portfolio, sorted(resolved));
        return new ImportResult(resolved.size(), List.of());
    }

    /** L3 允许代码集：用户全部持仓（含已清仓）∪ 最新快照；无代码行的文件返回空集（不查库）。 */
    private Set<String> knownStockCodes(Portfolio portfolio, List<ImportRow> rows) {
        if (rows.stream().noneMatch(r -> r.stockCode() != null)) {
            return Set.of();
        }
        Set<String> codes = new HashSet<>(valuationRepository.findLatestSnapshotStockCodes());
        repository.findPositionsByPortfolioId(portfolio.id())
                .forEach(p -> codes.add(p.stockCode()));
        return codes;
    }

    /**
     * L5 起点态组装：组现金 = Σ该组持仓.netCashFlow + Σ(转入−转出)——与
     * {@code PortfolioApplicationService#cashBalance} 同构手写（不逐组调读侧用例以免绕行）；
     * 持仓数量 key = groupId + "|" + stockCode（模拟器契约）。只装涉及分组即可：模拟器
     * 只会读这些组的现金/持仓（L3 已保证全是 ACCOUNT——现金语义分组）。
     */
    private ImportSimulator.StartState startState(List<ImportRow> rows) {
        Map<Long, BigDecimal> groupCash = new HashMap<>();
        Map<String, BigDecimal> holdingQty = new HashMap<>();
        for (Long groupId : rows.stream().map(ImportRow::groupId).distinct().toList()) {
            List<Position> positions = repository.findPositionsByGroupId(groupId);
            BigDecimal flow = positions.stream()
                    .map(Position::netCashFlow)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal transfers = repository.findCashTransactionsByGroupId(groupId).stream()
                    .map(t -> t.type() == CashTransactionType.DEPOSIT ? t.amount() : t.amount().negate())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            groupCash.put(groupId, flow.add(transfers));
            for (Position pos : positions) {
                holdingQty.put(groupId + "|" + pos.stockCode(), pos.quantity());
            }
        }
        return new ImportSimulator.StartState(groupCash, holdingQty);
    }

    /**
     * 执行落库：按模拟器同序（groupId→date→rowNumber）逐行演化。对每个 (groupId, stockCode)
     * 维护工作持仓（首次从库内取，含已清仓行——写路径可寻址；不存在则新建——名称取 CSV
     * 名称列，空则回填代码），后续行在已保存实例上续演，保证多行同股时 Trade/Dividend
     * 挂同一 positionId。
     */
    private void execute(Portfolio portfolio, List<ImportRow> rows) {
        Map<String, Position> working = new HashMap<>();
        for (ImportRow r : rows) {
            switch (r.type()) {
                case BUY, SELL -> {
                    Position current = workingPosition(portfolio, r, working);
                    Position updated = r.type() == ImportRowType.BUY
                            ? current.applyBuy(r.price(), r.quantity(), r.fee())
                            : current.applySell(r.price(), r.quantity(), r.fee());
                    Position saved = repository.savePosition(updated);
                    working.put(positionKey(r), saved);
                    repository.saveTrade(new Trade(null, saved.id(),
                            r.type() == ImportRowType.BUY ? TradeType.BUY : TradeType.SELL,
                            r.date(), r.price(), r.quantity(), r.fee(), Instant.now()));
                }
                case CASH_DIVIDEND, STOCK_DIVIDEND -> {
                    Position current = workingPosition(portfolio, r, working);
                    // 现金分红总额 = 每股现金 × 当时数量（与模拟器同口径；执行序=模拟序，数量一致）
                    Position updated = r.type() == ImportRowType.CASH_DIVIDEND
                            ? current.applyCashDividend(r.price().multiply(current.quantity()))
                            : current.applyStockDividend(r.price());
                    Position saved = repository.savePosition(updated);
                    working.put(positionKey(r), saved);
                    repository.saveDividend(r.type() == ImportRowType.CASH_DIVIDEND
                            ? new Dividend(null, saved.id(), DividendType.CASH,
                                    r.date(), r.price(), null, Instant.now())
                            : new Dividend(null, saved.id(), DividendType.STOCK,
                                    r.date(), null, r.price(), Instant.now()));
                }
                case DEPOSIT, WITHDRAW -> repository.saveCashTransaction(new CashTransaction(
                        null, r.groupId(),
                        r.type() == ImportRowType.DEPOSIT ? CashTransactionType.DEPOSIT : CashTransactionType.WITHDRAW,
                        r.amount(), r.date(), r.note(), Instant.now()));
            }
        }
    }

    /** (groupId, stockCode) 工作持仓：命中工作区直接用，否则查库（含已清仓行），再否则新建。 */
    private Position workingPosition(Portfolio portfolio, ImportRow r, Map<String, Position> working) {
        Position cached = working.get(positionKey(r));
        if (cached != null) {
            return cached;
        }
        // stock_name 非空：CSV 名称列空（ImportRow.stockName=null）时以代码回填
        String stockName = r.stockName() != null ? r.stockName() : r.stockCode();
        return repository.findPositionByPortfolioIdAndGroupIdAndStockCode(portfolio.id(), r.groupId(), r.stockCode())
                .orElseGet(() -> Position.create(portfolio.id(), r.groupId(), r.stockCode(), stockName,
                        Instant.now()));
    }

    /** 排序键与 ImportSimulator 完全一致（groupId→date→rowNumber），保证模拟与执行同构。 */
    private static List<ImportRow> sorted(List<ImportRow> rows) {
        return rows.stream()
                .sorted(Comparator.comparing(ImportRow::groupId)
                        .thenComparing(ImportRow::date)
                        .thenComparing(ImportRow::rowNumber))
                .toList();
    }

    private static String positionKey(ImportRow r) {
        return r.groupId() + "|" + r.stockCode();
    }
}
