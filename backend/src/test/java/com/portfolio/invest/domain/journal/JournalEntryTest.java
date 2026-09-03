package com.portfolio.invest.domain.journal;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JournalEntryTest {

    private static final Instant NOW = Instant.parse("2026-09-02T08:00:00Z");

    private static JournalEntry buyMemo() {
        return JournalEntry.create(1L, JournalEntryType.BUY_MEMO, "600519", "贵州茅台", 10L,
                "买入茅台", "理由：长期持有", new BigDecimal("1800"), new BigDecimal("1500"),
                null, null, null, LocalDate.of(2026, 9, 2), NOW);
    }

    @Test
    void 创建买入备忘带目标价止损价() {
        var e = buyMemo();
        assertThat(e.type()).isEqualTo(JournalEntryType.BUY_MEMO);
        assertThat(e.stockCode()).isEqualTo("600519");
        assertThat(e.targetPrice()).isEqualByComparingTo("1800");
        assertThat(e.stopLoss()).isEqualByComparingTo("1500");
        assertThat(e.eventDate()).isEqualTo(LocalDate.of(2026, 9, 2));
    }

    @Test
    void 标题为空抛INVALID_INPUT() {
        assertThatThrownBy(() -> JournalEntry.create(1L, JournalEntryType.RESEARCH_NOTE, null, null, null,
                "  ", "内容", null, null, null, null, null, LocalDate.now(), NOW))
                .isInstanceOfSatisfying(JournalException.class,
                        e -> assertThat(e.code()).isEqualTo(JournalErrorCode.INVALID_INPUT));
    }

    @Test
    void 买入备忘缺股票代码抛INVALID_INPUT() {
        assertThatThrownBy(() -> JournalEntry.create(1L, JournalEntryType.BUY_MEMO, null, null, null,
                "标题", "内容", null, null, null, null, null, LocalDate.now(), NOW))
                .isInstanceOfSatisfying(JournalException.class,
                        e -> assertThat(e.code()).isEqualTo(JournalErrorCode.INVALID_INPUT));
    }

    @Test
    void 目标价为负抛INVALID_INPUT() {
        assertThatThrownBy(() -> JournalEntry.create(1L, JournalEntryType.BUY_MEMO, "600519", "贵州茅台", null,
                "标题", "内容", new BigDecimal("-1"), null, null, null, null, LocalDate.now(), NOW))
                .isInstanceOfSatisfying(JournalException.class,
                        e -> assertThat(e.code()).isEqualTo(JournalErrorCode.INVALID_INPUT));
    }

    @Test
    void 复盘缺期间字段抛INVALID_INPUT() {
        assertThatThrownBy(() -> JournalEntry.create(1L, JournalEntryType.REVIEW, null, null, null,
                "复盘", "内容", null, null, null, null, null, LocalDate.now(), NOW))
                .isInstanceOfSatisfying(JournalException.class,
                        e -> assertThat(e.code()).isEqualTo(JournalErrorCode.INVALID_INPUT));
    }

    @Test
    void 复盘起始日晚于结束日抛INVALID_INPUT() {
        assertThatThrownBy(() -> JournalEntry.create(1L, JournalEntryType.REVIEW, null, null, null,
                "复盘", "内容", null, null, PeriodType.QUARTERLY,
                LocalDate.of(2026, 9, 30), LocalDate.of(2026, 7, 1), LocalDate.now(), NOW))
                .isInstanceOfSatisfying(JournalException.class,
                        e -> assertThat(e.code()).isEqualTo(JournalErrorCode.INVALID_INPUT));
    }

    @Test
    void 复盘绑定个股抛INVALID_INPUT() {
        assertThatThrownBy(() -> JournalEntry.create(1L, JournalEntryType.REVIEW, "600519", "贵州茅台", null,
                "复盘", "内容", null, null, PeriodType.QUARTERLY,
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30), LocalDate.now(), NOW))
                .isInstanceOfSatisfying(JournalException.class,
                        e -> assertThat(e.code()).isEqualTo(JournalErrorCode.INVALID_INPUT));
    }

    @Test
    void 复盘绑定交易抛INVALID_INPUT() {
        assertThatThrownBy(() -> JournalEntry.create(1L, JournalEntryType.REVIEW, null, null, 10L,
                "复盘", "内容", null, null, PeriodType.QUARTERLY,
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30), LocalDate.now(), NOW))
                .isInstanceOfSatisfying(JournalException.class,
                        e -> assertThat(e.code()).isEqualTo(JournalErrorCode.INVALID_INPUT));
    }

    @Test
    void 研究笔记可不关联股票() {
        var e = JournalEntry.create(1L, JournalEntryType.RESEARCH_NOTE, null, null, null,
                "白酒行业研究", "Markdown 内容", null, null, null, null, null, LocalDate.now(), NOW);
        assertThat(e.stockCode()).isNull();
    }

    @Test
    void 更新返回新实例且原实例不变() {
        var e = buyMemo();
        var updated = e.update("600519", "贵州茅台", 11L, "新标题", "新内容",
                new BigDecimal("2000"), new BigDecimal("1600"), null, null, null,
                LocalDate.of(2026, 9, 3));
        assertThat(updated.title()).isEqualTo("新标题");
        assertThat(updated.tradeId()).isEqualTo(11L);
        assertThat(e.title()).isEqualTo("买入茅台"); // 原实例不变
        assertThat(e.tradeId()).isEqualTo(10L);
    }
}
