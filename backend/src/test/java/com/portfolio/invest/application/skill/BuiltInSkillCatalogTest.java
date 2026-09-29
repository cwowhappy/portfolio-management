package com.portfolio.invest.application.skill;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BuiltInSkillCatalogTest {

    @DisplayName("main classpath 内置 6 个 skill 且 frontmatter 元数据齐全")
    @Test
    void givenMainResources_whenEnumerate_thenSixSkillsWithMetadata() throws Exception {
        try (ClasspathSkillRepository repo = new ClasspathSkillRepository("skills")) {
            assertThat(repo.getAllSkillNames()).containsExactlyInAnyOrder(
                    "tushare_data", "wind_finance",
                    "sop-new-analysis", "sop-strategy", "sop-position", "sop-review");

            var tushare = repo.getSkill("tushare_data");
            assertThat(tushare.getMetadata()).containsEntry("category", "data_source");
            assertThat(tushare.getMetadata()).containsEntry("default_enabled", false);
            assertThat(tushare.getMetadata()).containsEntry("depends_on_provider", "tushare");

            var wind = repo.getSkill("wind_finance");
            assertThat(wind.getMetadata()).containsEntry("depends_on_provider", "wind");
        }
    }

    @DisplayName("四个 SOP 阶段 skill 默认停用、category 为 sop 且不依赖 MCP provider")
    @Test
    void givenSopSkills_whenReadMetadata_thenDisabledByDefaultAndNoProvider() throws Exception {
        try (ClasspathSkillRepository repo = new ClasspathSkillRepository("skills")) {
            for (String name : List.of("sop-new-analysis", "sop-strategy", "sop-position", "sop-review")) {
                var skill = repo.getSkill(name);
                assertThat(skill.getDescription()).isNotBlank();
                assertThat(skill.getMetadata()).containsEntry("category", "sop");
                assertThat(skill.getMetadata()).containsEntry("default_enabled", false);
                assertThat(skill.getMetadata()).containsEntry("depends_on_provider", "");
            }
        }
    }
}
