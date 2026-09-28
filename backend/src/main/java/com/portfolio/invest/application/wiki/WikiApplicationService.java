package com.portfolio.invest.application.wiki;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.domain.wiki.WikiEntry;
import com.portfolio.invest.domain.wiki.WikiEntryRepository;
import com.portfolio.invest.domain.wiki.WikiEntryType;
import com.portfolio.invest.domain.wiki.WikiErrorCode;
import com.portfolio.invest.domain.wiki.WikiException;
import com.portfolio.invest.domain.wiki.WikiSeedStateRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WikiApplicationService {

    /** SOP 模板落库 category 标记（设计 S2：复用 RESEARCH_NOTE 三类型不扩枚举）。 */
    static final String SOP_CATEGORY = "SOP_TEMPLATE";

    /** 阶段键（ResearchStage 取值）→ 标题中的阶段中文名。 */
    private static final Map<String, String> SOP_STAGE_LABELS = Map.of(
            "NEW_ANALYSIS", "新分析",
            "STRATEGY", "制定投资策略",
            "POSITION", "建仓与持仓",
            "REVIEW", "复盘");

    private static final List<SopTemplate> SOP_TEMPLATES = loadSopTemplates();

    /** SOP 模板目录条目（classpath 资源 /wiki/sop-templates.json，内容源 F01 定稿 50 条）。 */
    record SopTemplate(String stage, String name, String content) {}

    private final WikiEntryRepository repository;
    private final PresetConceptCatalog presetConcepts;
    private final WikiSeedStateRepository seedStateRepository;

    public WikiApplicationService(WikiEntryRepository repository,
                                  PresetConceptCatalog presetConcepts,
                                  WikiSeedStateRepository seedStateRepository) {
        this.repository = repository;
        this.presetConcepts = presetConcepts;
        this.seedStateRepository = seedStateRepository;
    }

    /** 概念/研究笔记首次拉取触发对应预置 seeding（幂等：wiki_seed_state 各自标记列；删光不复活）。 */
    @Transactional
    public List<WikiEntryView> entries(Long userId, WikiEntryType type) {
        if (type == WikiEntryType.CONCEPT) {
            seedPresetConcepts(userId);
        } else if (type == WikiEntryType.RESEARCH_NOTE) {
            seedSopTemplates(userId);
        }
        return repository.findByUserId(userId, type).stream().map(WikiEntryView::from).toList();
    }

    private void seedPresetConcepts(Long userId) {
        if (seedStateRepository.existsByUserId(userId)) {
            return;
        }
        Instant now = Instant.now();
        for (PresetConceptCatalog.PresetConcept c : presetConcepts.concepts()) {
            repository.save(WikiEntry.create(userId, WikiEntryType.CONCEPT,
                    c.title(), c.content(), c.category(), null, now));
        }
        seedStateRepository.insert(userId);
    }

    /** SOP 模板 seeding（幂等：sop_seeded_at 独立标记列，老用户已 seed 概念不复用行存在性判定；删光不复活）。 */
    private void seedSopTemplates(Long userId) {
        if (seedStateRepository.isSopSeeded(userId)) {
            return;
        }
        // 概念预置先行补齐：markSopSeeded 落库时 seeded_at NOT NULL 必填，若概念尚未 seed，
        // 标记行会谎报概念已 seed（此后 existsByUserId 恒真、概念永不再 seed）——先走既有幂等路径。
        seedPresetConcepts(userId);
        Instant now = Instant.now();
        for (SopTemplate t : SOP_TEMPLATES) {
            repository.save(WikiEntry.create(userId, WikiEntryType.RESEARCH_NOTE,
                    "SOP·" + SOP_STAGE_LABELS.get(t.stage()) + "·" + t.name(),
                    t.content(), SOP_CATEGORY, null, now));
        }
        seedStateRepository.markSopSeeded(userId);
    }

    private static List<SopTemplate> loadSopTemplates() {
        try (var in = WikiApplicationService.class.getResourceAsStream("/wiki/sop-templates.json")) {
            if (in == null) {
                throw new IllegalStateException("SOP 模板资源缺失：/wiki/sop-templates.json");
            }
            List<SopTemplate> templates = new ObjectMapper().readValue(in, new TypeReference<>() {});
            for (SopTemplate t : templates) {
                if (!SOP_STAGE_LABELS.containsKey(t.stage())) {
                    throw new IllegalStateException("SOP 模板未知阶段：" + t.stage() + "（" + t.name() + "）");
                }
            }
            return List.copyOf(templates);
        } catch (IOException e) {
            throw new UncheckedIOException("SOP 模板资源解析失败", e);
        }
    }

    @Transactional
    public WikiEntryView createEntry(Long userId, CreateWikiEntryCommand cmd) {
        WikiEntry entry = WikiEntry.create(userId, cmd.type(), cmd.title().trim(), cmd.content(),
                cmd.category(), cmd.industryCode(), Instant.now());
        return WikiEntryView.from(repository.save(entry));
    }

    public WikiEntryView getEntry(Long userId, Long entryId) {
        return WikiEntryView.from(requireEntry(userId, entryId));
    }

    @Transactional
    public WikiEntryView updateEntry(Long userId, Long entryId, UpdateWikiEntryCommand cmd) {
        WikiEntry existing = requireEntry(userId, entryId);
        WikiEntry updated = existing.update(cmd.title().trim(), cmd.content(), cmd.category(), cmd.industryCode());
        return WikiEntryView.from(repository.save(updated));
    }

    @Transactional
    public void deleteEntry(Long userId, Long entryId) {
        requireEntry(userId, entryId);
        repository.deleteById(entryId);
    }

    private WikiEntry requireEntry(Long userId, Long entryId) {
        return repository.findByIdAndUserId(entryId, userId)
                .orElseThrow(() -> new WikiException(WikiErrorCode.NOT_FOUND, "条目不存在"));
    }
}
