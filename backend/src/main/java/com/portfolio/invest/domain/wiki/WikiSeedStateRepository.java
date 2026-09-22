package com.portfolio.invest.domain.wiki;

/** 概念预置 seeding 幂等标记仓库端口。 */
public interface WikiSeedStateRepository {
    boolean existsByUserId(Long userId);
    void insert(Long userId);
}
