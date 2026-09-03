package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.journal.JournalEntryType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JournalEntryJpaRepository extends JpaRepository<JournalEntryJpaEntity, Long> {
    List<JournalEntryJpaEntity> findByUserIdOrderByUpdatedAtDesc(Long userId);
    List<JournalEntryJpaEntity> findByUserIdAndTypeOrderByUpdatedAtDesc(Long userId, JournalEntryType type);
    Optional<JournalEntryJpaEntity> findByIdAndUserId(Long id, Long userId);
    List<JournalEntryJpaEntity> findByUserIdAndEventDateBetweenOrderByEventDateDesc(Long userId, LocalDate from, LocalDate to);
    List<JournalEntryJpaEntity> findByUserIdAndEventDateGreaterThanEqualOrderByEventDateDesc(Long userId, LocalDate from);
    List<JournalEntryJpaEntity> findByUserIdAndEventDateLessThanEqualOrderByEventDateDesc(Long userId, LocalDate to);
}
