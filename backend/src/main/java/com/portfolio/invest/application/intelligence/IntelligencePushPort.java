package com.portfolio.invest.application.intelligence;

import java.util.List;

/**
 * 情报推送端口（D10/D11，infrastructure.im 实现）。尽力而为：任何失败返回 false
 * 内部已记日志，绝不抛出——推送失败不拖垮简报主流程。
 *
 * <p>P1 群推版（sendToGroup，配置群 chatId）；sendToUser 为 P4 订阅单发预留并已
 * 落地实现（receive_id_type=open_id），P4 接订阅/绑定后直接消费。
 */
public interface IntelligencePushPort {

    /** 推卡片到配置群（invest.im.chatId）。 */
    boolean sendToGroup(String title, String template, List<String> bodyLines);

    /** 推卡片到指定用户（open_id 单发，不要求群 chatId 配置）。 */
    boolean sendToUser(String openId, String title, String template, List<String> bodyLines);
}
