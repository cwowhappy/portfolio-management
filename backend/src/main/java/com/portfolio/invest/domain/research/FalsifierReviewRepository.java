package com.portfolio.invest.domain.research;

import java.util.List;

/**
 * 证伪评审留痕仓库端口（M16-F15，NFR-2 append-only）：<b>评审行本身只提供 insert 与读方法，
 * 不暴露任何 update</b>（domain 侧亦无更新用例）。
 *
 * <p>唯一例外：{@link #attachReview(Long, Long)} 更新 {@code research_falsifier_hit.review_id}。
 * hit 表整体 append-only（只增不改，见 {@link ResearchCheckRepository}），但 review_id 软引用列
 * 在设计上即「评审后回填」（V2 列注释）——评审落库后须把命中行与结论留痕关联，这是 hit 表
 * <b>唯一合法更新</b>，故单独开本端口承载（方法名 attach 表达「挂接既有行」而非改写留痕内容）。
 * 回填仅当 review_id IS NULL 时生效（首评占据软引用，不覆盖——后续评审照常 append，链路不丢）。
 */
public interface FalsifierReviewRepository {

    /** 追加一条评审留痕（FalsifierReview.create 已完成域校验）。 */
    FalsifierReview insert(FalsifierReview review);

    /** 项目评审留痕（createdAt 倒序，前端列表用）。 */
    List<FalsifierReview> findReviews(Long projectId);

    /**
     * hit 回填 review_id（hit 表唯一合法更新，例外理由见接口 javadoc）：
     * 仅当该命中行 review_id IS NULL 时写入，已回填行不覆盖（append-only 精神）。
     */
    void attachReview(Long hitId, Long reviewId);
}
