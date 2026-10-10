package com.portfolio.invest.infrastructure.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserProfileConfigJpaRepository extends JpaRepository<UserProfileConfigJpaEntity, Long> {

    Optional<UserProfileConfigJpaEntity> findByUserId(Long userId);
}
