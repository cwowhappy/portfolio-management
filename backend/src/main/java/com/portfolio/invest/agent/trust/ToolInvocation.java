package com.portfolio.invest.agent.trust;

import java.util.List;
import java.util.Map;

/**
 * 一次工具调用的真值记录（RecordingAgentToolDecorator 产物，设计规格 §三/§4.1，B3 消费）。
 * 真值池条目形态：参数与结果同点捕获，asOf 取返回 DTO 时点（内置）或调用时刻（MCP）。
 *
 * <p><strong>mcp 字面标志（终审 triage B3-①）：</strong>池分桶（比对池 vs MCP 精确配源池）由本
 * 标志承载，不再由 {@code asOfKind==CALL} 编码——MCP 工具恒 CALL（决策 #5）但内置无时点工具
 * 同样落 CALL 兜底，两者语义不同桶：MCP 真值不入比对池（sourced），内置无时点真值可参与比对
 * （verified）。装饰器按被包装工具的 MCP 语义填充（UserToolkitFactory 装配处已知）。
 */
public record ToolInvocation(
        String toolName,
        Map<String, Object> args,
        String resultText,
        List<String> emittedSpecs,
        String asOf,
        AsOfKind asOfKind,
        boolean failed,
        boolean mcp) {

    /** 数据时点语义（决策 #17）：data=源站数据时刻 / generated=本机生成时刻 / call=调用时刻。 */
    public enum AsOfKind {
        DATA("data"), GENERATED("generated"), CALL("call");

        private final String wireName;

        AsOfKind(String wireName) {
            this.wireName = wireName;
        }

        /** payload v1 anchor.asOfKind 序列化名（设计规格 §2.1，B5 消费）。 */
        public String wireName() {
            return wireName;
        }

        /** 线名反解（历史池 metadata 读回，§2.2；未知值返回 null）。 */
        public static AsOfKind fromWireName(String wireName) {
            for (AsOfKind kind : values()) {
                if (kind.wireName.equals(wireName)) {
                    return kind;
                }
            }
            return null;
        }
    }
}
