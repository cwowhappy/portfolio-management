package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.user.User;
import com.portfolio.invest.domain.user.UserRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepositoryImpl implements UserRepository {

    private final UserJpaRepository jpa;

    public UserRepositoryImpl(UserJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<User> findByUsername(String username) {
        return jpa.findByUsername(username).map(UserJpaEntity::toDomain);
    }

    @Override
    public Optional<User> findById(Long id) {
        return jpa.findById(id).map(UserJpaEntity::toDomain);
    }

    @Override
    public List<User> findAll() {
        return jpa.findAll().stream().map(UserJpaEntity::toDomain).toList();
    }

    @Override
    public User save(User user) {
        // saveAndFlush：让唯一索引等约束违例在事务内尽早抛出，
        // 供应用层捕获映射（如并发注册 → USERNAME_TAKEN），而不是延迟到提交时冒泡成 500
        UserJpaEntity entity = jpa.saveAndFlush(UserJpaEntity.fromDomain(user));
        return entity.toDomain();
    }
}
