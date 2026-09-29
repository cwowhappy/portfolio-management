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
    private final Long projectId;
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
    private final Long version;

    private JournalEntry(Long id, Long userId, JournalEntryType type, String stockCode, String stockName,
                         Long tradeId, String title, String content, BigDecimal targetPrice, BigDecimal stopLoss,
                         PeriodType periodType, LocalDate periodStart, LocalDate periodEnd,
                         LocalDate eventDate, Instant createdAt, Instant updatedAt, Long version, Long projectId) {
        this.id = id;
        this.userId = userId;
        this.type = type;
        this.stockCode = stockCode;
        this.stockName = stockName;
        this.tradeId = tradeId;
        this.projectId = projectId;
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
        this.version = version;
    }

    public static JournalEntry create(Long userId, JournalEntryType type, String stockCode, String stockName,
                                      Long tradeId, String title, String content,
                                      BigDecimal targetPrice, BigDecimal stopLoss,
                                      PeriodType periodType, LocalDate periodStart, LocalDate periodEnd,
                                      LocalDate eventDate, Instant now) {
        return create(userId, type, stockCode, stockName, tradeId, title, content,
                targetPrice, stopLoss, periodType, periodStart, periodEnd, eventDate, now, null);
    }

    /** 研究事件创建入口：RESEARCH_EVENT 必填 projectId，其余类型传 null（照 User 域增量重载先例）。 */
    public static JournalEntry create(Long userId, JournalEntryType type, String stockCode, String stockName,
                                      Long tradeId, String title, String content,
                                      BigDecimal targetPrice, BigDecimal stopLoss,
                                      PeriodType periodType, LocalDate periodStart, LocalDate periodEnd,
                                      LocalDate eventDate, Instant now, Long projectId) {
        validate(type, stockCode, tradeId, projectId, title, content, targetPrice, stopLoss,
                periodType, periodStart, periodEnd, eventDate);
        return new JournalEntry(null, userId, type, stockCode, stockName, tradeId, title, content,
                targetPrice, stopLoss, periodType, periodStart, periodEnd, eventDate, now, now, null, projectId);
    }

    public static JournalEntry reconstitute(Long id, Long userId, JournalEntryType type,
                                            String stockCode, String stockName, Long tradeId,
                                            String title, String content, BigDecimal targetPrice, BigDecimal stopLoss,
                                            PeriodType periodType, LocalDate periodStart, LocalDate periodEnd,
                                            LocalDate eventDate, Instant createdAt, Instant updatedAt) {
        return reconstitute(id, userId, type, stockCode, stockName, tradeId, title, content,
                targetPrice, stopLoss, periodType, periodStart, periodEnd, eventDate, createdAt, updatedAt, null);
    }

    public static JournalEntry reconstitute(Long id, Long userId, JournalEntryType type,
                                            String stockCode, String stockName, Long tradeId,
                                            String title, String content, BigDecimal targetPrice, BigDecimal stopLoss,
                                            PeriodType periodType, LocalDate periodStart, LocalDate periodEnd,
                                            LocalDate eventDate, Instant createdAt, Instant updatedAt, Long version) {
        return reconstitute(id, userId, type, stockCode, stockName, tradeId, title, content,
                targetPrice, stopLoss, periodType, periodStart, periodEnd, eventDate, createdAt, updatedAt, version, null);
    }

    public static JournalEntry reconstitute(Long id, Long userId, JournalEntryType type,
                                            String stockCode, String stockName, Long tradeId,
                                            String title, String content, BigDecimal targetPrice, BigDecimal stopLoss,
                                            PeriodType periodType, LocalDate periodStart, LocalDate periodEnd,
                                            LocalDate eventDate, Instant createdAt, Instant updatedAt, Long version,
                                            Long projectId) {
        return new JournalEntry(id, userId, type, stockCode, stockName, tradeId, title, content,
                targetPrice, stopLoss, periodType, periodStart, periodEnd, eventDate, createdAt, updatedAt, version,
                projectId);
    }

    /** 更新可变字段（type/projectId 不可变），返回新实例。 */
    public JournalEntry update(String stockCode, String stockName, Long tradeId, String title, String content,
                               BigDecimal targetPrice, BigDecimal stopLoss,
                               PeriodType periodType, LocalDate periodStart, LocalDate periodEnd,
                               LocalDate eventDate) {
        validate(type, stockCode, tradeId, projectId, title, content, targetPrice, stopLoss,
                periodType, periodStart, periodEnd, eventDate);
        return new JournalEntry(id, userId, type, stockCode, stockName, tradeId, title, content,
                targetPrice, stopLoss, periodType, periodStart, periodEnd, eventDate, createdAt, Instant.now(), version,
                projectId);
    }

    private static void validate(JournalEntryType type, String stockCode, Long tradeId, Long projectId,
                                 String title, String content,
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
                // FR-D1：复盘不绑定个股/交易
                if (stockCode != null && !stockCode.isBlank()) {
                    throw new JournalException(JournalErrorCode.INVALID_INPUT, "复盘不绑定个股");
                }
                if (tradeId != null) {
                    throw new JournalException(JournalErrorCode.INVALID_INPUT, "复盘不绑定交易");
                }
                if (periodType == null || periodStart == null || periodEnd == null) {
                    throw new JournalException(JournalErrorCode.INVALID_INPUT, "复盘期间必填");
                }
                if (periodStart.isAfter(periodEnd)) {
                    throw new JournalException(JournalErrorCode.INVALID_INPUT, "复盘起始日不能晚于结束日");
                }
            }
            case RESEARCH_NOTE -> { /* stockCode 可选 */ }
            case RESEARCH_EVENT -> {
                // 软引用 research_project(id)，无 FK；研究事件由研究域写入（P2 Task 6），不绑定交易
                if (projectId == null) {
                    throw new JournalException(JournalErrorCode.INVALID_INPUT, "研究事件必须关联研究项目");
                }
                if (tradeId != null) {
                    throw new JournalException(JournalErrorCode.INVALID_INPUT, "研究事件不绑定交易");
                }
            }
        }
    }

    public Long id() { return id; }
    public Long userId() { return userId; }
    public JournalEntryType type() { return type; }
    public String stockCode() { return stockCode; }
    public String stockName() { return stockName; }
    public Long tradeId() { return tradeId; }
    public Long projectId() { return projectId; }
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
    public Long version() { return version; }
}
