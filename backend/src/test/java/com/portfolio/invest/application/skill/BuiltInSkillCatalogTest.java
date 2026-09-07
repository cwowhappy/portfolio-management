package com.portfolio.invest.application.skill;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BuiltInSkillCatalogTest {

    @DisplayName("main classpath 内置 2 个 skill 且 frontmatter 元数据齐全")
    @Test
    void givenMainResources_whenEnumerate_thenTwoSkillsWithMetadata() throws Exception {
        try (ClasspathSkillRepository repo = new ClasspathSkillRepository("skills")) {
            assertThat(repo.getAllSkillNames()).containsExactlyInAnyOrder("tushare_data", "wind_finance");

            var tushare = repo.getSkill("tushare_data");
            assertThat(tushare.getMetadata()).containsEntry("category", "data_source");
            assertThat(tushare.getMetadata()).containsEntry("default_enabled", false);
            assertThat(tushare.getMetadata()).containsEntry("depends_on_provider", "tushare");

            var wind = repo.getSkill("wind_finance");
            assertThat(wind.getMetadata()).containsEntry("depends_on_provider", "wind");
        }
    }
}
