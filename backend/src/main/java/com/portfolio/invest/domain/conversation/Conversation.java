package com.portfolio.invest.domain.conversation;

import java.time.Instant;

/** 会话聚合根：id = AG-UI threadId，归属 userId。纯 POJO。 */
public final class Conversation {

    public static final String DEFAULT_TITLE = "新会话";
    private static final int TITLE_MAX = 24;

    private final String id;
    private final Long userId;
    private final String title;
    private final Instant createdAt;
    private final Instant updatedAt;

    private Conversation(String id, Long userId, String title, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.userId = userId;
        this.title = title;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Conversation create(String id, Long userId, Instant now) {
        return new Conversation(id, userId, DEFAULT_TITLE, now, now);
    }

    /** 标题仍为默认值且存在首条用户消息时，取前 24 字设为标题（幂等）。 */
    public Conversation renameIfDefault(String firstUserContent) {
        if (!DEFAULT_TITLE.equals(title) || firstUserContent == null) {
            return this;
        }
        String t = firstUserContent.trim();
        String next = t.length() > TITLE_MAX ? t.substring(0, TITLE_MAX) : t;
        return next.isEmpty() ? this : new Conversation(id, userId, next, createdAt, updatedAt);
    }

    public Conversation touch(Instant now) {
        return new Conversation(id, userId, title, createdAt, now);
    }

    public String id() { return id; }
    public Long userId() { return userId; }
    public String title() { return title; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
}
