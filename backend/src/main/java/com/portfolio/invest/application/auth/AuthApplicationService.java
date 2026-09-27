package com.portfolio.invest.application.auth;

import com.portfolio.invest.domain.user.PasswordPolicy;
import com.portfolio.invest.domain.user.RememberMeTokenStore;
import com.portfolio.invest.domain.user.User;
import com.portfolio.invest.domain.user.UserErrorCode;
import com.portfolio.invest.domain.user.UserException;
import com.portfolio.invest.domain.user.UserRepository;
import com.portfolio.invest.domain.user.UserStatus;
import com.portfolio.invest.domain.user.UsernamePolicy;
import com.portfolio.invest.domain.user.VerificationPurpose;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthApplicationService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailCodeService emailCodeService;
    private final RememberMeTokenStore rememberMeTokenStore;

    public AuthApplicationService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                                  EmailCodeService emailCodeService, RememberMeTokenStore rememberMeTokenStore) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailCodeService = emailCodeService;
        this.rememberMeTokenStore = rememberMeTokenStore;
    }

    @Transactional
    public UserView register(RegisterCommand cmd) {
        PasswordPolicy.validate(cmd.password());
        UsernamePolicy.validate(cmd.username());
        String username = cmd.username().trim();
        // R4：register 侧自行归一化邮箱（EmailCodeService.normalize 为其私有），此后一律用 email 局部变量
        String email = cmd.email() == null ? null : cmd.email().trim().toLowerCase(Locale.ROOT);
        Optional<User> existing = userRepository.findByUsername(username);
        if (existing.isPresent() && existing.get().status() != UserStatus.REJECTED) {
            throw new UserException(UserErrorCode.USERNAME_TAKEN, "用户名已存在");
        }
        // R2：被拒用户沿用自己原邮箱时不算占用（assertEmailAvailable 语义是「未被他人绑定」）
        if (existing.isEmpty() || !Objects.equals(existing.get().email(), email)) {
            emailCodeService.assertEmailAvailable(email);
        }
        // 码先消费再落库：错码在创建账号前拦截（FR-A3）
        emailCodeService.verify(email, VerificationPurpose.REGISTER, cmd.code());
        String hash = passwordEncoder.encode(cmd.password());
        try {
            User saved = existing
                    .map(u -> userRepository.save(u.reRegister(hash, email)))
                    .orElseGet(() -> userRepository.save(User.register(username, hash, email)));
            return UserView.from(saved);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // 并发窗口唯一索引兜底：按约束名区分用户名/邮箱冲突（V21 uk_app_user_email）
            String msg = String.valueOf(e.getMostSpecificCause());
            if (msg.contains("uk_app_user_email")) {
                throw new UserException(UserErrorCode.EMAIL_TAKEN, "邮箱已被其他账号绑定");
            }
            throw new UserException(UserErrorCode.USERNAME_TAKEN, "用户名已存在");
        }
    }

    /** FR-B3：自助重置——验码通过换密码并吊销全部 remember-me。 */
    @Transactional
    public void resetPassword(String identifier, String code, String newPassword) {
        PasswordPolicy.validate(newPassword);
        User user = emailCodeService.findResettableUser(identifier);
        emailCodeService.verify(user.email(), VerificationPurpose.RESET, code);
        userRepository.save(user.withPassword(passwordEncoder.encode(newPassword)));
        // 密码已换：旧「记住我」令牌立即失效（同 UserAdminApplicationService.resetPassword 口径）
        rememberMeTokenStore.removeUserTokens(user.username());
    }
}
