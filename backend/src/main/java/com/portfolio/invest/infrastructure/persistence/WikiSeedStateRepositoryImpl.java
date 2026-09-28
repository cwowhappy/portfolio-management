package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.wiki.WikiSeedStateRepository;
import org.springframework.stereotype.Repository;

@Repository
public class WikiSeedStateRepositoryImpl implements WikiSeedStateRepository {

    private final WikiSeedStateJpaRepository jpa;

    public WikiSeedStateRepositoryImpl(WikiSeedStateJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public boolean existsByUserId(Long userId) {
        return jpa.existsByUserId(userId);
    }

    @Override
    public void insert(Long userId) {
        jpa.saveAndFlush(WikiSeedStateJpaEntity.of(userId));
    }

    @Override
    public boolean isSopSeeded(Long userId) {
        return jpa.existsByUserIdAndSopSeededAtIsNotNull(userId);
    }

    @Override
    public void markSopSeeded(Long userId) {
        // upsert：行已在（概念先 seed）则补列；行不在则连行带列一并落库
        //（seeded_at NOT NULL 由 of() 填 now——服务层保证调用前概念预置已先行补齐，不谎报）。
        WikiSeedStateJpaEntity row = jpa.findById(userId)
                .orElseGet(() -> WikiSeedStateJpaEntity.of(userId));
        row.markSopSeeded();
        jpa.saveAndFlush(row);
    }
}
