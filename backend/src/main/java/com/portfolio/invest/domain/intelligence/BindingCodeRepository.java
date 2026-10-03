package com.portfolio.invest.domain.intelligence;

import java.time.Instant;

/**
 * 飞书绑定码仓库端口（intelligence_binding_code 单表，V3 D8）。
 *
 * <p>本端口承载：设置页生成码（P4 Task 2，trySave）与过期清扫（deleteExpiredBefore）；
 * 核销（按码取行 + 置 used_at）属绑定闭环，P4 Task 5（BindingCommandHandler）按需扩展。
 * 事务边界在 application 层（照既有仓库先例）。
 */
public interface BindingCodeRepository {

    /**
     * 尝试落一张绑定码（D8：6 位数字、TTL 由调用方按配置折算）：PK 冲突返回
     * {@code false} 而非抛约束违例——由调用方换码重生成（≤3 次）。
     *
     * @param code      6 位数字码（PK）
     * @param userId    归属用户
     * @param expiresAt 失效时刻（used_at 落 NULL——未核销）
     * @return true = 已落行；false = 码已被占用（冲突）
     */
    boolean trySave(String code, Long userId, Instant expiresAt);

    /**
     * 删除 expires_at 早于 cutoff 的绑定码（返回删除行数）。按过期时间轴清扫，
     * 不看 used_at——已核销但未过期的码留待自然过期（保留审计痕迹）。
     */
    int deleteExpiredBefore(Instant cutoff);
}
