package com.portfolio.invest.application.wiki;

import com.portfolio.invest.domain.wiki.WikiEntry;
import com.portfolio.invest.domain.wiki.WikiEntryRepository;
import com.portfolio.invest.domain.wiki.WikiEntryType;
import com.portfolio.invest.domain.wiki.WikiErrorCode;
import com.portfolio.invest.domain.wiki.WikiException;
import com.portfolio.invest.domain.wiki.WikiSeedStateRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WikiApplicationService {

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

    /** 概念首次拉取触发预置 seeding（幂等：wiki_seed_state 标记；删光不复活）。 */
    @Transactional
    public List<WikiEntryView> entries(Long userId, WikiEntryType type) {
        if (type == WikiEntryType.CONCEPT) {
            seedPresetConcepts(userId);
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
