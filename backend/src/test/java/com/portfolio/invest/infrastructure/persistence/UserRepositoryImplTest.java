package com.portfolio.invest.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.invest.domain.user.User;
import com.portfolio.invest.domain.user.UserRepository;
import com.portfolio.invest.domain.user.UserStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

class UserRepositoryImplTest extends IntegrationTestBase {

    @Autowired
    UserRepository userRepository;

    @Test
    void 保存后按用户名与id可查回() {
        User saved = userRepository.save(User.register("bob", "hash"));
        assertThat(saved.id()).isNotNull();
        assertThat(userRepository.findByUsername("bob")).isPresent();
        assertThat(userRepository.findById(saved.id())).get().satisfies(u -> {
            assertThat(u.username()).isEqualTo("bob");
            assertThat(u.status()).isEqualTo(UserStatus.PENDING);
        });
    }

    @Test
    void username唯一约束生效() {
        userRepository.save(User.register("carol", "h1"));
        assertThatThrownBy(() -> userRepository.save(User.register("carol", "h2")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findAll返回全部() {
        userRepository.save(User.register("dave", "h"));
        assertThat(userRepository.findAll()).isNotEmpty();
    }
}
