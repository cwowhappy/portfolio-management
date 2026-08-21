package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.conversation.ChatMessage;
import com.portfolio.invest.domain.conversation.Conversation;
import com.portfolio.invest.domain.conversation.ConversationRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ConversationRepositoryImpl implements ConversationRepository {

    private final ConversationJpaRepository conversationJpa;
    private final ChatMessageJpaRepository messageJpa;

    public ConversationRepositoryImpl(ConversationJpaRepository conversationJpa, ChatMessageJpaRepository messageJpa) {
        this.conversationJpa = conversationJpa;
        this.messageJpa = messageJpa;
    }

    @Override
    public Optional<Conversation> findByIdAndUserId(String id, Long userId) {
        return conversationJpa.findByIdAndUserId(id, userId).map(ConversationJpaEntity::toDomain);
    }

    @Override
    public List<Conversation> findByUserId(Long userId) {
        return conversationJpa.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                .map(ConversationJpaEntity::toDomain).toList();
    }

    @Override
    public boolean existsById(String id) {
        return conversationJpa.existsById(id);
    }

    @Override
    @Transactional
    public Conversation save(Conversation conversation) {
        return conversationJpa.save(ConversationJpaEntity.fromDomain(conversation)).toDomain();
    }

    @Override
    @Transactional
    public void delete(String id) {
        conversationJpa.deleteById(id);
    }

    @Override
    public List<ChatMessage> findMessages(String conversationId) {
        return messageJpa.findByConversationIdOrderByIdAsc(conversationId).stream()
                .map(ChatMessageJpaEntity::toDomain).toList();
    }

    @Override
    @Transactional
    public void replaceMessages(String conversationId, List<ChatMessage> messages) {
        messageJpa.deleteByConversationId(conversationId);
        messageJpa.saveAll(messages.stream()
                .map(m -> ChatMessageJpaEntity.fromDomain(conversationId, m)).toList());
    }
}
