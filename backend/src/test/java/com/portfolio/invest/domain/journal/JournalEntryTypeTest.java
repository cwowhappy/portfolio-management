package com.portfolio.invest.domain.journal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class JournalEntryTypeTest {
    @DisplayName("四种记录类型带中文标签")
    @Test
    void fourRecordTypesCarryChineseLabels() {
        assertThat(JournalEntryType.values()).hasSize(4);
        assertThat(JournalEntryType.BUY_MEMO.label()).isEqualTo("买入备忘");
        assertThat(JournalEntryType.SELL_MEMO.label()).isEqualTo("卖出备忘");
        assertThat(JournalEntryType.RESEARCH_NOTE.label()).isEqualTo("研究笔记");
        assertThat(JournalEntryType.REVIEW.label()).isEqualTo("定期复盘");
    }
}
