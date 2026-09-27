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

/**
 * F11 数据底座：stock_financial 库表（tushare 季频，collector 落库）与东财 live 财报
 * <b>两源并列透传</b>（records 与 live 各自原样呈现，不做值合并）与杜邦推导；只取数不解读（解读归 LLM）。
 *
 * <p>口径注记（P1-11）：
 * <ul>
 *   <li><b>杜邦是唯一混合口径点</b>：{@code netMargin} 取东财 live 序列首期（最新报告期），
 *       而 {@code roa}/{@code debtToAssets}/{@code roe} 取库表 latest（tushare 采集的最新期）——
 *       当东财已披露新季报而 collector 采集未跑时，两者<b>可能来自不同报告期</b>，
 *       杜邦分解的分子分母存在口径错位窗口（通常一个采集日内自愈）。</li>
 *   <li><b>live 降级</b>：东财不可用时仅返回库表口径（netMargin 为 null，杜邦部分缺项），不失败。</li>
 * </ul>
 */
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
