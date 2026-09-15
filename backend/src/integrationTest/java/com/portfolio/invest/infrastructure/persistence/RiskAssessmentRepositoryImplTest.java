package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.allocation.RiskAssessment;
import com.portfolio.invest.domain.allocation.RiskAssessmentRepository;
import com.portfolio.invest.domain.allocation.RiskProfile;
import com.portfolio.invest.domain.allocation.RiskQuestion;
import com.portfolio.invest.support.PostgresTestSupport;
import org.junit.jupiter.api.BeforeEach;
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
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(RiskAssessmentRepositoryImpl.class)
class RiskAssessmentRepositoryImplTest {

    @ServiceConnection
    static PostgreSQLContainer<?> postgres = PostgresTestSupport.postgres();

    @Autowired
    private RiskAssessmentRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seedUsers() {
        jdbcTemplate.update(
                "INSERT INTO app_user(id, username, password_hash, role, status) VALUES (?, ?, ?, ?, ?)",
                42L, "risk-t3-42", "h", "USER", "PENDING");
    }

    private Map<String, String> allAnswers(String optionId) {
        return java.util.Arrays.stream(RiskQuestion.values())
                .collect(Collectors.toMap(Enum::name, q -> optionId));
    }

    @DisplayName("保存并回读：字段与答卷完整")
    @Test
    void whenSaveAndReadBack_thenFieldsPreserved() {
        RiskAssessment saved = repository.save(
                RiskAssessment.grade(42L, allAnswers("C"), Instant.parse("2026-09-15T00:00:00Z")));
        assertThat(saved.totalScore()).isEqualTo(24); // 8*3
        assertThat(saved.profile()).isEqualTo(RiskProfile.BALANCED);

        RiskAssessment read = repository.findByUserId(42L).orElseThrow();
        assertThat(read.totalScore()).isEqualTo(24);
        assertThat(read.profile()).isEqualTo(RiskProfile.BALANCED);
        assertThat(read.answers()).containsEntry("Q1", "C").hasSize(8);
    }

    @DisplayName("重测覆盖：同用户第二次保存不增行、值更新")
    @Test
    void whenSaveTwiceForSameUser_thenUpsertSingleRow() {
        repository.save(RiskAssessment.grade(42L, allAnswers("E"), Instant.now()));
        repository.save(RiskAssessment.grade(42L, allAnswers("A"), Instant.now()));

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM risk_assessment WHERE user_id = 42", Integer.class);
        assertThat(rows).isEqualTo(1);
        assertThat(repository.findByUserId(42L)).hasValueSatisfying(a -> {
            assertThat(a.totalScore()).isEqualTo(40);
            assertThat(a.profile()).isEqualTo(RiskProfile.AGGRESSIVE);
        });
    }

    @DisplayName("无记录返回空")
    @Test
    void whenNoRecord_thenEmpty() {
        assertThat(repository.findByUserId(9999L)).isEmpty();
    }
}
