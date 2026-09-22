package com.portfolio.invest.application.wiki;

import com.portfolio.invest.domain.wiki.WikiEntry;
import com.portfolio.invest.domain.wiki.WikiEntryRepository;
import com.portfolio.invest.domain.wiki.WikiEntryType;
import com.portfolio.invest.domain.wiki.WikiErrorCode;
import com.portfolio.invest.domain.wiki.WikiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WikiApplicationServiceTest {

    private final WikiEntryRepository repo = mock(WikiEntryRepository.class);
    private final com.portfolio.invest.domain.wiki.WikiSeedStateRepository seedStateRepo = org.mockito.Mockito.mock(com.portfolio.invest.domain.wiki.WikiSeedStateRepository.class);
    private WikiApplicationService service;

    @BeforeEach
    void setUp() {
        service = new WikiApplicationService(repo, new PresetConceptCatalog(), seedStateRepo);
    }

    private static WikiEntry entry(long id, WikiEntryType type) {
        return WikiEntry.reconstitute(id, 1L, type, "标题" + id, "内容" + id,
                type == WikiEntryType.CONCEPT ? "质量" : null,
                type == WikiEntryType.RESEARCH_NOTE ? "801120" : null,
                java.time.Instant.now(), java.time.Instant.now(), 0L);
    }

    @DisplayName("entries 委托仓库（Task 4 将扩展 seeding，本测固定 null type 全量行为）")
    @Test
    void givenEntries_whenList_thenReturnViews() {
        when(repo.findByUserId(1L, WikiEntryType.BOOK_NOTE)).thenReturn(List.of(entry(5L, WikiEntryType.BOOK_NOTE)));
        var views = service.entries(1L, WikiEntryType.BOOK_NOTE);
        assertThat(views).hasSize(1);
        assertThat(views.get(0).title()).isEqualTo("标题5");
    }

    @DisplayName("创建条目：trim 标题后保存返回视图")
    @Test
    void givenCommand_whenCreate_thenSaveAndReturnView() {
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var view = service.createEntry(1L, new CreateWikiEntryCommand(
                WikiEntryType.BOOK_NOTE, "  笔记标题 ", "内容", null, null));
        assertThat(view.title()).isEqualTo("笔记标题");
        verify(repo).save(any(WikiEntry.class));
    }

    @DisplayName("getEntry 归属校验：他人条目抛 NOT_FOUND")
    @Test
    void givenOthersEntry_whenGet_thenThrowNotFound() {
        when(repo.findByIdAndUserId(9L, 1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getEntry(1L, 9L))
                .isInstanceOfSatisfying(WikiException.class,
                        e -> assertThat(e.code()).isEqualTo(WikiErrorCode.NOT_FOUND));
    }

    @DisplayName("更新条目：reconstitute 后 update 保存")
    @Test
    void givenOwnedEntry_whenUpdate_thenSaved() {
        WikiEntry existing = entry(5L, WikiEntryType.CONCEPT);
        when(repo.findByIdAndUserId(5L, 1L)).thenReturn(Optional.of(existing));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var view = service.updateEntry(1L, 5L,
                new UpdateWikiEntryCommand("新标题", "新内容", "估值", null));
        assertThat(view.title()).isEqualTo("新标题");
    }

    @DisplayName("删除条目：先归属校验再删")
    @Test
    void givenOwnedEntry_whenDelete_thenDeleted() {
        when(repo.findByIdAndUserId(5L, 1L)).thenReturn(Optional.of(entry(5L, WikiEntryType.BOOK_NOTE)));
        service.deleteEntry(1L, 5L);
        verify(repo).deleteById(5L);
    }

    // —— seeding（Task 4）——

    @DisplayName("首次拉取概念：插入预置 + 写标记")
    @Test
    void givenNoSeedMarker_whenListConcepts_thenSeedPresetAndMark() {
        WikiApplicationService seeded = new WikiApplicationService(repo,
                new PresetConceptCatalog(), seedStateRepo);
        when(seedStateRepo.existsByUserId(1L)).thenReturn(false);
        when(repo.findByUserId(1L, WikiEntryType.CONCEPT)).thenReturn(java.util.List.of());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        seeded.entries(1L, WikiEntryType.CONCEPT);

        verify(repo, org.mockito.Mockito.times(10)).save(any(WikiEntry.class));
        verify(seedStateRepo).insert(1L);
    }

    @DisplayName("已有标记：不重复 seeding")
    @Test
    void givenSeedMarkerExists_whenListConcepts_thenNoSeed() {
        WikiApplicationService seeded = new WikiApplicationService(repo,
                new PresetConceptCatalog(), seedStateRepo);
        when(seedStateRepo.existsByUserId(1L)).thenReturn(true);
        when(repo.findByUserId(1L, WikiEntryType.CONCEPT)).thenReturn(java.util.List.of());

        seeded.entries(1L, WikiEntryType.CONCEPT);

        verify(repo, org.mockito.Mockito.never()).save(any());
        verify(seedStateRepo, org.mockito.Mockito.never()).insert(1L);
    }

    @DisplayName("非概念类型不触发 seeding")
    @Test
    void givenBookNote_whenList_thenNoSeedCheck() {
        WikiApplicationService seeded = new WikiApplicationService(repo,
                new PresetConceptCatalog(), seedStateRepo);
        when(repo.findByUserId(1L, WikiEntryType.BOOK_NOTE)).thenReturn(java.util.List.of());

        seeded.entries(1L, WikiEntryType.BOOK_NOTE);

        verify(seedStateRepo, org.mockito.Mockito.never()).existsByUserId(1L);
    }
}
