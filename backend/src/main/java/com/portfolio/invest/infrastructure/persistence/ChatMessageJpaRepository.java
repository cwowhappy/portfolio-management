package com.portfolio.invest.infrastructure.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatMessageJpaRepository extends JpaRepository<ChatMessageJpaEntity, Long> {
    List<ChatMessageJpaEntity> findByConversationIdOrderByIdAsc(String conversationId);
    void deleteByConversationId(String conversationId);
}
