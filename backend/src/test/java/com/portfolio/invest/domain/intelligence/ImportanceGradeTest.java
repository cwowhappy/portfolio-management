package com.portfolio.invest.domain.intelligence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.portfolio.invest.domain.intelligence.ImportanceGrade.IGNORE;
import static com.portfolio.invest.domain.intelligence.ImportanceGrade.MAJOR;
import static com.portfolio.invest.domain.intelligence.ImportanceGrade.WATCH;
import static org.assertj.core.api.Assertions.assertThat;

class ImportanceGradeTest {
    @DisplayName("重要度分档：score 达 majorAt 为 MAJOR，达 watchAt 为 WATCH，否则 IGNORE（含边界）")
    @Test
    void givenScoreAndThresholds_whenGrading_thenReturnsExpectedGrade() {
        assertThat(ImportanceGrade.grade(80, 80, 50)).isEqualTo(MAJOR);
        assertThat(ImportanceGrade.grade(79, 80, 50)).isEqualTo(WATCH);
        assertThat(ImportanceGrade.grade(50, 80, 50)).isEqualTo(WATCH);
        assertThat(ImportanceGrade.grade(49, 80, 50)).isEqualTo(IGNORE);
        assertThat(ImportanceGrade.grade(0, 80, 50)).isEqualTo(IGNORE);
        assertThat(ImportanceGrade.grade(100, 80, 50)).isEqualTo(MAJOR);
    }
}
