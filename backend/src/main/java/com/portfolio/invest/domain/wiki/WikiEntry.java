package com.portfolio.invest.domain.wiki;

import java.time.Instant;

/**
 * 知识库条目聚合根：不可变，update 返回新实例。三类条目统一建模，类型特有字段（category/industryCode）可空。
 * projectId 为研究项目软引用（V2 D12：无 FK 不级联，F08 项目反查用；type/userId/projectId 均不可变）。
 */
public final class WikiEntry {

    private final Long id;
    private final Long userId;
    private final WikiEntryType type;
    private final String title;
    private final String content;
    private final String category;
    private final String industryCode;
    private final Long projectId;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final Long version;

    private WikiEntry(Long id, Long userId, WikiEntryType type, String title, String content,
                      String category, String industryCode, Long projectId,
                      Instant createdAt, Instant updatedAt, Long version) {
        this.id = id;
        this.userId = userId;
        this.type = type;
        this.title = title;
        this.content = content;
        this.category = category;
        this.industryCode = industryCode;
        this.projectId = projectId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    public static WikiEntry create(Long userId, WikiEntryType type, String title, String content,
                                   String category, String industryCode, Instant now) {
        return create(userId, type, title, content, category, industryCode, now, null);
    }

    /** 带研究项目软引用的创建入口（复盘回流等场景，照 JournalEntry 增量重载先例）。 */
    public static WikiEntry create(Long userId, WikiEntryType type, String title, String content,
                                   String category, String industryCode, Instant now, Long projectId) {
        validate(title, content);
        return new WikiEntry(null, userId, type, title, content, category, industryCode, projectId, now, now, null);
    }

    public static WikiEntry reconstitute(Long id, Long userId, WikiEntryType type, String title, String content,
                                         String category, String industryCode,
                                         Instant createdAt, Instant updatedAt, Long version) {
        return reconstitute(id, userId, type, title, content, category, industryCode,
                createdAt, updatedAt, version, null);
    }

    public static WikiEntry reconstitute(Long id, Long userId, WikiEntryType type, String title, String content,
                                         String category, String industryCode,
                                         Instant createdAt, Instant updatedAt, Long version, Long projectId) {
        return new WikiEntry(id, userId, type, title, content, category, industryCode, projectId,
                createdAt, updatedAt, version);
    }

    /** 更新可变字段（type/userId/projectId 不可变），返回新实例。 */
    public WikiEntry update(String title, String content, String category, String industryCode) {
        validate(title, content);
        return new WikiEntry(id, userId, type, title, content, category, industryCode, projectId,
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
    public Long projectId() { return projectId; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public Long version() { return version; }
}
