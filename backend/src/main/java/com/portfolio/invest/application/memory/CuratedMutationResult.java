package com.portfolio.invest.application.memory;

/**
 * 策展层变更结果（{@link WorkspaceMemoryPort#deleteCuratedLine} 返回）。
 *
 * @param purgedLedgerLines 两层同步从日账本清除的归一化匹配行数（尽力清除，如实计数）
 */
public record CuratedMutationResult(int purgedLedgerLines) {}
