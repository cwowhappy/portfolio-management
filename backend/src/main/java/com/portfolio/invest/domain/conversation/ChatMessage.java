package com.portfolio.invest.domain.conversation;

/** 会话内的一条消息（精简历史：只存 user/assistant 纯文本；payload 预留工具调用扩展）。 */
public final class ChatMessage {

    private final Long dbId;
    private final String id;        // AG-UI 消息 id（wire）
    private final String role;
    private final String content;
    private final String payload;   // JSONB，可空
    private final long createdAtMs;

    private ChatMessage(Long dbId, String id, String role, String content, String payload, long createdAtMs) {
        this.dbId = dbId;
        this.id = id;
        this.role = role;
        this.content = content;
        this.payload = payload;
        this.createdAtMs = createdAtMs;
    }

    public static ChatMessage create(Long dbId, String id, String role, String content, String payload, long createdAtMs) {
        return new ChatMessage(dbId, id, role, content, payload, createdAtMs);
    }

    public Long dbId() { return dbId; }
    public String id() { return id; }
    public String role() { return role; }
    public String content() { return content; }
    public String payload() { return payload; }
    public long createdAtMs() { return createdAtMs; }
}
