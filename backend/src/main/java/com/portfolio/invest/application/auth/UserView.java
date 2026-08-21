package com.portfolio.invest.application.auth;

import com.portfolio.invest.domain.user.User;
import com.portfolio.invest.domain.user.UserRole;
import com.portfolio.invest.domain.user.UserStatus;
import java.time.Instant;

public record UserView(Long id, String username, UserRole role, UserStatus status,
                       boolean enabled, Instant createdAt) {
    public static UserView from(User u) {
        return new UserView(u.id(), u.username(), u.role(), u.status(), u.enabled(), u.createdAt());
    }
}
