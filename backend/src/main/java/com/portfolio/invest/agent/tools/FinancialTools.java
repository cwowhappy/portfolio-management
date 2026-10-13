package com.portfolio.invest.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.agent.chart.ChartSpecs;
import com.portfolio.invest.application.market.FinancialAnalysisView;
import com.portfolio.invest.application.market.FinancialQueryService;
import com.portfolio.invest.application.market.MarketDataService;
import com.portfolio.invest.domain.market.Financials;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 财务域子组件（2026-10-12 自 {@code InvestTools} 按域拆分，纯搬移）：get_financials /
 * analyze_financials 两个工具的方法体。门面保留 @Tool 注解与方法签名（装配面字节不变），
 * 此处仅承载实现。
 */
public class FinancialTools {

    private static final Logger log = LoggerFactory.getLogger(FinancialTools.class);

    private final MarketDataService market;
    private final FinancialQueryService financialQuery;
    private final ObjectMapper mapper;

    public FinancialTools(MarketDataService market, FinancialQueryService financialQuery, ObjectMapper mapper) {
        this.market = market;
        this.financialQuery = financialQuery;
        this.mapper = mapper;
    }

    public ToolResultBlock getFinancials(String code, ToolEmitter emitter) {
        return ToolResultBlocks.runBlock(log, mapper, () -> {
            Financials f = market.financials(code);
            if (f.indicators().isEmpty()) {
                // emit 前守卫：空指标不 emit（摘要 get(0) 也会 IOOBE），LLM 收安全摘要
                return ToolResultBlock.text(ChartSpecs.financialsEmptySummary(f));
            }
            // ① SSE 全量（只 emit 一次）：table spec（P4 前端接 DataTable 渲染）
            emitter.emit(ToolResultBlock.builder()
                    .output(TextBlock.builder().text(mapper.writeValueAsString(
                            ChartSpecs.financialsTable(f))).build())
                    .build());
            // ② LLM 摘要：PE/PB 与最新报告期
            return ToolResultBlock.text(ChartSpecs.financialsSummary(f));
        });
    }

    public ToolResultBlock analyzeFinancials(String code, ToolEmitter emitter) {
        return ToolResultBlocks.runBlock(log, mapper, () -> {
            FinancialAnalysisView view = financialQuery.analyze(code, 12);
            if (view.records().isEmpty()) {
                // 库表空：降级——live 财务摘要 + 趋势积累提示，不 emit 空表。
                // live 指标为空（东财返回 data:[] 的新股）时 financialsSummary 会 get(0) IOOBE，须防护
                String live = (view.live() == null || view.live().indicators().isEmpty())
                        ? "" : ChartSpecs.financialsSummary(view.live()) + " ";
                return ToolResultBlock.text(live + "库表趋势数据积累中（新股或未采集），暂无法做杜邦拆解。");
            }
            String name = view.live() == null ? code : view.live().name();
            emitter.emit(ToolResultBlock.builder()
                    .output(TextBlock.builder().text(mapper.writeValueAsString(
                            ChartSpecs.financialTrendTable(code, name, view.records()))).build())
                    .build());
            return ToolResultBlock.text(
                    ChartSpecs.financialTrendSummary(name, view.records().size(), view.duPont()));
        });
    }
}
