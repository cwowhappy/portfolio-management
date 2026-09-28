package com.portfolio.invest.domain.research;

/**
 * 单条证伪条件求值结果（纯数据载体）：hit 自动命中（仅 PREDICATE 方向比较成立）、
 * pending 待人工处理（EVENT 待勾选，不自动判命中）、skipped 对应口径最近值缺失跳过；
 * basis 为可解释依据（D10），如「收盘价 12.34 &lt; 下限 13.00（东财收盘 2026-09-26）」。
 */
public record FalsifierHitResult(Falsifier falsifier, boolean hit, boolean pending,
                                 boolean skipped, String basis) {
}
