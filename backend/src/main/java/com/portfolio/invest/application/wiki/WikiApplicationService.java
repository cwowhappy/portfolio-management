package com.portfolio.invest.application.wiki;

import com.portfolio.invest.domain.wiki.WikiEntry;
import com.portfolio.invest.domain.wiki.WikiEntryRepository;
import com.portfolio.invest.domain.wiki.WikiEntryType;
import com.portfolio.invest.domain.wiki.WikiErrorCode;
import com.portfolio.invest.domain.wiki.WikiException;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WikiApplicationService {

    private final WikiEntryRepository repository;

    public WikiApplicationService(WikiEntryRepository repository) {
        this.repository = repository;
    }

    public List<WikiEntryView> entries(Long userId, WikiEntryType type) {
        return repository.findByUserId(userId, type).stream().map(WikiEntryView::from).toList();
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
