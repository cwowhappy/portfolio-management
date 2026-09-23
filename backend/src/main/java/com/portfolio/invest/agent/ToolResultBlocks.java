package com.portfolio.invest.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.domain.market.MarketDataException;
import io.agentscope.core.message.ToolResultBlock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;

/**
 * Agent 工具错误兜底共享（包私有）：InvestTools 与 UserInvestTools 同包共用，
 * 防「runBlock 复制体各自演化」——{\@code {"error","hint"}} 形状是前端 ChartCard 嗅探降级的跨栈契约。
 */
final class ToolResultBlocks {

    private ToolResultBlocks() {}

    @FunctionalInterface
    interface BlockSupplier {
        ToolResultBlock get() throws Exception;
    }

    /** 失败不 emit，返回错误 JSON 文本（前端 ChartCard 嗅探降级）；MarketDataException 给数据源专属提示。 */
    static ToolResultBlock runBlock(Logger log, ObjectMapper mapper, BlockSupplier supplier) {
        try {
            return supplier.get();
        } catch (MarketDataException e) {
            log.warn("工具数据获取失败: code={}, msg={}", e.getCode(), e.getMessage());
            return ToolResultBlock.text(toError(mapper, e.getMessage(), "数据源暂不可用，请稍后重试或换个问法"));
        } catch (Exception e) {
            log.error("工具执行异常", e);
            return ToolResultBlock.text(toError(mapper, "工具执行失败", "请稍后重试"));
        }
    }

    /** 用 ObjectMapper 序列化错误，避免手工拼 JSON 导致非法输出。 */
    static String toError(ObjectMapper mapper, String message, String hint) {
        try {
            Map<String, String> body = new LinkedHashMap<>();
            body.put("error", message);
            body.put("hint", hint);
            return mapper.writeValueAsString(body);
        } catch (Exception e) {
            return "{\"error\":\"工具执行失败\",\"hint\":\"请稍后重试\"}";
        }
    }
}
