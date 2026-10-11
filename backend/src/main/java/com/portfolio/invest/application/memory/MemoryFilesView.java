package com.portfolio.invest.application.memory;

import java.util.List;

/**
 * 记忆文件全视图（{@link WorkspaceMemoryPort#load} 返回）。
 *
 * @param curated 策展层视图（文件缺失 → hash=null、lines=[]）
 * @param ledgers 当期日账本视图（按日期倒序；archive 与隐藏文件排除在外）
 */
public record MemoryFilesView(MemoryFileView curated, List<LedgerFileView> ledgers) {}
