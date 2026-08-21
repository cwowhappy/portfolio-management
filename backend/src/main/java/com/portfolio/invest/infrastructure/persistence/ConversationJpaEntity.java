package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.conversation.Conversation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "conversation")
public class ConversationJpaEntity {

    @Id
    @Column(columnDefinition = "varchar(64)")
    private String id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String title;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ConversationJpaEntity() {}

    static ConversationJpaEntity fromDomain(Conversation c) {
        ConversationJpaEntity e = new ConversationJpaEntity();
        e.id = c.id();
        e.userId = c.userId();
        e.title = c.title();
        e.createdAt = c.createdAt();
        e.updatedAt = c.updatedAt();
        return e;
    }

    Conversation toDomain() {
        return Conversation.reconstitute(id, userId, title, createdAt, updatedAt);
    }

    public String getId() { return id; }
    public Long getUserId() { return userId; }
    public String getTitle() { return title; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
