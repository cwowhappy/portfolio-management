package com.portfolio.invest.application.im;

/** 飞书入站消息（ws 长连接解析产物）。text 可空：非文本类型或 content 解析失败。 */
public record ImInboundMessage(String chatId, String messageId, String openId, String chatType, String msgType,
                               String text) {}
