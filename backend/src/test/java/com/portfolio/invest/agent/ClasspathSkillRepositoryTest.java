package com.portfolio.invest.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ClasspathSkillRepositoryTest {

    @DisplayName("从 classpath 枚举 test-skills 并读取 frontmatter 元数据")
    @Test
    void givenTestSkills_whenEnumerate_thenNamesAndMetadataLoaded() throws Exception {
        try (ClasspathSkillRepository repo = new ClasspathSkillRepository("test-skills")) {
            List<String> names = repo.getAllSkillNames();
            assertThat(names).containsExactlyInAnyOrder("tushare_data", "wind_finance");

            AgentSkill skill = repo.getSkill("tushare_data");
            assertThat(skill.getDescription()).isNotBlank();
            assertThat(skill.getMetadata()).containsEntry("category", "data_source");
            assertThat(skill.getMetadata()).containsEntry("default_enabled", false);
            assertThat(skill.getMetadata()).containsEntry("depends_on_provider", "tushare");
        }
    }
}
