package com.portfolio.invest.application.memory;

import java.util.List;

/**
 * 策展层文件视图（行级管理粒度）。
 *
 * @param hash  整文件 sha-256（hex）；文件缺失为 null
 * @param lines 非空行条目（行序即 {@code lineIndex} 寻址序，含标题行——标题行只读保护）
 */
public record MemoryFileView(String hash, List<String> lines) {}
