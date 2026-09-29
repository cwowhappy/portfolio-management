package com.portfolio.invest.domain.intelligence;

/**
 * 推送留痕仓库端口（intelligence_push_log）。只写不读（审计/排障走 SQL 直查），
 * 写入方为各推送服务（Task 10 盘前简报群推、P2 公告单发）。事务边界在 application 层
 * （照仓库先例）。
 */
public interface PushLogRepository {

    /** 落一行留痕。 */
    void save(PushLog log);
}
