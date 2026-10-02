package com.portfolio.invest.domain.intelligence;

/**
 * 推送留痕仓库端口（intelligence_push_log）。审计/排障走 SQL 直查，读取面只开
 * 公告推送的幂等查重一处；写入方为各推送服务（Task 10 盘前简报群推、P2 公告单发）。
 * 事务边界在 application 层（照仓库先例）。
 */
public interface PushLogRepository {

    /** 落一行留痕。 */
    void save(PushLog log);

    /**
     * 公告推送幂等查重（Task 7 消费）：该用户该公告已有 OK / SKIPPED_NO_BINDING 留痕
     * 即视为已处理（跳过重推）；<b>FAIL 行不算</b>——失败允许下一批重推重试。
     */
    boolean existsAnnouncementPush(Long announcementId, Long userId);
}
