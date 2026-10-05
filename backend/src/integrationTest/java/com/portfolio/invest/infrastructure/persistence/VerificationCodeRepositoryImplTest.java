package com.portfolio.invest.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.domain.user.VerificationCode;
import com.portfolio.invest.domain.user.VerificationCodeRepository;
import com.portfolio.invest.domain.user.VerificationPurpose;
import com.portfolio.invest.support.ConcurrencyTestSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 验证码仓库真库契约（Testcontainers PG16 + V1 verification_code）：查询/计数既有契约
 * 之外，B8 新增 tryMarkUsed 原子消费——未用未过期恰一置位，并发双调恰一 true
 * （PG 行锁 + 谓词重评，读-判定-写在应用层无法保证）。基座照 BindingCodeRepositoryTest
 * （@SpringBootTest 真库、无类级事务回滚——并发线程须看到已提交的种子行），
 * 并发形态用 ConcurrencyTestSupport.race（两线程 + CyclicBarrier 齐发）。
 */
@SpringBootTest
class VerificationCodeRepositoryImplTest extends ConcurrencyTestSupport {

    private static final Instant NOW = Instant.parse("2026-10-05T10:00:00Z");
    private static final String EMAIL = "vc-it@test.local";

    @Autowired
    VerificationCodeRepository repo;

    @BeforeEach
    @AfterEach
    void cleanTables() {
        jdbcTemplate.update("DELETE FROM verification_code WHERE email LIKE ?", "vc-it%@test.local");
        jdbcTemplate.update("DELETE FROM verification_code WHERE email = ?", "a@x.com");
    }

    @DisplayName("保存后可按邮箱用途查最新一条")
    @Test
    void whenSaveTwo_thenLatestFirst() {
        Instant now = Instant.now();
        repo.save(VerificationCode.reconstitute(null, "a@x.com", VerificationPurpose.REGISTER,
                "h1", 0, null, now.plusSeconds(300), now.minusSeconds(60)));
        repo.save(VerificationCode.reconstitute(null, "a@x.com", VerificationPurpose.REGISTER,
                "h2", 0, null, now.plusSeconds(300), now));
        Optional<VerificationCode> latest = repo.findTopByEmailAndPurposeOrderByCreatedAtDesc("a@x.com", VerificationPurpose.REGISTER);
        assertThat(latest).isPresent();
        assertThat(latest.get().codeHash()).isEqualTo("h2");
        assertThat(repo.countByEmailAndCreatedAtAfter("a@x.com", now.minusSeconds(120))).isEqualTo(2);
    }

    @DisplayName("未用未过期的码 tryMarkUsed 返回 true 且 used_at 落库")
    @Test
    void givenFreshCode_whenTryMarkUsed_thenTrueAndUsedAtPersisted() {
        Long id = seedCode(null, NOW.plusSeconds(300));

        boolean marked = repo.tryMarkUsed(id, NOW);

        assertThat(marked).isTrue();
        assertThat(readUsedAt(id)).isEqualTo(NOW);
    }

    @DisplayName("已用的码 tryMarkUsed 返回 false（重放拒绝）")
    @Test
    void givenUsedCode_whenTryMarkUsed_thenFalse() {
        Long id = seedCode(NOW.minusSeconds(30), NOW.plusSeconds(300));

        assertThat(repo.tryMarkUsed(id, NOW)).isFalse();
        assertThat(readUsedAt(id)).as("原消费时刻不被覆盖").isEqualTo(NOW.minusSeconds(30));
    }

    @DisplayName("已过期的码 tryMarkUsed 返回 false")
    @Test
    void givenExpiredCode_whenTryMarkUsed_thenFalse() {
        Long id = seedCode(null, NOW.minusSeconds(1));

        assertThat(repo.tryMarkUsed(id, NOW)).isFalse();
        assertThat(readUsedAt(id)).as("过期码不置位").isNull();
    }

    @DisplayName("并发双调 tryMarkUsed 恰一 true（原子消费，败者不置位）")
    @Test
    void givenFreshCode_whenConcurrentTryMarkUsed_thenExactlyOneTrue() throws Exception {
        Long id = seedCode(null, NOW.plusSeconds(300));
        AtomicInteger trues = new AtomicInteger();

        // race：两线程 CyclicBarrier 齐发；tryMarkUsed 抛异常会在 future.get 原样炸测试
        race(2, () -> {
            if (repo.tryMarkUsed(id, NOW)) {
                trues.incrementAndGet();
            }
            return null;
        });

        assertThat(trues.get()).as("并发双调恰一 true（一次性消费语义）").isEqualTo(1);
        assertThat(readUsedAt(id)).as("胜者置位时刻落库").isEqualTo(NOW);
    }

    @DisplayName("deleteCreatedBefore 只删 cutoff 之前行并返回删除数（恰在 cutoff 保留，< 严格）")
    @Test
    void givenCodesBeforeAtAndAfterCutoff_whenDeleteCreatedBefore_thenOnlyOlderDeletedWithCount() {
        repo.save(VerificationCode.reconstitute(null, "vc-it-old@test.local", VerificationPurpose.REGISTER,
                "h-old", 0, null, NOW.plusSeconds(600), NOW.minus(Duration.ofDays(91))));
        repo.save(VerificationCode.reconstitute(null, "vc-it-edge@test.local", VerificationPurpose.REGISTER,
                "h-edge", 0, null, NOW.plusSeconds(600), NOW));
        repo.save(VerificationCode.reconstitute(null, "vc-it-new@test.local", VerificationPurpose.REGISTER,
                "h-new", 0, null, NOW.plusSeconds(600), NOW.plus(Duration.ofDays(1))));

        int deleted = repo.deleteCreatedBefore(NOW);

        assertThat(deleted).as("仅 cutoff 前一行被删，返回删除数").isEqualTo(1);
        assertThat(countByEmail("vc-it-old@test.local")).as("91 天前的码应删除").isZero();
        assertThat(countByEmail("vc-it-edge@test.local")).as("恰在 cutoff 的行保留（< 严格语义）").isEqualTo(1);
        assertThat(countByEmail("vc-it-new@test.local")).as("cutoff 之后的行保留").isEqualTo(1);
    }

    // ── fixture 助手 ───────────────────────────────────────────────

    /** 照 RegistrationConcurrencyIntegrationTest 先例经 repo.save 落提交态码行（返回生成 id）。 */
    private Long seedCode(Instant usedAt, Instant expiresAt) {
        return repo.save(VerificationCode.reconstitute(null, EMAIL, VerificationPurpose.REGISTER,
                "h", 0, usedAt, expiresAt, NOW.minusSeconds(60))).id();
    }

    /** 读指定码的 used_at（Timestamp → Instant 归一比较）。 */
    private Instant readUsedAt(Long id) {
        java.sql.Timestamp ts = jdbcTemplate.queryForObject(
                "SELECT used_at FROM verification_code WHERE id = ?", java.sql.Timestamp.class, id);
        return ts == null ? null : ts.toInstant();
    }

    /** 按邮箱计行（删除/保留断言）。 */
    private int countByEmail(String email) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM verification_code WHERE email = ?", Integer.class, email);
    }
}
