package com.portfolio.invest.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.agent.chart.ChartSpecs;
import com.portfolio.invest.application.industry.IndustryApplicationService;
import com.portfolio.invest.application.industry.IndustryBoardView;
import com.portfolio.invest.domain.industry.IndustryStock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolEmitter;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 行业域子组件（2026-10-12 自 {@code InvestTools} 按域拆分，纯搬移）：analyze_industry 方法体。
 * 门面保留 @Tool 注解与方法签名（装配面字节不变），此处仅承载实现。
 */
public class IndustryTools {

    private static final Logger log = LoggerFactory.getLogger(IndustryTools.class);

    private final IndustryApplicationService industry;
    private final ObjectMapper mapper;

    public IndustryTools(IndustryApplicationService industry, ObjectMapper mapper) {
        this.industry = industry;
        this.mapper = mapper;
    }

    public ToolResultBlock analyzeIndustry(String industryCode, ToolEmitter emitter) {
        return ToolResultBlocks.runBlock(log, mapper, () -> {
            List<IndustryBoardView> board = industry.board();
            if (board.isEmpty()) {
                return ToolResultBlock.text(ChartSpecs.industryBoardSummary(board)); // 冷库安全摘要
            }
            if (industryCode == null || industryCode.isBlank()) {
                emitter.emit(ToolResultBlock.builder()
                        .output(TextBlock.builder().text(mapper.writeValueAsString(
                                ChartSpecs.industryBoardTable(board))).build())
                        .build());
                return ToolResultBlock.text(ChartSpecs.industryBoardSummary(board));
            }
            var row = board.stream()
                    .filter(b -> industryCode.equals(b.industryCode())).findFirst().orElse(null);
            if (row == null) {
                // 板面无该行即码未对齐——先返回提示，不调 stocks（service 对不存在码抛 INDUSTRY_NOT_FOUND，
                // 会落进通用错误兜底丢失友好提示）
                return ToolResultBlock.text(
                        "行业码 %s 无板面/成分股数据（请先经板面对齐行业码）。".formatted(industryCode));
            }
            List<IndustryStock> stocks = industry.stocks(industryCode, "total_mv", "desc", 15);
            if (stocks.isEmpty()) {
                return ToolResultBlock.text(ChartSpecs.industryStocksSummary(row, stocks));
            }
            emitter.emit(ToolResultBlock.builder()
                    .output(TextBlock.builder().text(mapper.writeValueAsString(
                            ChartSpecs.industryStocksTable(row.industryName(), stocks))).build())
                    .build());
            return ToolResultBlock.text(ChartSpecs.industryStocksSummary(row, stocks));
        });
    }
}
