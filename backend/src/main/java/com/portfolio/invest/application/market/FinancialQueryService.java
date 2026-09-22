package com.portfolio.invest.application.market;

import com.portfolio.invest.domain.market.DuPontAnalysis;
import com.portfolio.invest.domain.market.FinancialRecord;
import com.portfolio.invest.domain.market.FinancialRecordRepository;
import com.portfolio.invest.domain.market.Financials;
import com.portfolio.invest.domain.market.MarketDataException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** F11 数据底座：stock_financial 库表 + 东财 live 两源合并与杜邦推导；只取数不解读（解读归 LLM）。 */
@Service
public class FinancialQueryService {

    private static final Logger log = LoggerFactory.getLogger(FinancialQueryService.class);

    private final FinancialRecordRepository repository;
    private final MarketDataService market;

    public FinancialQueryService(FinancialRecordRepository repository, MarketDataService market) {
        this.repository = repository;
        this.market = market;
    }

    public FinancialAnalysisView analyze(String code, int quarters) {
        List<FinancialRecord> records = repository.findByCodeLatest(code, quarters);
        Financials live = null;
        try {
            live = market.financials(code);
        } catch (MarketDataException e) {
            log.warn("财报工具 live 源不可用，仅库表口径: code={}, msg={}", e.getCode(), e.getMessage());
        }
        if (records.isEmpty()) {
            return new FinancialAnalysisView(records, live, null);
        }
        FinancialRecord latest = records.get(0);
        Double netMargin = null;
        String liveDate = null;
        if (live != null && !live.indicators().isEmpty()) {
            var i = live.indicators().get(0); // 序列降序，首期最新（与 OrchestratingMarketDataService 同一不变式）
            if (i.netProfit() != null && i.totalRevenue() != null && i.totalRevenue() != 0.0) {
                netMargin = i.netProfit() / i.totalRevenue();
            }
            liveDate = i.reportDate();
        }
        return new FinancialAnalysisView(records, live,
                DuPontAnalysis.of(netMargin, latest.roa(), latest.debtToAssets(), latest.roe(),
                        latest.reportDate().toString(), liveDate));
    }
}
