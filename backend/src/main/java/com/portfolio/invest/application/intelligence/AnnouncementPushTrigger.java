package com.portfolio.invest.application.intelligence;

import java.time.Instant;

/**
 * 公告定向推送触发端口（Task 5 定义 / Task 7 实现，解两任务的顺序依赖）：
 * 抽取批批末以**批起点时刻**触发推送——推送侧按
 * {@link com.portfolio.invest.domain.intelligence.AnnouncementRepository#findExtractedMajorSince}
 * （extracted_at 时间轴、≥ 含边界）取本批完成抽取的 major 公告做订阅/持仓命中推送。
 *
 * <p>抽取服务经 {@code ObjectProvider&lt;AnnouncementPushTrigger&gt;} 注入：Task 7 的
 * AnnouncementPushService 就位前无实现 bean，跳过（debug 日志），链路照常。实现须自行
 * 吞异常（抽取侧亦有防御包裹，双保险）。
 */
public interface AnnouncementPushTrigger {

    /**
     * 推送 since 起（含边界）完成抽取的 major 公告。
     *
     * @param since 抽取批起点时刻（本批全部置换行的 extracted_at ≥ 该值）
     */
    void pushExtracted(Instant since);
}
