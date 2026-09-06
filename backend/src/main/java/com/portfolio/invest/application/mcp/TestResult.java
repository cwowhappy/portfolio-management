package com.portfolio.invest.application.mcp;

import java.util.List;

/** 连接测试结果：成功携带工具清单与耗时，失败携带错误信息。 */
public record TestResult(boolean success, List<McpToolDescriptor> tools, long latencyMs, String errorMessage) {
    public static TestResult ok(List<McpToolDescriptor> tools, long latencyMs) {
        return new TestResult(true, tools, latencyMs, null);
    }

    public static TestResult fail(String errorMessage) {
        return new TestResult(false, List.of(), 0, errorMessage);
    }
}
