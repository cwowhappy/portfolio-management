package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.wiki.WikiEntryType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WikiEntryJpaRepository extends JpaRepository<WikiEntryJpaEntity, Long> {
    List<WikiEntryJpaEntity> findByUserIdOrderByUpdatedAtDesc(Long userId);
    List<WikiEntryJpaEntity> findByUserIdAndTypeOrderByUpdatedAtDesc(Long userId, WikiEntryType type);
    Optional<WikiEntryJpaEntity> findByIdAndUserId(Long id, Long userId);
}
