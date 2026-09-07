package com.portfolio.invest.domain.skill;

import java.util.List;
import java.util.Optional;

public interface SkillConfigRepository {
    List<SkillUserConfig> findByUserId(Long userId);
    Optional<SkillUserConfig> findByUserIdAndSkillCode(Long userId, String skillCode);
    SkillUserConfig save(SkillUserConfig config);
}
