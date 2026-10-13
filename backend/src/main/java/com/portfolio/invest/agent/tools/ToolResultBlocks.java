package com.portfolio.invest.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.domain.market.MarketDataException;
import io.agentscope.core.message.ToolResultBlock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;

/**
 * Agent 工具错误兜底共享：InvestTools（门面）/UserInvestTools 与 agent.tools 域子组件
 * 共用，防「runBlock 复制体各自演化」——{\@code {"error","hint"}} 形状是前端 ChartCard 嗅探降级的跨栈契约。
 * 2026-10-12 InvestTools 按域拆分子组件时由包私有放宽为 public（仅供 agent 能力域内部使用）。
 */
public final class ToolResultBlocks {

    private ToolResultBlocks() {}

    @FunctionalInterface
    public interface BlockSupplier {
        ToolResultBlock get() throws Exception;
    }

    @FunctionalInterface
    public interface StringSupplier {
        String get() throws Exception;
    }

    /** 失败不 emit，返回错误 JSON 文本（前端 ChartCard 嗅探降级）；MarketDataException 给数据源专属提示。 */
    public static ToolResultBlock runBlock(Logger log, ObjectMapper mapper, BlockSupplier supplier) {
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

    /** String 返回版兜底（InvestTools.run 同款形状，UserInvestTools 的 JSON 工具共用，防复制体漂移）。 */
    public static String runString(Logger log, ObjectMapper mapper, StringSupplier supplier) {
        try {
            return supplier.get();
        } catch (MarketDataException e) {
            log.warn("工具数据获取失败: code={}, msg={}", e.getCode(), e.getMessage());
            return toError(mapper, e.getMessage(), "数据源暂不可用，请稍后重试或换个问法");
        } catch (Exception e) {
            log.error("工具执行异常", e);
            return toError(mapper, "工具执行失败", "请稍后重试");
        }
    }

    /** 用 ObjectMapper 序列化错误，避免手工拼 JSON 导致非法输出。 */
    public static String toError(ObjectMapper mapper, String message, String hint) {
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
