package com.portfolio.invest.application.useradmin;

import com.portfolio.invest.application.auth.EmailCodeService;
import com.portfolio.invest.application.auth.MailSender;
import com.portfolio.invest.domain.user.PasswordPolicy;
import com.portfolio.invest.domain.user.RememberMeTokenStore;
import com.portfolio.invest.domain.user.User;
import com.portfolio.invest.domain.user.UserErrorCode;
import com.portfolio.invest.domain.user.UserException;
import com.portfolio.invest.domain.user.UserRepository;
import com.portfolio.invest.domain.user.UserRole;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserAdminApplicationService {

    private static final Logger log = LoggerFactory.getLogger(UserAdminApplicationService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RememberMeTokenStore rememberMeTokenStore;
    private final EmailCodeService emailCodeService;
    private final MailSender mailSender;
    private final ApplicationEventPublisher publisher;

    public UserAdminApplicationService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                                       RememberMeTokenStore rememberMeTokenStore,
                                       EmailCodeService emailCodeService, MailSender mailSender,
                                       ApplicationEventPublisher publisher) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.rememberMeTokenStore = rememberMeTokenStore;
        this.emailCodeService = emailCodeService;
        this.mailSender = mailSender;
        this.publisher = publisher;
    }

    public List<UserAdminView> list() {
        return userRepository.findAll().stream().map(UserAdminView::from).toList();
    }

    @Transactional
    public UserAdminView approve(Long id) {
        return mutate(id, User::approve);
    }

    @Transactional
    public UserAdminView reject(Long id) {
        return mutate(id, User::reject);
    }

    @Transactional
    public UserAdminView enable(Long id) {
        return mutate(id, User::enable);
    }

    @Transactional
    public UserAdminView disable(Long id) {
        return mutate(id, User::disable);
    }

    @Transactional
    public UserAdminView resetPassword(Long id, String newPassword) {
        PasswordPolicy.validate(newPassword);
        UserAdminView view = mutate(id, u -> u.withPassword(passwordEncoder.encode(newPassword)));
        // 密码已换，该用户所有 remember-me 令牌必须失效，否则旧令牌仍可免密登录
        rememberMeTokenStore.removeUserTokens(view.username());
        return view;
    }

    /** FR-C1/C2：管理员代填即视为已验证（仅 USER 行）；保存后尽力而为发告知邮件（NFR-2，失败仅日志不影响绑定）。 */
    @Transactional
    public UserAdminView setEmail(Long id, String email) {
        String normalized = normalize(email);
        User target = requireUser(id);
        // ADMIN 保护先于幂等比较与占用校验（FR-C1 仅 USER 行可代填）
        if (target.role() == UserRole.ADMIN) {
            throw new UserException(UserErrorCode.FORBIDDEN, "不能对管理员账号执行此操作");
        }
        // 幂等重放：现邮箱归一化后与入参相同则原样返回，不校验占用、不重绑、不发告知邮件
        if (Objects.equals(target.email(), normalized)) {
            return UserAdminView.from(target);
        }
        emailCodeService.assertEmailAvailable(normalized);
        UserAdminView view = mutate(id, u -> u.bindEmail(normalized));
        try {
            mailSender.send(view.email(), "九和投资邮箱绑定通知",
                    "管理员已将本邮箱绑定至九和投资账号 " + view.username() + "。此后可通过本邮箱自助重置密码。若非本人知晓，请联系管理员。");
        } catch (Exception e) {
            log.warn("邮箱绑定告知邮件发送失败（已存库不影响绑定）", e);
        }
        return view;
    }

    private UserAdminView mutate(Long id, java.util.function.Function<User, User> fn) {
        User user = requireUser(id);
        if (user.role() == UserRole.ADMIN) {
            throw new UserException(UserErrorCode.FORBIDDEN, "不能对管理员账号执行此操作");
        }
        User updated = userRepository.save(fn.apply(user));
        // canLogin 判定输入（status/enabled）变化才发事件：ActiveUserStatusCache 逐出重查，
        // 密码/邮箱等不影响登录资格的写路径不发（B9）。
        if (updated.status() != user.status() || updated.enabled() != user.enabled()) {
            publisher.publishEvent(new UserStatusChangedEvent(updated.username()));
        }
        return UserAdminView.from(updated);
    }

    private User requireUser(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new UserException(UserErrorCode.USER_NOT_FOUND, "用户不存在"));
    }

    /** 邮箱归一化：trim + 小写（与 EmailCodeService 同规则，比较/校验/绑定/发信统一使用归一化值）。 */
    private static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
