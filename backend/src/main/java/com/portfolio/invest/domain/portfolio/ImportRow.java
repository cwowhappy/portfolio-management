package com.portfolio.invest.domain.portfolio;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * CSV 导入的一行解析产物（L2 产出、L3 解析分组、L5 模拟/执行消费）。
 * 校验不在本层：格式与语义校验由解析层（L2）完成，模拟器只消费合法行。
 */
public record ImportRow(int rowNumber, ImportRowType type, LocalDate date, String stockCode,
                        String groupName, Long groupId, BigDecimal price, BigDecimal quantity,
                        BigDecimal fee, BigDecimal amount, String note) {

    public enum ImportRowType { BUY, SELL, CASH_DIVIDEND, STOCK_DIVIDEND, DEPOSIT, WITHDRAW }

    /** L3 分组名解析后的行（模拟器/执行只认 groupId 非空的行）。 */
    public ImportRow withGroupId(Long resolvedGroupId) {
        return new ImportRow(rowNumber, type, date, stockCode, groupName, resolvedGroupId,
                price, quantity, fee, amount, note);
    }
}
