package com.portfolio.invest.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.domain.user.VerificationCode;
import com.portfolio.invest.domain.user.VerificationCodeRepository;
import com.portfolio.invest.domain.user.VerificationPurpose;
import com.portfolio.invest.support.PostgresTestSupport;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;

// @DataJpaTest 切片 + 真实 PG：@ServiceConnection 复用 testFixtures 的 JVM 单例容器（同 UserRepositoryImplTest）。
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(VerificationCodeRepositoryImpl.class)
class VerificationCodeRepositoryImplTest {

    @ServiceConnection
    static PostgreSQLContainer<?> postgres = PostgresTestSupport.postgres();

    @Autowired
    VerificationCodeRepository repo;

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
}
