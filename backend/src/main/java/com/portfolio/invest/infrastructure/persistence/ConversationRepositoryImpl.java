package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.conversation.ChatMessage;
import com.portfolio.invest.domain.conversation.Conversation;
import com.portfolio.invest.domain.conversation.ConversationRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

// 事务边界在 application 层（A2），本类不挂 @Transactional：
// 全部写路径的唯一调用方 ConversationApplicationService 已声明事务，
// 底层 Spring Data JpaRepository 的 save/delete 方法自身亦带事务。
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
    public Conversation save(Conversation conversation) {
        return conversationJpa.save(ConversationJpaEntity.fromDomain(conversation)).toDomain();
    }

    @Override
    public void delete(String id) {
        conversationJpa.deleteById(id);
    }

    @Override
    public List<ChatMessage> findMessages(String conversationId) {
        return messageJpa.findByConversationIdOrderByIdAsc(conversationId).stream()
                .map(ChatMessageJpaEntity::toDomain).toList();
    }

    @Override
    public void replaceMessages(String conversationId, List<ChatMessage> messages) {
        messageJpa.deleteByConversationId(conversationId);
        messageJpa.saveAll(messages.stream()
                .map(m -> ChatMessageJpaEntity.fromDomain(conversationId, m)).toList());
    }
}
