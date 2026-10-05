package com.portfolio.invest.agent.trust;

import java.util.List;
import java.util.Map;

/**
 * 一次工具调用的真值记录（RecordingAgentToolDecorator 产物，设计规格 §三/§4.1，B3 消费）。
 * 真值池条目形态：参数与结果同点捕获，asOf 取返回 DTO 时点（内置）或调用时刻（MCP）。
 */
public record ToolInvocation(
        String toolName,
        Map<String, Object> args,
        String resultText,
        List<String> emittedSpecs,
        String asOf,
        AsOfKind asOfKind,
        boolean failed) {

    /** 数据时点语义（决策 #17）：data=源站数据时刻 / generated=本机生成时刻 / call=调用时刻。 */
    public enum AsOfKind { DATA, GENERATED, CALL }
}
