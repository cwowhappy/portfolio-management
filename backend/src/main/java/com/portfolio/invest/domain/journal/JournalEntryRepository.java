package com.portfolio.invest.domain.journal;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 记录仓库端口：归属过滤（userId）在用例层双重保障。 */
public interface JournalEntryRepository {
    List<JournalEntry> findByUserId(Long userId, JournalEntryType type);

    /**
     * 按事件日期范围查询（两端可空表示不设界），日期过滤下推到 SQL，利用 (user_id, event_date) 索引。
     */
    List<JournalEntry> findByUserIdInDateRange(Long userId, LocalDate from, LocalDate to);

    Optional<JournalEntry> findByIdAndUserId(Long id, Long userId);
    JournalEntry save(JournalEntry entry);
    void deleteById(Long id);
}
