package com.portfolio.invest.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SkillUserConfigJpaRepository extends JpaRepository<SkillUserConfigJpaEntity, Long> {
    List<SkillUserConfigJpaEntity> findByUserId(Long userId);
    Optional<SkillUserConfigJpaEntity> findByUserIdAndSkillCode(Long userId, String skillCode);
}
