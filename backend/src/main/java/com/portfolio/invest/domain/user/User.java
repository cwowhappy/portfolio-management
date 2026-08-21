package com.portfolio.invest.domain.user;

import java.time.Instant;

/** 用户聚合根：纯业务，零 Spring/JPA 依赖。状态转移返回新实例。 */
public final class User {

    private final Long id;
    private final String username;
    private final String passwordHash;
    private final UserRole role;
    private final UserStatus status;
    private final boolean enabled;
    private final Instant createdAt;
    private final Instant updatedAt;

    private User(Long id, String username, String passwordHash, UserRole role,
                 UserStatus status, boolean enabled, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.status = status;
        this.enabled = enabled;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static User register(String username, String passwordHash) {
        return new User(null, username, passwordHash, UserRole.USER,
                UserStatus.PENDING, true, Instant.now(), Instant.now());
    }

    /** 被拒用户重新注册：复用同一行，换密码、恢复 PENDING。 */
    public User reRegister(String passwordHash) {
        requireStatus(UserStatus.REJECTED, "状态非拒绝，仅被拒绝用户可重新注册");
        return new User(id, username, passwordHash, UserRole.USER,
                UserStatus.PENDING, true, createdAt, Instant.now());
    }

    public User approve() {
        requireStatus(UserStatus.PENDING, "状态非待审核，仅待审核用户可通过");
        return withStatus(UserStatus.APPROVED);
    }

    public User reject() {
        requireStatus(UserStatus.PENDING, "状态非待审核，仅待审核用户可拒绝");
        return withStatus(UserStatus.REJECTED);
    }

    public User enable() {
        requireStatus(UserStatus.APPROVED, "状态非已通过，仅已通过用户可启用/停用");
        return new User(id, username, passwordHash, role, status, true, createdAt, Instant.now());
    }

    public User disable() {
        requireStatus(UserStatus.APPROVED, "状态非已通过，仅已通过用户可启用/停用");
        return new User(id, username, passwordHash, role, status, false, createdAt, Instant.now());
    }

    public User withPassword(String passwordHash) {
        return new User(id, username, passwordHash, role, status, enabled, createdAt, Instant.now());
    }

    public User withId(Long id) {
        return new User(id, username, passwordHash, role, status, enabled, createdAt, updatedAt);
    }

    public boolean canLogin() {
        return status == UserStatus.APPROVED && enabled;
    }

    private User withStatus(UserStatus s) {
        return new User(id, username, passwordHash, role, s, enabled, createdAt, Instant.now());
    }

    private void requireStatus(UserStatus expected, String message) {
        if (status != expected) {
            throw new UserException("INVALID_STATE", message);
        }
    }

    public Long id() { return id; }
    public String username() { return username; }
    public String passwordHash() { return passwordHash; }
    public UserRole role() { return role; }
    public UserStatus status() { return status; }
    public boolean enabled() { return enabled; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
}
