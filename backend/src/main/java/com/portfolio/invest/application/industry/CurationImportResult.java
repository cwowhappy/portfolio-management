package com.portfolio.invest.application.industry;

import java.util.List;

/**
 * 策展 CSV 导入结果（设计规格 §四写侧契约 + §九#2）：upsert 语义下区分双计数——
 * 全成功 {@code insertedCount+updatedCount=N && rowErrors==[]}；任何行级错误全量拒绝
 * （all-or-nothing）时双计数归零、{@code rowErrors 非空}（行号+人话原因，NFR #2 不允许静默跳过）。
 * 文件级错误（空/超限/表头不匹配）不经本类型——控制器直接 400 ApiError。
 */
public record CurationImportResult(int insertedCount, int updatedCount,
                                   List<RowError> rowErrors) {

    public CurationImportResult {
        rowErrors = List.copyOf(rowErrors);
    }

    /** 行级错误（行号 = CSV 记录序号，表头为 1；文件级错误行号恒 0 无物理行可指）。 */
    public record RowError(int row, String reason) {}
}
