package com.portfolio.invest.domain.journal;

import java.util.List;
import java.util.Optional;

/** 记录仓库端口：归属过滤（userId）在用例层双重保障。 */
public interface JournalEntryRepository {
    List<JournalEntry> findByUserId(Long userId, JournalEntryType type);
    Optional<JournalEntry> findByIdAndUserId(Long id, Long userId);
    JournalEntry save(JournalEntry entry);
    void deleteById(Long id);
}
