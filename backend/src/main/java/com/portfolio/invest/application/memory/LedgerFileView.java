package com.portfolio.invest.application.memory;

import java.time.LocalDate;
import java.util.List;

/**
 * 日账本文件视图（节级管理粒度）。
 *
 * @param date     账本日期（文件名 yyyy-MM-dd）
 * @param hash     整文件 sha-256（hex）
 * @param sections 节列表（「## 」节头切分，文件内出现序）
 */
public record LedgerFileView(LocalDate date, String hash, List<LedgerSection> sections) {}
