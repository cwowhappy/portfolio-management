package com.portfolio.invest.application.wiki;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PresetConceptCatalogTest {

    @DisplayName("预置目录加载：10 条、字段齐全、术语唯一")
    @Test
    void whenLoad_thenTenConceptsWithUniqueTitles() {
        PresetConceptCatalog catalog = new PresetConceptCatalog();
        var concepts = catalog.concepts();
        assertThat(concepts).hasSize(10);
        assertThat(concepts).allSatisfy(c -> {
            assertThat(c.title()).isNotBlank();
            assertThat(c.category()).isNotBlank();
            assertThat(c.content()).isNotBlank();
        });
        assertThat(concepts.stream().map(PresetConceptCatalog.PresetConcept::title).distinct()).hasSize(10);
    }
}
