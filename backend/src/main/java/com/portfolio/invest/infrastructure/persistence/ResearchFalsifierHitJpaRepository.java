package com.portfolio.invest.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * append-only 表：仓库侧只读 + insert（save）；T5 日终扫描按 falsifier 去重（未评审行不重复 insert）。
 * 唯一更新入口 attachReviewIfUnreviewed（P4 评审回填 review_id，仅未评审行生效、不覆盖）。
 */
public interface ResearchFalsifierHitJpaRepository extends JpaRepository<ResearchFalsifierHitJpaEntity, Long> {

    List<ResearchFalsifierHitJpaEntity> findByProjectIdOrderByCreatedAtDescIdDesc(Long projectId);

    List<ResearchFalsifierHitJpaEntity> findByProjectIdAndReviewIdIsNull(Long projectId);

    /** 项目域内单行读取（P4 评审回填前置校验：跨项目/不存在 → empty）。 */
    Optional<ResearchFalsifierHitJpaEntity> findByIdAndProjectId(Long id, Long projectId);

    /**
     * hit 表唯一合法更新（NFR-2 append-only 例外）：评审落库后回填 review_id 软引用。
     * WHERE review_id IS NULL 守卫——首评占据软引用，二次评审不覆盖（留痕照常 append）。
     */
    @Modifying
    @Query("update ResearchFalsifierHitJpaEntity h set h.reviewId = :reviewId "
            + "where h.id = :hitId and h.reviewId is null")
    int attachReviewIfUnreviewed(@Param("hitId") Long hitId, @Param("reviewId") Long reviewId);
}
