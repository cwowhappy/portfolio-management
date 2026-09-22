package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.wiki.WikiEntry;
import com.portfolio.invest.domain.wiki.WikiEntryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "wiki_entry")
public class WikiEntryJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private WikiEntryType type;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(length = 50)
    private String category;

    @Column(name = "industry_code", length = 16)
    private String industryCode;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected WikiEntryJpaEntity() {}

    public static WikiEntryJpaEntity fromDomain(WikiEntry e) {
        WikiEntryJpaEntity entity = new WikiEntryJpaEntity();
        entity.id = e.id();
        entity.userId = e.userId();
        entity.type = e.type();
        entity.title = e.title();
        entity.content = e.content();
        entity.category = e.category();
        entity.industryCode = e.industryCode();
        entity.createdAt = e.createdAt();
        entity.updatedAt = e.updatedAt();
        entity.version = e.version();
        return entity;
    }

    public WikiEntry toDomain() {
        return WikiEntry.reconstitute(id, userId, type, title, content, category, industryCode,
                createdAt, updatedAt, version);
    }
}
