package com.portfolio.invest.application.useradmin;

import com.portfolio.invest.domain.user.User;

public record UserAdminView(Long id, String username, String role, String status, boolean enabled) {
    public static UserAdminView from(User u) {
        return new UserAdminView(u.id(), u.username(), u.role().name(), u.status().name(), u.enabled());
    }
}
