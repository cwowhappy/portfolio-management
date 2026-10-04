package com.portfolio.invest.application.conversation;

import java.time.Instant;
import java.util.List;

/** 会话消息读视图（B6）：消息体连同乐观校验基准 updatedAt 一并返回（ISO 字符串序列化）。 */
public record ConversationMessagesView(Instant updatedAt, List<ChatMessageWire> messages) {}
