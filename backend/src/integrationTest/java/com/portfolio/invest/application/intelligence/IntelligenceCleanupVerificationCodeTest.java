package com.portfolio.invest.application.intelligence;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.domain.intelligence.BindingCodeRepository;
import com.portfolio.invest.domain.intelligence.NewsRepository;
import com.portfolio.invest.domain.user.VerificationCode;
import com.portfolio.invest.domain.user.VerificationCodeRepository;
import com.portfolio.invest.domain.user.VerificationPurpose;
import com.portfolio.invest.support.PostgresTestSupport;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 滚动清理第三步（验证码 90 天）真库边界语义：与 IntelligenceCleanupTest（@Autowired
 * bean、系统时钟）互补——「90 天整」的边界行在系统时钟下必然受 seed 与 cleanup 间的
 * 执行耗时而摇摆，本类与 IntelligenceCleanupService 同包，借测试构造器注入固定时钟，
 * 使 cutoff 精确等于 fixture 的 created_at，钉死 {@code <} 严格语义。三个仓库为真实
 * Bean（真 PG 落库），新闻/绑定码两步顺带真库空跑不干预本类断言（共享容器卫生照
 * IntelligenceCleanupTest 先例双向清 fixture）。
 */
@SpringBootTest
class IntelligenceCleanupVerificationCodeTest extends PostgresTestSupport {

    /** 固定时刻（真实仓库 + 固定时钟 → cutoff = NOW-90d 精确可知）。 */
    private static final Instant NOW = Instant.parse("2026-10-05T02:07:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("Asia/Shanghai"));

    @Autowired
    NewsRepository newsRepository;
    @Autowired
    BindingCodeRepository bindingCodeRepository;
    @Autowired
    VerificationCodeRepository codeRepository;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void cleanCodes() {
        jdbc.update("DELETE FROM verification_code WHERE email LIKE ?", "vc-cln%@test.local");
    }

    @Test
    @DisplayName("给定91天前与恰90天整的验证码，when清理，then旧码删恰边界码留（< 严格）")
    void givenCodesAt91dAndExactly90d_whenCleanup_thenOldDeletedBoundaryRetained() {
        codeRepository.save(VerificationCode.reconstitute(null, "vc-cln-old@test.local",
                VerificationPurpose.REGISTER, "h-old", 0, null, NOW.plusSeconds(600),
                NOW.minus(Duration.ofDays(91))));
        codeRepository.save(VerificationCode.reconstitute(null, "vc-cln-edge@test.local",
                VerificationPurpose.REGISTER, "h-edge", 0, null, NOW.plusSeconds(600),
                NOW.minus(Duration.ofDays(90))));

        new IntelligenceCleanupService(newsRepository, bindingCodeRepository, codeRepository, CLOCK)
                .cleanupNow();

        assertThat(count("vc-cln-old@test.local")).as("91 天前的码应删除").isZero();
        assertThat(count("vc-cln-edge@test.local"))
                .as("恰 90 天整（created_at == cutoff）保留，< 严格语义").isEqualTo(1);
    }

    // ── fixture 助手 ───────────────────────────────────────────────

    private int count(String email) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM verification_code WHERE email = ?", Integer.class, email);
    }
}
