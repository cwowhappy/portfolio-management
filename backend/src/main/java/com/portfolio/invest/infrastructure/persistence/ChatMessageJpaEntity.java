package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.conversation.ChatMessage;
import com.portfolio.invest.domain.conversation.ChatMessageRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "chat_message")
public class ChatMessageJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false)
    private String conversationId;

    @Column(name = "message_id", nullable = false)
    private String messageId;

    @Column(nullable = false)
    private String role;

    @Column(nullable = false)
    private String content;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Long createdAtMs;

    protected ChatMessageJpaEntity() {}

    static ChatMessageJpaEntity fromDomain(String conversationId, ChatMessage m) {
        ChatMessageJpaEntity e = new ChatMessageJpaEntity();
        e.id = m.dbId();
        e.conversationId = conversationId;
        e.messageId = m.id();
        e.role = m.role().wire();
        e.content = m.content();
        e.payload = m.payload();
        e.createdAtMs = m.createdAtMs();
        return e;
    }

    ChatMessage toDomain() {
        return ChatMessage.create(id, messageId, ChatMessageRole.fromWire(role), content, payload, createdAtMs);
    }
}
