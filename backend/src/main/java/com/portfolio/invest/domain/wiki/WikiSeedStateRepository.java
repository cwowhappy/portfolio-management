package com.portfolio.invest.domain.wiki;

/** 预置 seeding 幂等标记仓库端口：概念与 SOP 模板共用一行、各自独立标记列（删光不复活）。 */
public interface WikiSeedStateRepository {
    boolean existsByUserId(Long userId);
    void insert(Long userId);

    /** SOP 模板标记（sop_seeded_at 非空即已 seed，独立于概念标记——老用户已 seed 概念不复用行存在性判定）。 */
    boolean isSopSeeded(Long userId);
    void markSopSeeded(Long userId);
}
