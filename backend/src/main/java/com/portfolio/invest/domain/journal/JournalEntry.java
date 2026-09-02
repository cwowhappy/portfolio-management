package com.portfolio.invest.domain.journal;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** 投资决策记录聚合根：不可变，变更操作 update 返回新实例。四类记录统一建模，类型特有字段可空。 */
public final class JournalEntry {

    private final Long id;
    private final Long userId;
    private final JournalEntryType type;
    private final String stockCode;
    private final String stockName;
    private final Long tradeId;
    private final String title;
    private final String content;
    private final BigDecimal targetPrice;
    private final BigDecimal stopLoss;
    private final PeriodType periodType;
    private final LocalDate periodStart;
    private final LocalDate periodEnd;
    private final LocalDate eventDate;
    private final Instant createdAt;
    private final Instant updatedAt;

    private JournalEntry(Long id, Long userId, JournalEntryType type, String stockCode, String stockName,
                         Long tradeId, String title, String content, BigDecimal targetPrice, BigDecimal stopLoss,
                         PeriodType periodType, LocalDate periodStart, LocalDate periodEnd,
                         LocalDate eventDate, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.userId = userId;
        this.type = type;
        this.stockCode = stockCode;
        this.stockName = stockName;
        this.tradeId = tradeId;
        this.title = title;
        this.content = content;
        this.targetPrice = targetPrice;
        this.stopLoss = stopLoss;
        this.periodType = periodType;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.eventDate = eventDate;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static JournalEntry create(Long userId, JournalEntryType type, String stockCode, String stockName,
                                      Long tradeId, String title, String content,
                                      BigDecimal targetPrice, BigDecimal stopLoss,
                                      PeriodType periodType, LocalDate periodStart, LocalDate periodEnd,
                                      LocalDate eventDate, Instant now) {
        validate(type, stockCode, title, content, targetPrice, stopLoss,
                periodType, periodStart, periodEnd, eventDate);
        return new JournalEntry(null, userId, type, stockCode, stockName, tradeId, title, content,
                targetPrice, stopLoss, periodType, periodStart, periodEnd, eventDate, now, now);
    }

    public static JournalEntry reconstitute(Long id, Long userId, JournalEntryType type,
                                            String stockCode, String stockName, Long tradeId,
                                            String title, String content, BigDecimal targetPrice, BigDecimal stopLoss,
                                            PeriodType periodType, LocalDate periodStart, LocalDate periodEnd,
                                            LocalDate eventDate, Instant createdAt, Instant updatedAt) {
        return new JournalEntry(id, userId, type, stockCode, stockName, tradeId, title, content,
                targetPrice, stopLoss, periodType, periodStart, periodEnd, eventDate, createdAt, updatedAt);
    }

    /** 更新可变字段（type 不可变），返回新实例。 */
    public JournalEntry update(String stockCode, String stockName, Long tradeId, String title, String content,
                               BigDecimal targetPrice, BigDecimal stopLoss,
                               PeriodType periodType, LocalDate periodStart, LocalDate periodEnd,
                               LocalDate eventDate) {
        validate(type, stockCode, title, content, targetPrice, stopLoss,
                periodType, periodStart, periodEnd, eventDate);
        return new JournalEntry(id, userId, type, stockCode, stockName, tradeId, title, content,
                targetPrice, stopLoss, periodType, periodStart, periodEnd, eventDate, createdAt, Instant.now());
    }

    private static void validate(JournalEntryType type, String stockCode, String title, String content,
                                 BigDecimal targetPrice, BigDecimal stopLoss,
                                 PeriodType periodType, LocalDate periodStart, LocalDate periodEnd,
                                 LocalDate eventDate) {
        if (title == null || title.isBlank()) {
            throw new JournalException(JournalErrorCode.INVALID_INPUT, "标题不能为空");
        }
        if (content == null || content.isBlank()) {
            throw new JournalException(JournalErrorCode.INVALID_INPUT, "内容不能为空");
        }
        if (eventDate == null) {
            throw new JournalException(JournalErrorCode.INVALID_INPUT, "事件日期不能为空");
        }
        switch (type) {
            case BUY_MEMO, SELL_MEMO -> {
                if (stockCode == null || stockCode.isBlank()) {
                    throw new JournalException(JournalErrorCode.INVALID_INPUT, "股票代码不能为空");
                }
                if (targetPrice != null && targetPrice.signum() <= 0) {
                    throw new JournalException(JournalErrorCode.INVALID_INPUT, "目标价必须为正");
                }
                if (stopLoss != null && stopLoss.signum() <= 0) {
                    throw new JournalException(JournalErrorCode.INVALID_INPUT, "止损价必须为正");
                }
            }
            case REVIEW -> {
                if (periodType == null || periodStart == null || periodEnd == null) {
                    throw new JournalException(JournalErrorCode.INVALID_INPUT, "复盘期间必填");
                }
                if (periodStart.isAfter(periodEnd)) {
                    throw new JournalException(JournalErrorCode.INVALID_INPUT, "复盘起始日不能晚于结束日");
                }
            }
            case RESEARCH_NOTE -> { /* stockCode 可选 */ }
        }
    }

    public Long id() { return id; }
    public Long userId() { return userId; }
    public JournalEntryType type() { return type; }
    public String stockCode() { return stockCode; }
    public String stockName() { return stockName; }
    public Long tradeId() { return tradeId; }
    public String title() { return title; }
    public String content() { return content; }
    public BigDecimal targetPrice() { return targetPrice; }
    public BigDecimal stopLoss() { return stopLoss; }
    public PeriodType periodType() { return periodType; }
    public LocalDate periodStart() { return periodStart; }
    public LocalDate periodEnd() { return periodEnd; }
    public LocalDate eventDate() { return eventDate; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
}
