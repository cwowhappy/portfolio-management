package com.portfolio.invest.infrastructure.seed;

import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.user.User;
import com.portfolio.invest.domain.user.UserRepository;
import com.portfolio.invest.domain.user.UserRole;
import com.portfolio.invest.domain.user.UserStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** 启动幂等种子管理员：ADMIN_USERNAME/ADMIN_PASSWORD 已配置且用户名不存在时创建。 */
@Component
public class AdminSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminSeedRunner.class);

    private final InvestProperties props;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AdminSeedRunner(InvestProperties props, UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.props = props;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        String username = props.getAdmin().getUsername();
        String password = props.getAdmin().getPassword();
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            log.warn("未配置 ADMIN_USERNAME/ADMIN_PASSWORD，跳过管理员种子");
            return;
        }
        if (userRepository.findByUsername(username.trim()).isPresent()) {
            return;
        }
        User admin = User.reconstitute(null, username.trim(), passwordEncoder.encode(password),
                UserRole.ADMIN, UserStatus.APPROVED, true, java.time.Instant.now(), java.time.Instant.now());
        userRepository.save(admin);
        log.info("已创建内置管理员: {}", username);
    }
}
