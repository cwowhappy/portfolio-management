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
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class AdminSeedRunnerTest {

    private final InvestProperties props = new InvestProperties();
    private final UserRepository repo = mock(UserRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final AdminSeedRunner runner = new AdminSeedRunner(props, repo, encoder);

    @Test
    void 未配置管理员账号时跳过() {
        runner.run(null);
        verify(repo, never()).save(any());
    }

    @Test
    void 配置后创建内置管理员() {
        props.getAdmin().setUsername("admin");
        props.getAdmin().setPassword("admin123");
        when(encoder.encode("admin123")).thenReturn("$2a$hash");
        when(repo.findByUsername("admin")).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        runner.run(null);

        verify(repo).save(argIsAdmin());
    }

    @Test
    void 用户名已存在时幂等跳过() {
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
