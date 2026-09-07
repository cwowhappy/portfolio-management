package com.portfolio.invest.application.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.invest.domain.skill.SkillConfigRepository;
import com.portfolio.invest.domain.skill.SkillUserConfig;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SkillApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-07T08:00:00Z");

    private final SkillConfigRepository repo = mock(SkillConfigRepository.class);
    private final ClasspathSkillRepository builtInSkills = mock(ClasspathSkillRepository.class);
    private SkillApplicationService service;

    @BeforeEach
    void setUp() {
        service = new SkillApplicationService(repo, builtInSkills);
    }

    private static AgentSkill skill(String name, boolean defaultEnabled) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("name", name);
        meta.put("description", name + " 描述");
        meta.put("category", "data_source");
        meta.put("default_enabled", defaultEnabled);
        meta.put("depends_on_provider", name.startsWith("tushare") ? "tushare" : "wind");
        return new AgentSkill(meta, "# " + name, Map.of(), "test");
    }

    @DisplayName("无用户配置时按 default_enabled 回落")
    @Test
    void givenNoUserConfig_whenCatalog_thenFallbackToDefaultEnabled() {
        when(builtInSkills.getAllSkills()).thenReturn(List.of(skill("tushare_data", false), skill("wind_finance", false)));
        when(repo.findByUserId(1L)).thenReturn(List.of());

        var catalog = service.catalog(1L);

        assertThat(catalog).extracting(SkillView::skillCode).containsExactlyInAnyOrder("tushare_data", "wind_finance");
        assertThat(catalog).allSatisfy(v -> assertThat(v.enabled()).isFalse());
        assertThat(catalog).allSatisfy(v -> assertThat(v.defaultEnabled()).isFalse());
    }

    @DisplayName("用户显式启用覆盖 default_enabled")
    @Test
    void givenUserConfig_whenCatalog_thenOverrideDefault() {
        when(builtInSkills.getAllSkills()).thenReturn(List.of(skill("tushare_data", false), skill("wind_finance", false)));
        when(repo.findByUserId(1L)).thenReturn(List.of(
                SkillUserConfig.reconstitute(9L, 1L, "tushare_data", true, NOW)));

        var catalog = service.catalog(1L);
        var tushare = catalog.stream().filter(v -> v.skillCode().equals("tushare_data")).findFirst().orElseThrow();

        assertThat(tushare.enabled()).isTrue();
        assertThat(tushare.dependsOnProvider()).isEqualTo("tushare");
    }

    @DisplayName("enabledSkillCodes 只返回生效启用的 code")
    @Test
    void givenUserConfig_whenEnabledSkillCodes_thenOnlyEnabledReturned() {
        when(builtInSkills.getAllSkills()).thenReturn(List.of(skill("tushare_data", false), skill("wind_finance", false)));
        when(repo.findByUserId(1L)).thenReturn(List.of(
                SkillUserConfig.reconstitute(9L, 1L, "tushare_data", true, NOW)));

        assertThat(service.enabledSkillCodes(1L)).containsExactly("tushare_data");
    }

    @DisplayName("save 对每个内置 skill 做启停 upsert")
    @Test
    void givenSave_whenEnabledSet_thenUpsertEachSkill() {
        when(builtInSkills.getAllSkills()).thenReturn(List.of(skill("tushare_data", false), skill("wind_finance", false)));
        when(repo.findByUserIdAndSkillCode(any(), any())).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repo.findByUserId(1L)).thenReturn(List.of());

        service.save(1L, List.of("tushare_data"));

        verify(repo).save(argThat(c -> c.skillCode().equals("tushare_data") && c.enabled()));
        verify(repo).save(argThat(c -> c.skillCode().equals("wind_finance") && !c.enabled()));
    }
}
