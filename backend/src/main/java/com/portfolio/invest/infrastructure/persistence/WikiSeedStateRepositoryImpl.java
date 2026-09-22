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
}
