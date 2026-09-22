package com.portfolio.invest.domain.wiki;

import java.time.Instant;

/** 知识库条目聚合根：不可变，update 返回新实例。三类条目统一建模，类型特有字段（category/industryCode）可空。 */
public final class WikiEntry {

    private final Long id;
    private final Long userId;
    private final WikiEntryType type;
    private final String title;
    private final String content;
    private final String category;
    private final String industryCode;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final Long version;

    private WikiEntry(Long id, Long userId, WikiEntryType type, String title, String content,
                      String category, String industryCode, Instant createdAt, Instant updatedAt, Long version) {
        this.id = id;
        this.userId = userId;
        this.type = type;
        this.title = title;
        this.content = content;
        this.category = category;
        this.industryCode = industryCode;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    public static WikiEntry create(Long userId, WikiEntryType type, String title, String content,
                                   String category, String industryCode, Instant now) {
        validate(title, content);
        return new WikiEntry(null, userId, type, title, content, category, industryCode, now, now, null);
    }

    public static WikiEntry reconstitute(Long id, Long userId, WikiEntryType type, String title, String content,
                                         String category, String industryCode,
                                         Instant createdAt, Instant updatedAt, Long version) {
        return new WikiEntry(id, userId, type, title, content, category, industryCode, createdAt, updatedAt, version);
    }

    /** 更新可变字段（type/userId 不可变），返回新实例。 */
    public WikiEntry update(String title, String content, String category, String industryCode) {
        validate(title, content);
        return new WikiEntry(id, userId, type, title, content, category, industryCode,
                createdAt, Instant.now(), version);
    }

    /** 类型特有列从轻校验（校验从轻，避免堵死用户路径——设计规格 §3.1）。 */
    private static void validate(String title, String content) {
        if (title == null || title.isBlank()) {
            throw new WikiException(WikiErrorCode.INVALID_INPUT, "标题不能为空");
        }
        if (title.length() > 200) {
            throw new WikiException(WikiErrorCode.INVALID_INPUT, "标题长度不能超过200字");
        }
        if (content == null || content.isBlank()) {
            throw new WikiException(WikiErrorCode.INVALID_INPUT, "内容不能为空");
        }
    }

    public Long id() { return id; }
    public Long userId() { return userId; }
    public WikiEntryType type() { return type; }
    public String title() { return title; }
    public String content() { return content; }
    public String category() { return category; }
    public String industryCode() { return industryCode; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public Long version() { return version; }
}
