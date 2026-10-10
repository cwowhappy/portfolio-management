package com.portfolio.invest.application.memory;

import java.util.List;

/**
 * 日账本节（整节为管理操作单元——flush 节是原子语义单元，不拆行）。
 *
 * @param header 完整节头行（如 {@code ## Memory Flush — 2026-10-09T13:58:45Z}）
 * @param kind   节类型（节头前缀判定，见 {@link SectionKind}）
 * @param lines  节内行（原样保留，尾随空行剔除）
 */
public record LedgerSection(String header, SectionKind kind, List<String> lines) {}
