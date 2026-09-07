package com.portfolio.invest.application.skill;

import com.portfolio.invest.domain.skill.SkillConfigRepository;
import com.portfolio.invest.domain.skill.SkillUserConfig;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Skill 目录与用户配置用例：目录来自 classpath，用户选择落 skill_user_config。 */
@Service
public class SkillApplicationService {
    private final SkillConfigRepository repository;
    private final ClasspathSkillRepository builtInSkills;

    public SkillApplicationService(SkillConfigRepository repository, ClasspathSkillRepository builtInSkills) {
        this.repository = repository;
        this.builtInSkills = builtInSkills;
    }

    @Transactional(readOnly = true)
    public List<SkillView> catalog(Long userId) {
        Map<String, Boolean> choices = choices(userId);
        return builtInSkills.getAllSkills().stream()
                .map(s -> SkillView.from(s, effective(s, choices)))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<String> enabledSkillCodes(Long userId) {
        Map<String, Boolean> choices = choices(userId);
        List<String> result = new ArrayList<>();
        for (AgentSkill s : builtInSkills.getAllSkills()) {
            if (effective(s, choices)) result.add(s.getName());
        }
        return result;
    }

    @Transactional
    public List<SkillView> save(Long userId, List<String> enabledCodes) {
        Set<String> enabled = enabledCodes == null ? Set.of() : new HashSet<>(enabledCodes);
        Instant now = Instant.now();
        for (AgentSkill s : builtInSkills.getAllSkills()) {
            String code = s.getName();
            boolean want = enabled.contains(code);
            SkillUserConfig existing = repository.findByUserIdAndSkillCode(userId, code).orElse(null);
            repository.save(existing == null
                    ? SkillUserConfig.create(userId, code, want, now)
                    : existing.update(want, now));
        }
        return catalog(userId);
    }

    private Map<String, Boolean> choices(Long userId) {
        return repository.findByUserId(userId).stream()
                .collect(Collectors.toMap(SkillUserConfig::skillCode, SkillUserConfig::enabled));
    }

    private boolean effective(AgentSkill s, Map<String, Boolean> choices) {
        Boolean choice = choices.get(s.getName());
        return choice != null ? choice : defaultEnabled(s);
    }

    private static boolean defaultEnabled(AgentSkill s) {
        Object v = s.getMetadata().get("default_enabled");
        return v instanceof Boolean b && b;
    }
}
