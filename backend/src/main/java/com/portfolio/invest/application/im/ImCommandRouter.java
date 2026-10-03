package com.portfolio.invest.application.im;

import java.util.Optional;

/**
 * 入站命令前置路由端口（P4 Task 5，D8 绑定闭环）：消息进入对话桥
 * （{@link ImMessageListener}）之前先尝试命令拦截。返回非 empty 即已处理——
 * 返回值为面向用户的回复文本，由 ws 客户端直接 reply（不进 LLM 对话）；
 * empty = 非本路由命令，照旧透传对话桥。
 *
 * <p>消费方为 infrastructure（FeishuWsClient 在转投 listener 前调用）；实现在
 * application/intelligence（BindingCommandHandler——6 位绑定码命令）。
 */
public interface ImCommandRouter {

    /**
     * 尝试拦截处理入站消息。
     *
     * @return 已处理时的回复文本；empty = 未命中，透传对话桥
     */
    Optional<String> tryRoute(ImInboundMessage message);
}
