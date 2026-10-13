package com.portfolio.invest.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.agent.chart.ChartSpecs;
import com.portfolio.invest.application.screening.ScreeningApplicationService;
import com.portfolio.invest.domain.screening.ScreeningCriteria;
import com.portfolio.invest.domain.screening.SortDirection;
import com.portfolio.invest.domain.screening.StockScreeningResult;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolEmitter;
import java.math.BigDecimal;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 筛选域子组件（2026-10-12 自 {@code InvestTools} 按域拆分，纯搬移）：screen_stocks 方法体。
 * 门面保留 @Tool 注解与方法签名（装配面字节不变），此处仅承载实现。
 */
public class ScreeningTools {

    private static final Logger log = LoggerFactory.getLogger(ScreeningTools.class);

    private final ScreeningApplicationService screening;
    private final ObjectMapper mapper;

    public ScreeningTools(ScreeningApplicationService screening, ObjectMapper mapper) {
        this.screening = screening;
        this.mapper = mapper;
    }

    public ToolResultBlock screenStocks(
            Double peTtmMax, Double pbMax, Double dividendYieldMin,
            Double roeMin, Double roaMin, Double grossMarginMin,
            Double debtToAssetsMax, Double currentRatioMin, Double revenueYoyMin,
            Double netprofitYoyMin, Double totalMvMin, Double turnoverRateMin,
            String industryCode, String indexCode,
            String sortBy, String sortDirection, Integer limit,
            ToolEmitter emitter) {
        return ToolResultBlocks.runBlock(log, mapper, () -> {
            String sort = sortBy == null || sortBy.isBlank() ? "total_mv" : sortBy;
            if (!ScreeningCriteria.SORTABLE_FIELDS.contains(sort)) {
                return ToolResultBlock.text(ToolResultBlocks.toError(mapper, "不支持的排序字段: " + sort,
                        "可用: " + ScreeningCriteria.SORTABLE_FIELDS));
            }
            // 指数入参防御（e2e 实录 LLM 幻觉值如 "."）：白名单外降级全市场，避免 domain 构造器
            // 抛 INVALID_INDEX 致整查询失败——indexCode 是范围收窄而非核心条件，真实筛选条件仍生效
            String idx = indexCode == null ? null : indexCode.trim();
            if (idx != null && !ScreeningCriteria.INDEX_WHITELIST.contains(idx)) {
                idx = null; // 空串/幻觉值统一降级全市场（e2e 实录 LLM 传过 "." 与 ""）
            }
            ScreeningCriteria criteria = new ScreeningCriteria(
                    bd(peTtmMax), bd(pbMax), bd(dividendYieldMin), bd(roeMin), bd(roaMin),
                    bd(grossMarginMin), bd(debtToAssetsMax), bd(currentRatioMin), bd(revenueYoyMin),
                    bd(netprofitYoyMin), bd(totalMvMin), bd(turnoverRateMin),
                    industryCode == null || industryCode.isBlank() ? null : industryCode,
                    idx,
                    sort,
                    "asc".equalsIgnoreCase(sortDirection) ? SortDirection.ASC : SortDirection.DESC,
                    Math.max(1, Math.min(limit == null ? 20 : limit, 50)));
            if (!criteria.hasAnyCondition()) {
                return ToolResultBlock.text(ToolResultBlocks.toError(mapper, "至少需要一个筛选条件",
                        "请给出 PE/ROE/市值等至少一个条件"));
            }
            List<StockScreeningResult> results = screening.screen(criteria);
            if (results.isEmpty()) {
                return ToolResultBlock.text(ChartSpecs.screeningSummary(results)); // 空结果不 emit 空表
            }
            emitter.emit(ToolResultBlock.builder()
                    .output(TextBlock.builder().text(mapper.writeValueAsString(
                            ChartSpecs.screeningTable(results))).build())
                    .build());
            return ToolResultBlock.text(ChartSpecs.screeningSummary(results));
        });
    }

    /** Double → BigDecimal（null 透传）。 */
    private static BigDecimal bd(Double v) {
        return v == null ? null : BigDecimal.valueOf(v);
    }
}
