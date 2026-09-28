package com.portfolio.invest.application.wiki;

import com.portfolio.invest.domain.wiki.WikiEntry;
import com.portfolio.invest.domain.wiki.WikiEntryRepository;
import com.portfolio.invest.domain.wiki.WikiEntryType;
import com.portfolio.invest.domain.wiki.WikiSeedStateRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SOP 模板 seeding 切片测试（照 WikiApplicationServiceTest 概念 seeding 先例：mock 仓库断言 save 次数）。
 *
 * <p>内容口径：F01 定稿 50 条（Ruling-7 以表格行为准——新分析 10 / 策略 17 / 纪律 11 / 复盘 12，3.9 不入）；
 * 只断言结构与键（类型/category/title 前缀/分布），不断言内容文本。
 */
class WikiSopSeedingTest {

    private final WikiEntryRepository repo = mock(WikiEntryRepository.class);
    private final WikiSeedStateRepository seedStateRepo = mock(WikiSeedStateRepository.class);
    private WikiApplicationService service;

    @BeforeEach
    void setUp() {
        service = new WikiApplicationService(repo, new PresetConceptCatalog(), seedStateRepo);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @DisplayName("老用户首次拉取研究笔记：写 50 条 SOP 模板（10/17/11/12）+ 置独立标记，不动概念")
    @Test
    void givenConceptSeededUser_whenFirstListResearchNotes_thenSeedFiftyTemplatesAndMarkSop() {
        when(seedStateRepo.existsByUserId(1L)).thenReturn(true); // 概念已 seed（老用户）
        when(seedStateRepo.isSopSeeded(1L)).thenReturn(false);
        when(repo.findByUserId(1L, WikiEntryType.RESEARCH_NOTE)).thenReturn(List.of());

        service.entries(1L, WikiEntryType.RESEARCH_NOTE);

        ArgumentCaptor<WikiEntry> captor = ArgumentCaptor.forClass(WikiEntry.class);
        verify(repo, times(50)).save(captor.capture());
        List<WikiEntry> saved = captor.getAllValues();
        assertThat(saved).allSatisfy(e -> {
            assertThat(e.type()).isEqualTo(WikiEntryType.RESEARCH_NOTE);
            assertThat(e.category()).isEqualTo("SOP_TEMPLATE");
            assertThat(e.title()).startsWith("SOP·");
        });
        // 四阶段分布照 F01 定稿（Ruling-7：以表格行为准）
        assertThat(saved.stream().filter(e -> e.title().startsWith("SOP·新分析·"))).hasSize(10);
        assertThat(saved.stream().filter(e -> e.title().startsWith("SOP·制定投资策略·"))).hasSize(17);
        assertThat(saved.stream().filter(e -> e.title().startsWith("SOP·建仓与持仓·"))).hasSize(11);
        assertThat(saved.stream().filter(e -> e.title().startsWith("SOP·复盘·"))).hasSize(12);
        assertThat(saved.stream().map(WikiEntry::title).distinct()).hasSize(50); // 模板名不重复
        verify(seedStateRepo).markSopSeeded(1L);
        verify(seedStateRepo, never()).insert(1L); // 概念标记已在，不重复置
    }

    @DisplayName("已有 SOP 标记：二次拉取零新增")
    @Test
    void givenSopSeeded_whenListResearchNotesAgain_thenNoMoreSaves() {
        when(seedStateRepo.isSopSeeded(1L)).thenReturn(true);
        when(repo.findByUserId(1L, WikiEntryType.RESEARCH_NOTE)).thenReturn(List.of());

        service.entries(1L, WikiEntryType.RESEARCH_NOTE);

        verify(repo, never()).save(any());
        verify(seedStateRepo, never()).markSopSeeded(1L);
    }

    @DisplayName("用户删光全部模板后：标记仍在（不随条目删除），零复活")
    @Test
    void givenAllTemplatesDeleted_whenListResearchNotes_thenNoResurrection() {
        when(seedStateRepo.isSopSeeded(1L)).thenReturn(true);
        when(repo.findByUserId(1L, WikiEntryType.RESEARCH_NOTE)).thenReturn(List.of()); // 已删光

        var views = service.entries(1L, WikiEntryType.RESEARCH_NOTE);

        assertThat(views).isEmpty();
        verify(repo, never()).save(any());
    }

    @DisplayName("全新用户先拉研究笔记：置 SOP 标记前先补概念预置（标记行 seeded_at 不谎报），共 60 条")
    @Test
    void givenBrandNewUser_whenFirstListResearchNotes_thenConceptsSeededFirst() {
        when(seedStateRepo.existsByUserId(1L)).thenReturn(false); // 无概念标记行
        when(seedStateRepo.isSopSeeded(1L)).thenReturn(false);
        when(repo.findByUserId(1L, WikiEntryType.RESEARCH_NOTE)).thenReturn(List.of());

        service.entries(1L, WikiEntryType.RESEARCH_NOTE);

        ArgumentCaptor<WikiEntry> captor = ArgumentCaptor.forClass(WikiEntry.class);
        verify(repo, times(60)).save(captor.capture());
        // 先 10 条概念预置、后 50 条 SOP 模板
        assertThat(captor.getAllValues().subList(0, 10))
                .allSatisfy(e -> assertThat(e.type()).isEqualTo(WikiEntryType.CONCEPT));
        assertThat(captor.getAllValues().subList(10, 60))
                .allSatisfy(e -> assertThat(e.type()).isEqualTo(WikiEntryType.RESEARCH_NOTE));
        verify(seedStateRepo).insert(1L);
        verify(seedStateRepo).markSopSeeded(1L);
    }

    @DisplayName("概念路径零扰动：拉概念不触发 SOP 标记读写")
    @Test
    void givenConceptList_whenFetch_thenSopMarkerUntouched() {
        when(seedStateRepo.existsByUserId(1L)).thenReturn(true);
        when(repo.findByUserId(1L, WikiEntryType.CONCEPT)).thenReturn(List.of());

        service.entries(1L, WikiEntryType.CONCEPT);

        verify(seedStateRepo, never()).isSopSeeded(1L);
        verify(seedStateRepo, never()).markSopSeeded(any());
        verify(repo, never()).save(any());
    }
}
