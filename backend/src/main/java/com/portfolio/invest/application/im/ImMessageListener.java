package com.portfolio.invest.application.im;

/** 入站消息消费端口（agent 包桥接实现；infrastructure ws 客户端调用）。 */
public interface ImMessageListener {

    void onMessage(ImInboundMessage message);
}
