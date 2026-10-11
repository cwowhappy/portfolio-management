package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.memory.UserProfileConfig;
import com.portfolio.invest.domain.memory.UserProfileConfigRepository;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class UserProfileConfigRepositoryImpl implements UserProfileConfigRepository {
    private final UserProfileConfigJpaRepository jpa;

    public UserProfileConfigRepositoryImpl(UserProfileConfigJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override public Optional<UserProfileConfig> findByUserId(Long userId) {
        return jpa.findByUserId(userId).map(UserProfileConfigJpaEntity::toDomain);
    }

    @Override public UserProfileConfig save(UserProfileConfig config) {
        return jpa.save(UserProfileConfigJpaEntity.fromDomain(config)).toDomain();
    }
}
