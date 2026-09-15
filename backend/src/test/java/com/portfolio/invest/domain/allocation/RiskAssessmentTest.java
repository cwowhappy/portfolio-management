package com.portfolio.invest.domain.allocation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RiskAssessmentTest {

    private static final Instant NOW = Instant.parse("2026-09-15T00:00:00Z");

    private static Map<String, String> answers(String optionIdForAll) {
        return java.util.Arrays.stream(RiskQuestion.values())
                .collect(Collectors.toMap(Enum::name, q -> optionIdForAll));
    }

    @DisplayName("题库形状：8 题、每题 5 选项、分值 1-5、选项 id 唯一")
    @Test
    void givenBuiltinQuestionnaire_whenInspect_thenWellFormed() {
        assertThat(RiskQuestion.values()).hasSize(8);
        for (RiskQuestion q : RiskQuestion.values()) {
            assertThat(q.options()).hasSize(5);
            assertThat(q.options().stream().map(RiskOption::id)).doesNotHaveDuplicates();
            assertThat(q.options().stream().mapToInt(RiskOption::score))
                    .containsExactlyInAnyOrder(5, 4, 3, 2, 1);
            assertThat(q.text()).isNotBlank();
            assertThat(q.dimension()).isNotBlank();
        }
    }

    @DisplayName("全选最低分：8 分保守")
    @Test
    void givenAllLowest_whenGrade_thenConservative() {
        var a = RiskAssessment.grade(42L, answers("E"), NOW);
        assertThat(a.totalScore()).isEqualTo(8);
        assertThat(a.profile()).isEqualTo(RiskProfile.CONSERVATIVE);
    }

    @DisplayName("全选最高分：40 分进取")
    @Test
    void givenAllHighest_whenGrade_thenAggressive() {
        var a = RiskAssessment.grade(42L, answers("A"), NOW);
        assertThat(a.totalScore()).isEqualTo(40);
        assertThat(a.profile()).isEqualTo(RiskProfile.AGGRESSIVE);
    }

    @DisplayName("构造指定总分：3 题 A + 5 题 B = 35 分成长（切点上边界）")
    @Test
    void givenMixedAnswers_whenGrade_thenSumMatches() {
        Map<String, String> answers = answers("B");
        answers.put("Q1", "A");
        answers.put("Q2", "A");
        answers.put("Q3", "A"); // 3*5 + 5*4 = 35
        var a = RiskAssessment.grade(42L, answers, NOW);
        assertThat(a.totalScore()).isEqualTo(35);
        assertThat(a.profile()).isEqualTo(RiskProfile.GROWTH);
        assertThat(a.answers()).isEqualTo(answers);
        assertThat(a.assessedAt()).isEqualTo(NOW);
    }

    @DisplayName("缺题拒绝")
    @Test
    void givenMissingQuestion_whenGrade_thenReject() {
        Map<String, String> incomplete = answers("C");
        incomplete.remove("Q5");
        assertThatThrownBy(() -> RiskAssessment.grade(42L, incomplete, NOW))
                .isInstanceOf(AllocationException.class)
                .extracting("code").isEqualTo(AllocationErrorCode.INVALID_ANSWERS);
    }

    @DisplayName("多余题目拒绝（含 Q1~Q8 之外的键）")
    @Test
    void givenExtraQuestionKey_whenGrade_thenReject() {
        Map<String, String> bogus = answers("C");
        bogus.remove("Q8");
        bogus.put("Q9", "A");
        assertThatThrownBy(() -> RiskAssessment.grade(42L, bogus, NOW))
                .isInstanceOf(AllocationException.class);
    }

    @DisplayName("选项不属于该题拒绝")
    @Test
    void givenInvalidOption_whenGrade_thenReject() {
        Map<String, String> bogus = answers("C");
        bogus.put("Q3", "Z");
        assertThatThrownBy(() -> RiskAssessment.grade(42L, bogus, NOW))
                .isInstanceOf(AllocationException.class)
                .extracting("code").isEqualTo(AllocationErrorCode.INVALID_ANSWERS);
    }
}
