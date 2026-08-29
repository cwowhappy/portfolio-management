package com.portfolio.invest.application.useradmin;

import com.portfolio.invest.domain.user.PasswordPolicy;
import com.portfolio.invest.domain.user.RememberMeTokenStore;
import com.portfolio.invest.domain.user.User;
import com.portfolio.invest.domain.user.UserException;
import com.portfolio.invest.domain.user.UserRepository;
import com.portfolio.invest.domain.user.UserRole;
import java.util.List;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserAdminApplicationService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RememberMeTokenStore rememberMeTokenStore;

    public UserAdminApplicationService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                                       RememberMeTokenStore rememberMeTokenStore) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.rememberMeTokenStore = rememberMeTokenStore;
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

    private UserAdminView mutate(Long id, java.util.function.Function<User, User> fn) {
        User user = requireUser(id);
        if (user.role() == UserRole.ADMIN) {
            throw new UserException("FORBIDDEN", "不能对管理员账号执行此操作");
        }
        return UserAdminView.from(userRepository.save(fn.apply(user)));
    }

    private User requireUser(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new UserException("USER_NOT_FOUND", "用户不存在"));
    }
}
