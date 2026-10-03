package com.portfolio.invest.infrastructure.persistence.intelligence;

import com.portfolio.invest.domain.intelligence.PushLog;
import com.portfolio.invest.domain.intelligence.PushLogRepository;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 推送留痕仓库实现（intelligence_push_log）。照 {@link IntelligenceDailyBriefRepositoryImpl}
 * 先例全走 JdbcTemplate 原生 SQL，不建 JPA 门面（写入仅各推送服务一处，Task 5 遗留的
 * 「JPA 门面无运行时消费者」教训不再复制）。id 为 IDENTITY 生成不参与写入。
 * 事务边界在 application 层。
 */
@Repository
public class IntelligencePushLogRepositoryImpl implements PushLogRepository {

    private final JdbcTemplate jdbc;

    public IntelligencePushLogRepositoryImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(PushLog log) {
        jdbc.update("""
                INSERT INTO intelligence_push_log
                    (user_id, push_type, target, ref_table, ref_id, status, error, sent_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                log.userId(), log.pushType().name(), log.target(), log.refTable(), log.refId(),
                log.status().name(), log.error(), log.sentAt().atOffset(ZoneOffset.UTC));
    }

    @Override
    public boolean existsAnnouncementPush(Long announcementId, Long userId) {
        Boolean found = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM intelligence_push_log
                              WHERE push_type = 'ANNOUNCEMENT'
                                AND ref_table = 'intelligence_announcement'
                                AND ref_id = ? AND user_id = ?
                                AND status IN ('OK', 'SKIPPED_NO_BINDING'))
                """, Boolean.class, announcementId, userId);
        return Boolean.TRUE.equals(found);
    }
}
