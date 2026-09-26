package com.portfolio.invest.application.portfolio;

import com.portfolio.invest.domain.portfolio.ImportSimulator;
import java.util.List;

/**
 * CSV 导入结果（设计规格 §1.3 契约）：全成功 {@code importedCount>0 && rowErrors==[]}；
 * 任何拒绝全量回滚（all-or-nothing，决策 #7）——{@code importedCount==0 && rowErrors 非空}。
 */
public record ImportResult(int importedCount, List<ImportSimulator.RowError> rowErrors) {

    public ImportResult {
        rowErrors = List.copyOf(rowErrors);
    }
}
