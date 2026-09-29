package com.portfolio.invest.domain.intelligence;

import java.time.LocalDate;
import java.util.Optional;

/**
 * 盘前简报归档仓库端口（intelligence_daily_brief）。写入仅 {@link BriefGenerationService}
 * （Task 9）一处；读取消费方为 Task 10 推送（findByDate）与简报归档查询端点。
 * top_stocks JSONB 序列化/反序列化由实现负责。事务边界在 application 层（照仓库先例）。
 */
public interface BriefRepository {

    /**
     * 落档一行（trade_date UNIQUE，ON CONFLICT 整体置换）。服务层幂等守卫
     * （findByDate 已有当日档即跳过）为第一道防线，置换语义兜底并发双触发；
     * FAILED 档不自动重试——人工清理行后可重生成。
     */
    void save(DailyBrief brief);

    /** 按交易日取档（无档 empty）。 */
    Optional<DailyBrief> findByDate(LocalDate tradeDate);
}
