package com.portfolio.invest.domain.intelligence;

import java.time.Instant;

/**
 * 飞书绑定码仓库端口（intelligence_binding_code 单表，V3 D8）。
 *
 * <p>绑定码的写入/核销属绑定流程（P4），本端口现只承载过期清扫——
 * 90 天滚动清理任务（Task 11）消费。事务边界在 application 层（照既有仓库先例）。
 */
public interface BindingCodeRepository {

    /**
     * 删除 expires_at 早于 cutoff 的绑定码（返回删除行数）。按过期时间轴清扫，
     * 不看 used_at——已核销但未过期的码留待自然过期（保留审计痕迹）。
     */
    int deleteExpiredBefore(Instant cutoff);
}
