package com.portfolio.invest.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.application.auth.AuthApplicationService;
import com.portfolio.invest.application.auth.RegisterCommand;
import com.portfolio.invest.domain.user.UserErrorCode;
import com.portfolio.invest.domain.user.UserException;
import com.portfolio.invest.support.ConcurrencyTestSupport;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 并发注册同一用户名的真实 PG 验证：AuthApplicationService 的 findByUsername→insert 存在
 * TOCTOU 窗口，由 app_user.username 唯一索引兜底 + UserRepositoryImpl.saveAndFlush 让
 * DataIntegrityViolationException 在事务内抛出，应用层映射为业务异常 USERNAME_TAKEN。
 * 单元测试（AuthApplicationServiceTest）用 mock 模拟了这一竞态，此处用真实库验证端到端行为。
 */
@SpringBootTest
class RegistrationConcurrencyIntegrationTest extends ConcurrencyTestSupport {

    @Autowired
    private AuthApplicationService authService;

    private static final String USERNAME = "conc-reg-same";

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM app_user WHERE username = ?", USERNAME);
    }

    @DisplayName("并发注册同一用户名仅一个成功其余得到业务异常")
    @Test
    void givenConcurrentSameUsernameRegistrations_whenRegister_thenOneSucceedsOthersGetBusinessException() throws Exception {
        int threads = 8;
        AtomicInteger success = new AtomicInteger();
        AtomicInteger usernameTaken = new AtomicInteger();
        race(threads, () -> {
            try {
                authService.register(new RegisterCommand(USERNAME, "abc12345"));
                success.incrementAndGet();
            } catch (UserException e) {
                // 预期业务异常：TOCTOU 预检命中或唯一索引兜底，均为 USERNAME_TAKEN；
                // 其他异常（如约束违例未映射）由 future.get 原样抛出使测试失败
                assertThat(e.getCode()).isEqualTo(UserErrorCode.USERNAME_TAKEN);
                usernameTaken.incrementAndGet();
            }
            return null;
        });

        assertThat(success.get()).isEqualTo(1);
        assertThat(usernameTaken.get()).isEqualTo(threads - 1);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM app_user WHERE username = ?", Integer.class, USERNAME);
        assertThat(count).isEqualTo(1);
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM app_user WHERE username = ?", String.class, USERNAME);
        assertThat(status).isEqualTo("PENDING");
    }
}
