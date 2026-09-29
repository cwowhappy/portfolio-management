package com.portfolio.invest.domain.intelligence;

import java.time.Instant;

/**
 * 情报推送留痕（intelligence_push_log，NFR-5）：每次推送尝试一行（成功 OK / 失败 FAIL），
 * 供运维感知与事后审计。群推无归属用户（userId null）；ref_table+ref_id 指向被推送的
 * 业务行（如 intelligence_daily_brief 的 id），是幂等查重键。
 *
 * @param id       主键（落库前为 null）
 * @param userId   归属用户（群推 null，列可空 FK）
 * @param pushType BRIEF / ANNOUNCEMENT
 * @param target   open_id 或 chatId
 * @param refTable 关联表名（可空）
 * @param refId    关联行主键（可空）
 * @param status   OK / FAIL / SKIPPED_NO_BINDING
 * @param error    失败原因（仅 FAIL）
 * @param sentAt   留痕时间
 */
public record PushLog(
        Long id,
        Long userId,
        PushType pushType,
        String target,
        String refTable,
        Long refId,
        PushStatus status,
        String error,
        Instant sentAt) {
}
