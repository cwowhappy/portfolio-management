package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.valuation.IndustryValuation;
import com.portfolio.invest.domain.valuation.IndexValuation;
import com.portfolio.invest.domain.valuation.ShenwanIndustryMapping;
import com.portfolio.invest.domain.valuation.TreasuryYield;
import com.portfolio.invest.domain.valuation.ValuationRepository;
import com.portfolio.invest.domain.valuation.ValuationSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

@Repository
public class ValuationRepositoryImpl implements ValuationRepository {

    /** ERP 读侧仅用 10 年期国债收益率。 */
    private static final String TERM_10Y = "10Y";

    private final ValuationSnapshotJpaRepository snapshotRepo;
    private final IndustryValuationJpaRepository industryRepo;
    private final TreasuryYieldJpaRepository treasuryRepo;
    private final IndexValuationJpaRepository indexRepo;
    private final ShenwanIndustryMappingJpaRepository mappingRepo;
    private final JdbcTemplate jdbc;

    public ValuationRepositoryImpl(ValuationSnapshotJpaRepository snapshotRepo,
                                   IndustryValuationJpaRepository industryRepo,
                                   TreasuryYieldJpaRepository treasuryRepo,
                                   IndexValuationJpaRepository indexRepo,
                                   ShenwanIndustryMappingJpaRepository mappingRepo,
                                   JdbcTemplate jdbc) {
        this.snapshotRepo = snapshotRepo;
        this.industryRepo = industryRepo;
        this.treasuryRepo = treasuryRepo;
        this.indexRepo = indexRepo;
        this.mappingRepo = mappingRepo;
        this.jdbc = jdbc;
    }

    @Override
    public ValuationSnapshot findLatestSnapshot() {
        var e = snapshotRepo.findTopByOrderByTradingDayDesc();
        return e == null ? null : e.toDomain();
    }

    @Override
    public Set<String> findLatestSnapshotStockCodes() {
        // 与 ScreeningRepositoryImpl 同口径：最新交易日 = max(trading_day) 子查询
        return Set.copyOf(jdbc.queryForList(
                "SELECT DISTINCT stock_code FROM stock_valuation_daily "
                        + "WHERE trading_day = (SELECT max(trading_day) FROM stock_valuation_daily)",
                String.class));
    }

    @Override
    public List<ValuationSnapshot> findAllSnapshots() {
        return snapshotRepo.findAllByOrderByTradingDayAsc().stream().map(ValuationSnapshotJpaEntity::toDomain).toList();
    }

    @Override
    public List<IndustryValuation> findIndustryValuationsByDay(LocalDate tradingDay) {
        return industryRepo.findByTradingDay(tradingDay).stream().map(IndustryValuationJpaEntity::toDomain).toList();
    }

    @Override
    public List<TreasuryYield> findAllTreasuryYields() {
        return treasuryRepo.findAllByTermOrderByTradingDayAsc(TERM_10Y).stream().map(TreasuryYieldJpaEntity::toDomain).toList();
    }

    @Override
    public List<IndexValuation> findIndexValuations(String indexCode) {
        return indexRepo.findByIndexCodeOrderByTradingDayAsc(indexCode).stream().map(IndexValuationJpaEntity::toDomain).toList();
    }

    @Override
    public List<ShenwanIndustryMapping> findAllIndustryMappings() {
        return mappingRepo.findAll().stream().map(ShenwanIndustryMappingJpaEntity::toDomain).toList();
    }
}
