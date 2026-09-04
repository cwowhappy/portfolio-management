package com.portfolio.invest.infrastructure.seed;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.user.User;
import com.portfolio.invest.domain.user.UserRepository;
import com.portfolio.invest.domain.user.UserRole;
import com.portfolio.invest.domain.user.UserStatus;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class AdminSeedRunnerTest {

    private final InvestProperties props = new InvestProperties();
    private final UserRepository repo = mock(UserRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final AdminSeedRunner runner = new AdminSeedRunner(props, repo, encoder);

    @DisplayName("未配置管理员账号时跳过")
    @Test
    void givenAdminAccountNotConfigured_whenRun_thenSkips() {
        runner.run(null);
        verify(repo, never()).save(any());
    }

    @DisplayName("仅配置用户名未配置密码时跳过")
    @Test
    void givenOnlyUsernameWithoutPassword_whenRun_thenSkips() {
        props.getAdmin().setUsername("admin");

        runner.run(null);

        verify(repo, never()).save(any());
    }

    @DisplayName("用户名或密码为空白时跳过")
    @Test
    void givenUsernameOrPasswordBlank_whenRun_thenSkips() {
        props.getAdmin().setUsername("   ");
        props.getAdmin().setPassword("admin123");
        runner.run(null);

        props.getAdmin().setUsername("admin");
        props.getAdmin().setPassword("   ");
        runner.run(null);

        verify(repo, never()).save(any());
    }

    @DisplayName("配置后创建内置管理员")
    @Test
    void givenAdminConfigured_whenRun_thenCreatesBuiltInAdmin() {
        props.getAdmin().setUsername("admin");
        props.getAdmin().setPassword("admin123");
        when(encoder.encode("admin123")).thenReturn("$2a$hash");
        when(repo.findByUsername("admin")).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        runner.run(null);

        verify(repo).save(argIsAdmin());
    }

    @DisplayName("用户名已存在时幂等跳过")
    @Test
    void givenUsernameAlreadyExists_whenRun_thenSkipsIdempotently() {
        props.getAdmin().setUsername("admin");
        props.getAdmin().setPassword("admin123");
        when(repo.findByUsername("admin")).thenReturn(Optional.of(
                User.reconstitute(1L, "admin", "h", UserRole.ADMIN, UserStatus.APPROVED, true, null, null)));

        runner.run(null);

        verify(repo, never()).save(any());
    }

    private User argIsAdmin() {
        return org.mockito.ArgumentMatchers.argThat(u ->
                u.role() == UserRole.ADMIN && u.status() == UserStatus.APPROVED
                        && u.username().equals("admin") && u.passwordHash().equals("$2a$hash"));
    }
}
