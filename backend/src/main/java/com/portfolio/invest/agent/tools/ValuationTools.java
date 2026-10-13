package com.portfolio.invest.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.agent.chart.ChartSpecs;
import com.portfolio.invest.application.valuation.ValuationApplicationService;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 估值域子组件（2026-10-12 自 {@code InvestTools} 按域拆分，纯搬移）：get_valuation 方法体。
 * 门面保留 @Tool 注解与方法签名（装配面字节不变），此处仅承载实现。
 */
public class ValuationTools {

    private static final Logger log = LoggerFactory.getLogger(ValuationTools.class);

    private final ValuationApplicationService valuationApplicationService;
    private final ObjectMapper mapper;

    public ValuationTools(ValuationApplicationService valuationApplicationService, ObjectMapper mapper) {
        this.valuationApplicationService = valuationApplicationService;
        this.mapper = mapper;
    }

    public ToolResultBlock getValuation(ToolEmitter emitter) {
        return ToolResultBlocks.runBlock(log, mapper, () -> {
            // ⚠ 图数据来自 history()（overview() 视图无 peHistory/pbHistory 字段——一手核实）
            var history = valuationApplicationService.history();
            if (history.snapshots().isEmpty()) {
                // emit 前守卫：冷库期（ValuationApplicationService 空表）合法产出空——不 emit 空走势 spec
                return ToolResultBlock.text(ChartSpecs.valuationEmptySummary());
            }
            var overview = valuationApplicationService.overview();
            // ① SSE 全量（只 emit 一次）：PE/PB 中位数历史 line（含 null 缺口）
            emitter.emit(ToolResultBlock.builder()
                    .output(TextBlock.builder().text(mapper.writeValueAsString(
                            ChartSpecs.valuationLine(history.snapshots()))).build())
                    .build());
            // ② LLM 摘要：overview() 标量（分位/ERP/温度计）
            return ToolResultBlock.text(ChartSpecs.valuationSummary(overview, history.snapshots().size()));
        });
    }
}
