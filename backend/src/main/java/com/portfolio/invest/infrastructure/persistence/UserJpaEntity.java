package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.user.User;
import com.portfolio.invest.domain.user.UserRole;
import com.portfolio.invest.domain.user.UserStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** app_user 表的 JPA 映射（领域 User 为纯 POJO，见 domain/user）。 */
@Entity
@Table(name = "app_user")
public class UserJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserJpaEntity() {}

    static UserJpaEntity fromDomain(User u) {
        UserJpaEntity e = new UserJpaEntity();
        e.id = u.id();
        e.username = u.username();
        e.passwordHash = u.passwordHash();
        e.role = u.role();
        e.status = u.status();
        e.enabled = u.enabled();
        e.createdAt = u.createdAt();
        e.updatedAt = u.updatedAt();
        return e;
    }

    User toDomain() {
        return User.reconstitute(id, username, passwordHash, role, status, enabled, createdAt, updatedAt);
    }

    // getter 供 JPA 使用（可按需提供）
    public Long getId() { return id; }
    public String getUsername() { return username; }
    // ... 其余 getter 省略（JPA 通过字段访问亦可）
}
