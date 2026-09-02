package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.journal.JournalEntry;
import com.portfolio.invest.domain.journal.JournalEntryRepository;
import com.portfolio.invest.domain.journal.JournalEntryType;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

// 事务边界在 application 层（P2）；本类不挂 @Transactional。
@Repository
public class JournalEntryRepositoryImpl implements JournalEntryRepository {

    private final JournalEntryJpaRepository jpa;

    public JournalEntryRepositoryImpl(JournalEntryJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public List<JournalEntry> findByUserId(Long userId, JournalEntryType type) {
        List<JournalEntryJpaEntity> entities = type == null
                ? jpa.findByUserIdOrderByUpdatedAtDesc(userId)
                : jpa.findByUserIdAndTypeOrderByUpdatedAtDesc(userId, type);
        return entities.stream().map(JournalEntryJpaEntity::toDomain).toList();
    }

    @Override
    public Optional<JournalEntry> findByIdAndUserId(Long id, Long userId) {
        return jpa.findByIdAndUserId(id, userId).map(JournalEntryJpaEntity::toDomain);
    }

    @Override
    public JournalEntry save(JournalEntry entry) {
        return jpa.save(JournalEntryJpaEntity.fromDomain(entry)).toDomain();
    }

    @Override
    public void deleteById(Long id) {
        jpa.deleteById(id);
    }
}
