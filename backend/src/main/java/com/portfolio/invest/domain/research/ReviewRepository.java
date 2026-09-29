package com.portfolio.invest.domain.research;

import java.util.List;
import java.util.Optional;

/**
 * 复盘仓库端口（M16-F13/F14/F16）：save 覆盖新建与修正/reflux 落库（聚合有更新用例，
 * 与 {@link FalsifierReviewRepository} append-only 不同）；读方法一律项目域过滤
 * （越项目/不存在一律 empty，404 隔离的仓库侧口径）。
 */
public interface ReviewRepository {

    /** 保存（id 空=新建插入；非空=整行更新，乐观锁版本由仓库实现承载）。 */
    Review save(Review review);

    /** 项目复盘列表（periodStart 倒序 + id 倒序稳定序，照 idx_review_project 口径）。 */
    List<Review> findByProjectId(Long projectId);

    /** 项目域内单行读取。 */
    Optional<Review> findByIdAndProjectId(Long id, Long projectId);
}
