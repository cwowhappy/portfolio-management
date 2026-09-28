package com.portfolio.invest.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.domain.research.RefluxState;
import com.portfolio.invest.domain.research.ResearchFeedback;
import com.portfolio.invest.domain.research.ResearchFeedbackRepository;
import com.portfolio.invest.domain.research.ResearchProject;
import com.portfolio.invest.domain.research.ResearchProjectRepository;
import com.portfolio.invest.domain.research.ResearchStage;
import com.portfolio.invest.domain.research.Review;
import com.portfolio.invest.domain.research.ReviewRepository;
import com.portfolio.invest.domain.research.ReviewTier;
import com.portfolio.invest.support.PostgresTestSupport;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * P4-T3 两表贯通：research_review（answers/auto_snapshot/overrides JSONB + trade_ids BIGINT[] 往返、
 * 定格列修正后不变、reflux 状态机落列）与 research_feedback（append-only 只收集）。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import({ResearchProjectRepositoryImpl.class, ReviewRepositoryImpl.class,
        ResearchFeedbackRepositoryImpl.class})
class ReviewFeedbackRepositoryImplTest {

    @ServiceConnection
    static PostgreSQLContainer<?> postgres = PostgresTestSupport.postgres();

    @Autowired
    private ResearchProjectRepository projectRepository;
    @Autowired
    private ReviewRepository reviewRepository;
    @Autowired
    private ResearchFeedbackRepository feedbackRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final LocalDate START = LocalDate.of(2026, 2, 1);
    private static final LocalDate END = LocalDate.of(2026, 2, 28);
    private static final String SNAPSHOT =
            "{\"periodReturn\":0.05,\"priceBasis\":\"东财收盘\",\"tradeIds\":[103,205]}";

    private Long seedProject() {
        Long user = jdbcTemplate.queryForObject(
                "INSERT INTO app_user(username, password_hash, role, status) VALUES (?, 'x', 'USER', 'APPROVED') RETURNING id",
                Long.class, "review_" + System.nanoTime());
        ResearchProject project = projectRepository.save(ResearchProject.create(
                user, "600519", "贵州茅台", "801120", "茅台复盘", ResearchStage.REVIEW));
        return project.id();
    }

    @DisplayName("复盘 insert：快照/作答/覆盖 JSONB 往返保真 + trade_ids BIGINT[] 去重排序落列，PENDING 态")
    @Test
    @Transactional
    void givenReview_whenInsert_thenJsonbAndArrayRoundTrip() {
        Long projectId = seedProject();

        Review inserted = reviewRepository.save(Review.create(projectId, ReviewTier.MONTHLY,
                START, END, SNAPSHOT, List.of(205L, 103L, 205L)));

        assertThat(inserted.id()).isNotNull();
        assertThat(inserted.version()).isEqualTo(0L);
        assertThat(inserted.tradeIds()).containsExactly(103L, 205L);

        Review found = reviewRepository.findByIdAndProjectId(inserted.id(), projectId).orElseThrow();
        assertThat(found.tier()).isEqualTo(ReviewTier.MONTHLY);
        assertThat(found.periodStart()).isEqualTo(START);
        assertThat(found.periodEnd()).isEqualTo(END);
        assertThat(found.snapshotJson()).contains("\"periodReturn\"").contains("0.05"); // JSONB 语义保真
        assertThat(found.answersJson()).isNull();
        assertThat(found.overridesJson()).isNull();
        assertThat(found.narrative()).isNull();
        assertThat(found.tradeIds()).containsExactly(103L, 205L); // bigint[] 往返
        assertThat(found.refluxState()).isEqualTo(RefluxState.PENDING);
        assertThat(found.wikiEntryId()).isNull();

        String tradeIds = jdbcTemplate.queryForObject(
                "SELECT trade_ids::text FROM research_review WHERE id = ?", String.class, inserted.id());
        assertThat(tradeIds).isEqualTo("{103,205}");
        // 项目域过滤：跨项目/不存在不可见（404 隔离的仓库侧口径）
        assertThat(reviewRepository.findByIdAndProjectId(inserted.id(), projectId + 1)).isEmpty();
    }

    @DisplayName("复盘修正 save：answers/overrides/narrative/trade_ids 更新、auto_snapshot 定格不变、version 递增")
    @Test
    @Transactional
    void givenCorrections_whenSave_thenUpdatedWithFrozenSnapshot() {
        Long projectId = seedProject();
        Review inserted = reviewRepository.save(Review.create(projectId, ReviewTier.MONTHLY,
                START, END, SNAPSHOT, List.of(103L, 205L)));
        String frozen = jdbcTemplate.queryForObject(
                "SELECT auto_snapshot::text FROM research_review WHERE id = ?", String.class, inserted.id());

        Review corrected = reviewRepository.save(inserted.correct("{\"q1\":\"追高\"}",
                "{\"periodReturn\":\"0.06\"}", "事后看止损执行晚了", List.of(7L, 3L, 7L)));

        assertThat(corrected.tradeIds()).containsExactly(3L, 7L);
        assertThat(corrected.version()).isEqualTo(1L); // 乐观锁版本递增
        assertThat(jdbcTemplate.queryForObject(
                "SELECT answers::text FROM research_review WHERE id = ?", String.class, inserted.id()))
                .contains("追高");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT overrides::text FROM research_review WHERE id = ?", String.class, inserted.id()))
                .contains("0.06");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT narrative FROM research_review WHERE id = ?", String.class, inserted.id()))
                .isEqualTo("事后看止损执行晚了");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT trade_ids::text FROM research_review WHERE id = ?", String.class, inserted.id()))
                .isEqualTo("{3,7}");
        // 定格：修正后 auto_snapshot 语义不变（F14 不复算历史由列不变保证）
        assertThat(jdbcTemplate.queryForObject(
                "SELECT auto_snapshot::text FROM research_review WHERE id = ?", String.class, inserted.id()))
                .isEqualTo(frozen);
    }

    @DisplayName("refluxConfirm save：reflux_state=REFLOWN + wiki_entry_id 落列；findReviews periodStart 倒序")
    @Test
    @Transactional
    void givenReviews_whenRefluxAndList_thenStatePersistedAndOrdered() {
        Long projectId = seedProject();
        Review february = reviewRepository.save(Review.create(projectId, ReviewTier.MONTHLY,
                START, END, SNAPSHOT, List.of()));
        Review march = reviewRepository.save(Review.create(projectId, ReviewTier.MONTHLY,
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), SNAPSHOT, List.of()));

        reviewRepository.save(february.refluxConfirm(501L));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT reflux_state FROM research_review WHERE id = ?", String.class, february.id()))
                .isEqualTo("REFLOWN");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT wiki_entry_id FROM research_review WHERE id = ?", Long.class, february.id()))
                .isEqualTo(501L);

        // 列表：periodStart 倒序（idx_review_project 口径）；项目隔离
        List<Review> reviews = reviewRepository.findByProjectId(projectId);
        assertThat(reviews).extracting(Review::id).containsExactly(march.id(), february.id());
        assertThat(reviewRepository.findByProjectId(projectId + 1)).isEmpty();
    }

    @DisplayName("建议 insert：stage/content/review_id 落列往返（append-only，只收集）")
    @Test
    @Transactional
    void givenFeedback_whenInsert_thenRowRoundTrip() {
        Long projectId = seedProject();
        Review review = reviewRepository.save(Review.create(projectId, ReviewTier.QUARTERLY,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31), SNAPSHOT, List.of()));

        ResearchFeedback inserted = feedbackRepository.insert(ResearchFeedback.create(
                projectId, review.id(), ResearchStage.REVIEW, "月度模板建议增加仓位口径维度"));

        assertThat(inserted.id()).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM research_feedback WHERE project_id = ? AND review_id = ? "
                        + "AND stage = 'REVIEW' AND content = ?",
                Integer.class, projectId, review.id(), "月度模板建议增加仓位口径维度")).isEqualTo(1);
        // review_id FK：无关联复盘行时拒插（约束兜底，用例层前置校验不至此）
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        feedbackRepository.insert(ResearchFeedback.create(projectId, 99999999L,
                                ResearchStage.STRATEGY, "孤儿建议")))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
