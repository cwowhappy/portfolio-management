package com.portfolio.invest.application.auth;

import com.portfolio.invest.domain.user.PasswordPolicy;
import com.portfolio.invest.domain.user.User;
import com.portfolio.invest.domain.user.UserException;
import com.portfolio.invest.domain.user.UserRepository;
import com.portfolio.invest.domain.user.UserStatus;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthApplicationService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthApplicationService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public UserView register(RegisterCommand cmd) {
        PasswordPolicy.validate(cmd.password());
        if (cmd.username() == null || cmd.username().isBlank()) {
            throw new UserException("INVALID_USERNAME", "用户名不能为空");
        }
        String username = cmd.username().trim();
        if (username.length() > 64) {
            throw new UserException("INVALID_USERNAME", "用户名最长64个字符");
        }
        Optional<User> existing = userRepository.findByUsername(username);
        if (existing.isPresent() && existing.get().status() != UserStatus.REJECTED) {
            throw new UserException("USERNAME_TAKEN", "用户名已存在");
        }
        String hash = passwordEncoder.encode(cmd.password());
        User saved = existing
                .map(u -> userRepository.save(u.reRegister(hash)))
                .orElseGet(() -> userRepository.save(User.register(username, hash)));
        return UserView.from(saved);
    }
}
