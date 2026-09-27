package com.portfolio.invest.agent;

/** 飞书对话的 agent 调用接缝（隔离 HarnessAgent 便于桥接单测）。异常上抛由调用方兜底。 */
public interface FeishuAgentInvoker {

    String ask(Long userId, String sessionId, String text);
}
