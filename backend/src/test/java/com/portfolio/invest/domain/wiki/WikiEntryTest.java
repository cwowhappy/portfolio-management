package com.portfolio.invest.domain.wiki;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WikiEntryTest {

    private static final Instant NOW = Instant.parse("2026-09-22T00:00:00Z");

    @DisplayName("创建读书笔记：类型特有列可空")
    @Test
    void givenBookNote_whenCreate_thenSucceed() {
        WikiEntry e = WikiEntry.create(1L, WikiEntryType.BOOK_NOTE, "《聪明的投资者》笔记",
                "## 核心\n- 市场先生", null, null, NOW);
        assertThat(e.id()).isNull();
        assertThat(e.userId()).isEqualTo(1L);
        assertThat(e.type()).isEqualTo(WikiEntryType.BOOK_NOTE);
        assertThat(e.createdAt()).isEqualTo(NOW);
        assertThat(e.updatedAt()).isEqualTo(NOW);
    }

    @DisplayName("创建研究结论：带行业代码与分类可空列")
    @Test
    void givenResearchNote_whenCreate_thenIndustryCodeKept() {
        WikiEntry e = WikiEntry.create(1L, WikiEntryType.RESEARCH_NOTE, "白酒行业研究结论",
                "景气上行", null, "801120", NOW);
        assertThat(e.industryCode()).isEqualTo("801120");
    }

    @DisplayName("标题空/超200字抛INVALID_INPUT")
    @Test
    void givenBlankOrOverlongTitle_whenCreate_thenThrow() {
        assertThatThrownBy(() -> WikiEntry.create(1L, WikiEntryType.CONCEPT, " ", "内容", null, null, NOW))
                .isInstanceOfSatisfying(WikiException.class,
                        e -> assertThat(e.code()).isEqualTo(WikiErrorCode.INVALID_INPUT));
        assertThatThrownBy(() -> WikiEntry.create(1L, WikiEntryType.CONCEPT, "标".repeat(201), "内容", null, null, NOW))
                .isInstanceOfSatisfying(WikiException.class,
                        e -> assertThat(e.code()).isEqualTo(WikiErrorCode.INVALID_INPUT));
    }

    @DisplayName("内容空抛INVALID_INPUT")
    @Test
    void givenBlankContent_whenCreate_thenThrow() {
        assertThatThrownBy(() -> WikiEntry.create(1L, WikiEntryType.CONCEPT, "护城河", "", null, null, NOW))
                .isInstanceOfSatisfying(WikiException.class,
                        e -> assertThat(e.code()).isEqualTo(WikiErrorCode.INVALID_INPUT));
    }

    @DisplayName("update 返回新实例，type 与 id 不变，createdAt 保留")
    @Test
    void givenEntry_whenUpdate_thenNewInstance() {
        WikiEntry original = WikiEntry.reconstitute(5L, 1L, WikiEntryType.CONCEPT, "护城河", "旧内容",
                "质量", null, NOW, NOW, 0L);
        WikiEntry updated = original.update("护城河（修订）", "新内容", "质量", null);
        assertThat(updated).isNotSameAs(original);
        assertThat(updated.id()).isEqualTo(5L);
        assertThat(updated.type()).isEqualTo(WikiEntryType.CONCEPT);
        assertThat(updated.createdAt()).isEqualTo(NOW);
        assertThat(updated.title()).isEqualTo("护城河（修订）");
        assertThat(original.title()).isEqualTo("护城河"); // 不可变
    }

    // —— 研究项目软引用（V2 D12，照 JournalEntry.projectId 先例）——

    @DisplayName("带 projectId 的 create 重载：回流条目携带软引用；旧签名 projectId=null")
    @Test
    void givenProjectId_whenCreateOverload_thenProjectIdKept() {
        WikiEntry linked = WikiEntry.create(1L, WikiEntryType.RESEARCH_NOTE, "复盘回流", "结论",
                "SOP_REVIEW", null, NOW, 5L);
        assertThat(linked.projectId()).isEqualTo(5L);

        WikiEntry plain = WikiEntry.create(1L, WikiEntryType.RESEARCH_NOTE, "复盘回流", "结论",
                "SOP_REVIEW", null, NOW);
        assertThat(plain.projectId()).isNull();
    }

    @DisplayName("带 projectId 的 reconstitute 重载：旧签名 projectId=null；update 保留软引用")
    @Test
    void givenProjectId_whenReconstituteAndUpdate_thenProjectIdPreserved() {
        WikiEntry restored = WikiEntry.reconstitute(9L, 1L, WikiEntryType.RESEARCH_NOTE, "复盘回流", "结论",
                "SOP_REVIEW", null, NOW, NOW, 0L, 5L);
        assertThat(restored.projectId()).isEqualTo(5L);

        WikiEntry legacy = WikiEntry.reconstitute(9L, 1L, WikiEntryType.RESEARCH_NOTE, "复盘回流", "结论",
                "SOP_REVIEW", null, NOW, NOW, 0L);
        assertThat(legacy.projectId()).isNull();

        WikiEntry updated = restored.update("复盘回流（改）", "结论2", "SOP_REVIEW", null);
        assertThat(updated.projectId()).isEqualTo(5L); // projectId 不可变，update 保留
    }
}
