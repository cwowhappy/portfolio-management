package com.portfolio.invest.domain.research;

import java.util.List;
import java.util.Optional;

/**
 * 纪律检查留痕 + 证伪命中留痕仓库端口（M16-F10/F12，NFR-2 append-only）：
 * <b>只提供 insert 与读方法，不暴露任何 update</b>（domain 侧亦无更新用例；
 * hit.review_id 回填这一唯一例外由 {@link FalsifierReviewRepository#attachReview} 承载，不在本端口）。
 * 证伪命中落表口径见 {@link FalsifierHit}（Ruling-18：仅 PREDICATE 命中行）。
 */
public interface ResearchCheckRepository {

    /** 追加一条检查留痕（CheckRecord.create 已完成域校验）。 */
    CheckRecord insert(CheckRecord record);

    /** 追加一条证伪命中留痕（日终扫描/实时命中落历史，T5 消费）。 */
    FalsifierHit insertHit(FalsifierHit hit);

    /** 项目的证伪命中留痕（createdAt 倒序，与实时求值合并展示用，D21）。 */
    List<FalsifierHit> findHits(Long projectId);

    /** 项目下未评审（review_id IS NULL）命中的 falsifier id 集（T5 去重：同条件不重复落/不重复推）。 */
    List<Long> findUnreviewedHitFalsifierIds(Long projectId);

    /** 项目域内单行命中读取（P4 评审回填前置校验：hitId 越项目/不存在一律 empty，照 404 隔离口径）。 */
    Optional<FalsifierHit> findHit(Long projectId, Long hitId);

    /**
     * 项目检查留痕列表（createdAt 倒序 + id 倒序稳定序，P4-T3 开读端口：
     * 复盘 4.3 纪律遵守度预填数据源——表本身 append-only 不变，仅补读取用例）。
     */
    List<CheckRecord> findChecks(Long projectId);
}
