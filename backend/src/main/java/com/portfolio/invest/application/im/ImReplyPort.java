package com.portfolio.invest.application.im;

/** 对话回复端口（infrastructure/im 实现）：reply 到指定 message_id。尽力而为，失败返回 false。 */
public interface ImReplyPort {

    boolean reply(String messageId, String text);
}
