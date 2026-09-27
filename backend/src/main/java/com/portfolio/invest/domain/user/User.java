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
    private final String email;
    private final boolean emailVerified;
    private final Instant createdAt;
    private final Instant updatedAt;

    private User(Long id, String username, String passwordHash, UserRole role,
                 UserStatus status, boolean enabled, String email, boolean emailVerified,
                 Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.status = status;
        this.enabled = enabled;
        this.email = email;
        this.emailVerified = emailVerified;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static User register(String username, String passwordHash) {
        return register(username, passwordHash, null);
    }

    /** 注册可携带邮箱：非空即视为已验证（注册流程邮箱来自用户自有输入，验证码另行发送）。 */
    public static User register(String username, String passwordHash, String email) {
        return new User(null, username, passwordHash, UserRole.USER,
                UserStatus.PENDING, true, email, email != null, Instant.now(), Instant.now());
    }

    /** 从持久化还原：不做状态校验（语义不同于 register 的业务注册）。 */
    public static User reconstitute(Long id, String username, String passwordHash, UserRole role,
                                    UserStatus status, boolean enabled, Instant createdAt, Instant updatedAt) {
        return reconstitute(id, username, passwordHash, role, status, enabled, null, false, createdAt, updatedAt);
    }

    public static User reconstitute(Long id, String username, String passwordHash, UserRole role,
                                    UserStatus status, boolean enabled, String email, boolean emailVerified,
                                    Instant createdAt, Instant updatedAt) {
        return new User(id, username, passwordHash, role, status, enabled, email, emailVerified, createdAt, updatedAt);
    }

    /** 被拒用户重新注册：复用同一行，换密码、恢复 PENDING。 */
    public User reRegister(String passwordHash) {
        return reRegister(passwordHash, this.email);
    }

    public User reRegister(String passwordHash, String email) {
        requireStatus(UserStatus.REJECTED, "状态非拒绝，仅被拒绝用户可重新注册");
        return new User(id, username, passwordHash, UserRole.USER,
                UserStatus.PENDING, true, email, email != null, createdAt, Instant.now());
    }

    /** 管理员代填/注册流程绑定邮箱：绑定即视为已验证。 */
    public User bindEmail(String email) {
        return new User(id, username, passwordHash, role, status, enabled, email, true, createdAt, Instant.now());
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
        return new User(id, username, passwordHash, role, status, true, email, emailVerified, createdAt, Instant.now());
    }

    public User disable() {
        requireStatus(UserStatus.APPROVED, "状态非已通过，仅已通过用户可启用/停用");
        return new User(id, username, passwordHash, role, status, false, email, emailVerified, createdAt, Instant.now());
    }

    public User withPassword(String passwordHash) {
        return new User(id, username, passwordHash, role, status, enabled, email, emailVerified, createdAt, Instant.now());
    }

    public User withId(Long id) {
        return new User(id, username, passwordHash, role, status, enabled, email, emailVerified, createdAt, updatedAt);
    }

    public boolean canLogin() {
        return status == UserStatus.APPROVED && enabled;
    }

    private User withStatus(UserStatus s) {
        return new User(id, username, passwordHash, role, s, enabled, email, emailVerified, createdAt, Instant.now());
    }

    private void requireStatus(UserStatus expected, String message) {
        if (status != expected) {
            throw new UserException(UserErrorCode.INVALID_STATE, message);
        }
    }

    public Long id() { return id; }
    public String username() { return username; }
    public String passwordHash() { return passwordHash; }
    public UserRole role() { return role; }
    public UserStatus status() { return status; }
    public boolean enabled() { return enabled; }
    public String email() { return email; }
    public boolean emailVerified() { return emailVerified; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
}
