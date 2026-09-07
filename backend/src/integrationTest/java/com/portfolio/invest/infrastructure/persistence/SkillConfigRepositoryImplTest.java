package com.portfolio.invest.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.domain.skill.SkillUserConfig;
import com.portfolio.invest.support.PostgresTestSupport;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(SkillConfigRepositoryImpl.class)
class SkillConfigRepositoryImplTest extends PostgresTestSupport {

    @Autowired
    private SkillConfigRepositoryImpl repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** skill_user_config.user_id 外键引用 app_user(id)，需先植入用户行；高位哨兵 id 避开全量运行时注册测试。 */
    private static final long USER_71 = 71L;
    private static final long USER_72 = 72L;

    @BeforeEach
    void seedUsers() {
        jdbcTemplate.update(
                "INSERT INTO app_user(id, username, password_hash, role, status) VALUES (?, ?, ?, ?, ?)",
                USER_71, "skill-t7-71", "h", "USER", "PENDING");
        jdbcTemplate.update(
                "INSERT INTO app_user(id, username, password_hash, role, status) VALUES (?, ?, ?, ?, ?)",
                USER_72, "skill-t7-72", "h", "USER", "PENDING");
    }

    @DisplayName("保存后可按用户+技能码查回")
    @Test
    void givenSave_whenFindByUserIdAndSkillCode_thenRoundTrip() {
        Instant now = Instant.parse("2026-09-07T08:00:00Z");
        repository.save(SkillUserConfig.create(USER_71, "tushare_data", true, now));

        var found = repository.findByUserIdAndSkillCode(USER_71, "tushare_data").orElseThrow();
        assertThat(found.enabled()).isTrue();
        assertThat(found.userId()).isEqualTo(USER_71);
    }

    @DisplayName("按用户查全部选择")
    @Test
    void givenTwoConfigs_whenFindByUserId_thenBothReturned() {
        Instant now = Instant.parse("2026-09-07T08:00:00Z");
        repository.save(SkillUserConfig.create(USER_71, "tushare_data", true, now));
        repository.save(SkillUserConfig.create(USER_71, "wind_finance", false, now));
        repository.save(SkillUserConfig.create(USER_72, "tushare_data", false, now));

        var mine = repository.findByUserId(USER_71);
        assertThat(mine).extracting(SkillUserConfig::skillCode)
                .containsExactlyInAnyOrder("tushare_data", "wind_finance");
    }

    @DisplayName("同一用户+技能码保存覆盖更新")
    @Test
    void givenExisting_whenSaveAgain_thenUpdated() {
        Instant now = Instant.parse("2026-09-07T08:00:00Z");
        SkillUserConfig first = repository.save(SkillUserConfig.create(USER_71, "tushare_data", true, now));
        SkillUserConfig updated = repository.save(first.update(false, now.plusSeconds(10)));

        assertThat(updated.enabled()).isFalse();
        var persisted = repository.findByUserIdAndSkillCode(USER_71, "tushare_data").orElseThrow();
        assertThat(persisted.enabled()).isFalse();
    }
}
