package com.portfolio.invest.application.conversation;

import com.portfolio.invest.domain.conversation.ChatMessage;

/** 消息线上格式：与前端 ChatMessage 对齐（不含 payload，payload 预留）。 */
public record ChatMessageWire(String id, String role, String content, long createdAt) {
    public ChatMessage toDomain() {
        return ChatMessage.create(null, id, role, content, null, createdAt);
    }
    public static ChatMessageWire from(ChatMessage m) {
        return new ChatMessageWire(m.id(), m.role(), m.content(), m.createdAtMs());
    }
}
