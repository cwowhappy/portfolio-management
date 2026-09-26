package com.portfolio.invest.application.industry;

import java.util.List;

/**
 * 策展 CSV 导入结果（设计规格 §四写侧契约 + §九#2）：upsert 语义下区分双计数——
 * 全成功 {@code insertedCount+updatedCount=N && rowErrors==[]}；任何行级错误全量拒绝
 * （all-or-nothing）时双计数归零、{@code rowErrors 非空}（行号+人话原因，NFR #2 不允许静默跳过）。
 * 文件级错误两档（2026-09-26 评审定案，对齐 MS-14 实现现状与前端 ImportDialog 渲染）：
 * 空文件/超 1MB 由 controller 直接 400 ApiError，不经本类型；解析层文件级错误
 * （表头不匹配/无数据行/超 2000 行）以 {@code rowErrors[row=0]} 走 200 经本类型返回。
 */
public record CurationImportResult(int insertedCount, int updatedCount,
                                   List<RowError> rowErrors) {

    public CurationImportResult {
        rowErrors = List.copyOf(rowErrors);
    }

    /** 行级错误（行号 = CSV 记录序号，表头为 1；文件级错误行号恒 0 无物理行可指）。 */
    public record RowError(int row, String reason) {}
}
