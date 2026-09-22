package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.wiki.WikiEntry;
import com.portfolio.invest.domain.wiki.WikiEntryRepository;
import com.portfolio.invest.domain.wiki.WikiEntryType;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class WikiEntryRepositoryImpl implements WikiEntryRepository {

    private final WikiEntryJpaRepository jpa;

    public WikiEntryRepositoryImpl(WikiEntryJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public List<WikiEntry> findByUserId(Long userId, WikiEntryType type) {
        List<WikiEntryJpaEntity> entities = type == null
                ? jpa.findByUserIdOrderByUpdatedAtDesc(userId)
                : jpa.findByUserIdAndTypeOrderByUpdatedAtDesc(userId, type);
        return entities.stream().map(WikiEntryJpaEntity::toDomain).toList();
    }

    @Override
    public Optional<WikiEntry> findByIdAndUserId(Long id, Long userId) {
        return jpa.findByIdAndUserId(id, userId).map(WikiEntryJpaEntity::toDomain);
    }

    @Override
    public WikiEntry save(WikiEntry entry) {
        return jpa.save(WikiEntryJpaEntity.fromDomain(entry)).toDomain();
    }

    @Override
    public void deleteById(Long id) {
        jpa.deleteById(id);
    }
}
