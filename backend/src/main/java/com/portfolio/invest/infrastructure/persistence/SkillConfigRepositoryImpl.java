package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.skill.SkillConfigRepository;
import com.portfolio.invest.domain.skill.SkillUserConfig;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class SkillConfigRepositoryImpl implements SkillConfigRepository {
    private final SkillUserConfigJpaRepository jpa;

    public SkillConfigRepositoryImpl(SkillUserConfigJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override public List<SkillUserConfig> findByUserId(Long userId) {
        return jpa.findByUserId(userId).stream().map(SkillUserConfigJpaEntity::toDomain).toList();
    }
    @Override public Optional<SkillUserConfig> findByUserIdAndSkillCode(Long userId, String skillCode) {
        return jpa.findByUserIdAndSkillCode(userId, skillCode).map(SkillUserConfigJpaEntity::toDomain);
    }
    @Override public SkillUserConfig save(SkillUserConfig config) {
        return jpa.save(SkillUserConfigJpaEntity.fromDomain(config)).toDomain();
    }
}
