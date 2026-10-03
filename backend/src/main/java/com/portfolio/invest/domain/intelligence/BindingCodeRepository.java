package com.portfolio.invest.domain.intelligence;

import java.time.Instant;
import java.util.Optional;

/**
 * 飞书绑定码仓库端口（intelligence_binding_code 单表，V3 D8）。
 *
 * <p>本端口承载：设置页生成码（P4 Task 2，trySave）、过期清扫（deleteExpiredBefore）
 * 与核销闭环（findRedeemableUserId 前置校验 + tryMarkUsed 原子占位，P4 Task 5
 * BindingCommandHandler 经 SubscriptionService.bindByCode 消费）。
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

    /**
     * 按码查可核销行的归属用户（核销前置校验）：码存在、未核销（used_at IS NULL）、
     * 未过期（expires_at >= now）才返回 userId，否则 empty（调用方按
     * BINDING_CODE_EXPIRED 拒绝）。
     */
    Optional<Long> findRedeemableUserId(String code, Instant now);

    /**
     * 原子核销占位（D8 一次性语义的并发护栏）：{@code UPDATE ... SET used_at = ?
     * WHERE code = ? AND used_at IS NULL AND expires_at >= ?} 受影响行数判定——
     * false = 码不存在/已核销/已过期/被并发先核销（调用方按 BINDING_CODE_EXPIRED 拒绝）。
     */
    boolean tryMarkUsed(String code, Instant now);
}
