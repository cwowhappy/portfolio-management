package com.portfolio.invest.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationJpaRepository extends JpaRepository<ConversationJpaEntity, String> {
    Optional<ConversationJpaEntity> findByIdAndUserId(String id, Long userId);
    List<ConversationJpaEntity> findByUserIdOrderByUpdatedAtDesc(Long userId);
}
