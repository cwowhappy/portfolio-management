package com.portfolio.invest.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.invest.domain.user.User;
import com.portfolio.invest.domain.user.UserRepository;
import com.portfolio.invest.domain.user.UserStatus;
import com.portfolio.invest.support.PostgresTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;

// @DataJpaTest 切片 + 真实 PG：@ServiceConnection 复用 testFixtures 的 JVM 单例容器，整个测试进程只起一个 Postgres。
// Boot 4 的 @DataJpaTest 不含 Flyway 自动配置（schema 由 Flyway 管），需 @ImportAutoConfiguration 显式引入；
// RepositoryImpl 适配器不在切片扫描范围内，用 @Import 显式装配。
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(UserRepositoryImpl.class)
class UserRepositoryImplTest {

    @ServiceConnection
    static PostgreSQLContainer<?> postgres = PostgresTestSupport.postgres();

    @Autowired
    UserRepository userRepository;

    @DisplayName("保存后按用户名与id可查回")
    @Test
    void whenSaveUser_thenFindableByUsernameAndId() {
        User saved = userRepository.save(User.register("bob", "hash"));
        assertThat(saved.id()).isNotNull();
        assertThat(userRepository.findByUsername("bob")).isPresent();
        assertThat(userRepository.findById(saved.id())).get().satisfies(u -> {
            assertThat(u.username()).isEqualTo("bob");
            assertThat(u.status()).isEqualTo(UserStatus.PENDING);
        });
    }

    @DisplayName("username唯一约束生效")
    @Test
    void givenSameUsernameSaved_whenSaveDuplicate_thenUniqueConstraintViolated() {
        userRepository.save(User.register("carol", "h1"));
        assertThatThrownBy(() -> userRepository.save(User.register("carol", "h2")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @DisplayName("findAll返回全部")
    @Test
    void givenSavedUsers_whenFindAll_thenAllReturned() {
        userRepository.save(User.register("dave", "h"));
        assertThat(userRepository.findAll()).isNotEmpty();
    }
}
