package com.portfolio.invest.application.research;

import com.portfolio.invest.domain.journal.JournalEntry;
import com.portfolio.invest.domain.journal.JournalEntryRepository;
import com.portfolio.invest.domain.journal.JournalEntryType;
import com.portfolio.invest.domain.research.ResearchErrorCode;
import com.portfolio.invest.domain.research.ResearchException;
import com.portfolio.invest.domain.research.ResearchProject;
import com.portfolio.invest.domain.research.ResearchProjectRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Research 用例族共享支撑函数（归属校验 / journal 事件写入）：由
 * {@link ResearchApplicationService} 门面与各用例族 *ApplicationService 复用，
 * 避免拆分为七族后逐类誊写同一私有助手（2026-10-12 按用例族拆分配套提取）。
 */
final class ResearchServiceSupport {

    private ResearchServiceSupport() {}

    /** 归属双重保障（照 journal requireEntry 模式）：非本人/不存在一律 NOT_FOUND，不泄漏存在性。 */
    static ResearchProject requireProject(ResearchProjectRepository repository, Long userId, Long projectId) {
        return repository.findById(projectId)
                .filter(p -> Objects.equals(p.userId(), userId))
                .orElseThrow(() -> new ResearchException(ResearchErrorCode.NOT_FOUND, "研究项目不存在"));
    }

    /** 研究事件写入（S3）；打点时间取注入时钟（eventDate 按业务时区日历）。 */
    static void writeEvent(JournalEntryRepository journalRepository, Clock clock,
                           ResearchProject project, String title, String content) {
        journalRepository.save(JournalEntry.create(project.userId(), JournalEntryType.RESEARCH_EVENT,
                project.stockCode(), project.stockName(), null, title, content,
                null, null, null, null, null, LocalDate.now(clock), clock.instant(), project.id()));
    }
}
