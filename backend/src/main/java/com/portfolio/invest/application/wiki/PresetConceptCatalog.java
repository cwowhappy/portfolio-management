package com.portfolio.invest.application.wiki;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.springframework.stereotype.Component;

/** 预置概念目录：classpath 资源随 jar 分发（Skill 域同款先例），构造时一次加载。 */
@Component
public class PresetConceptCatalog {

    public record PresetConcept(String title, String category, String content) {}

    private final List<PresetConcept> concepts;

    public PresetConceptCatalog() {
        try (var in = getClass().getResourceAsStream("/wiki/preset-concepts.json")) {
            if (in == null) {
                throw new IllegalStateException("预置概念资源缺失：/wiki/preset-concepts.json");
            }
            this.concepts = new ObjectMapper().readValue(in, new TypeReference<>() {});
        } catch (IOException e) {
            throw new UncheckedIOException("预置概念资源解析失败", e);
        }
    }

    public List<PresetConcept> concepts() {
        return concepts;
    }
}
