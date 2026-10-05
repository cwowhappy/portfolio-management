package com.portfolio.invest.agent.trust;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.support.TrustVerificationCases;
import com.portfolio.invest.support.TrustVerificationCases.Case;
import com.portfolio.invest.support.TrustVerificationCases.ExpectedAnchor;
import com.portfolio.invest.support.TrustVerificationCases.PoolEntry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 验收集门槛测试（MS-29 B2，需求决策 #14：约 30 例 testFixtures 纯确定性 fixture）：
 * 逐例断言 anchors/corrections/notes/text 全量符合预期，并聚合断言验收门槛——
 * 直接引用错值检出率（拦截改写 + 显式标注合计）≥ 90%，正确值误改写率 ≤ 5%。
 */
class ConsistencyValidatorFixtureTest {

    private static final List<Case> CASES = TrustVerificationCases.load();

    private final ConsistencyValidator validator =
            new ConsistencyValidator(new InvestProperties().getTrust());

    @DisplayName("验收集完整性：约 30 例加载成功且 id 唯一")
    @Test
    void givenFixtureDirectory_whenLoad_thenAboutThirtyUniqueCases() {
        assertThat(CASES).hasSizeBetween(28, 34);
        assertThat(CASES).extracting(Case::id).doesNotHaveDuplicates();
    }

    @DisplayName("逐例断言：anchors（snippet+occ→state/raw）、corrections、notes、修正后文本全量符合预期")
    @Test
    void givenEachCase_whenVerifyAndCorrect_thenAllExpectationsHold() {
        for (Case c : CASES) {
            CorrectionResult result = validator.correct(c.text(), toPool(c.pool()));

            assertThat(result.correctedText())
                    .as("case[%s] 修正后文本", c.id())
                    .isEqualTo(c.expectedTextOrOriginal());

            Map<String, AnchorRecord> byKey = new HashMap<>();
            for (AnchorRecord anchor : result.batch().anchors()) {
                assertThat(byKey.putIfAbsent(anchor.snippet() + "#" + anchor.occ(), anchor))
                        .as("case[%s] anchor 键重复: %s#%d", c.id(), anchor.snippet(), anchor.occ())
                        .isNull();
            }
            assertThat(byKey).as("case[%s] anchor 数量", c.id()).hasSize(c.expectedAnchors().size());
            for (ExpectedAnchor expected : c.expectedAnchors()) {
                AnchorRecord actual = byKey.get(expected.snippet() + "#" + expected.occ());
                assertThat(actual).as("case[%s] 缺少 anchor %s#%d",
                        c.id(), expected.snippet(), expected.occ()).isNotNull();
                assertThat(actual.state().wireName()).as("case[%s] %s#%d 状态",
                        c.id(), expected.snippet(), expected.occ()).isEqualTo(expected.state());
                if (expected.expectedRaw() != null) {
                    assertThat(actual.raw()).as("case[%s] %s#%d 命中真值原值",
                            c.id(), expected.snippet(), expected.occ()).isEqualTo(expected.expectedRaw());
                }
            }

            List<CorrectionResult.Correction> corrections = result.corrections();
            assertThat(corrections).as("case[%s] 修正数", c.id())
                    .hasSize(c.expectedCorrections().size());
            for (int i = 0; i < c.expectedCorrections().size(); i++) {
                var expected = c.expectedCorrections().get(i);
                var actual = corrections.get(i);
                assertThat(actual.snippet()).as("case[%s] 修正[%d] snippet", c.id(), i)
                        .isEqualTo(expected.snippet());
                assertThat(actual.occ()).as("case[%s] 修正[%d] occ", c.id(), i)
                        .isEqualTo(expected.occ());
                assertThat(actual.degraded()).as("case[%s] 修正[%d] 是否降级", c.id(), i)
                        .isEqualTo(expected.isDegraded());
            }

            assertThat(result.batch().correctionNotes()).as("case[%s] 注记", c.id())
                    .containsExactlyElementsOf(notesOf(c));
        }
    }

    @DisplayName("验收门槛：直接引用错值检出率 ≥ 90%，正确值误改写率 ≤ 5%")
    @Test
    void givenFixtureSet_whenAggregateRates_thenMeetAcceptanceThresholds() {
        int wrongCases = 0;
        int detectedCases = 0;
        int correctAnchors = 0;
        int misrewritten = 0;

        for (Case c : CASES) {
            CorrectionResult result = validator.correct(c.text(), toPool(c.pool()));
            boolean hasWrong = c.expectedAnchors().stream().anyMatch(ExpectedAnchor::isWrong);
            if (hasWrong) {
                wrongCases++;
                boolean flagged = c.expectedAnchors().stream()
                        .filter(ExpectedAnchor::isWrong)
                        .anyMatch(e -> !"verified".equals(e.state()));
                if (!result.corrections().isEmpty() || flagged) {
                    detectedCases++;
                }
            }
            for (var correction : result.corrections()) {
                boolean targetsCorrect = c.expectedAnchors().stream()
                        .anyMatch(e -> !e.isWrong()
                                && e.snippet().equals(correction.snippet())
                                && e.occ() == correction.occ());
                if (targetsCorrect && !correction.degraded()) {
                    misrewritten++;
                }
            }
            correctAnchors += c.expectedAnchors().stream().filter(e -> !e.isWrong()).count();
        }

        double detectionRate = wrongCases == 0 ? 0 : (double) detectedCases / wrongCases;
        double misrewriteRate = correctAnchors == 0 ? 0 : (double) misrewritten / correctAnchors;
        assertThat(wrongCases).as("错值用例分母非空").isGreaterThanOrEqualTo(10);
        assertThat(correctAnchors).as("正确值锚定分母非空").isGreaterThanOrEqualTo(10);
        assertThat(detectionRate)
                .as("检出率（拦截改写+显式标注合计 / 直接引用错值例数，%d/%d）", detectedCases, wrongCases)
                .isGreaterThanOrEqualTo(0.90);
        assertThat(misrewriteRate)
                .as("误改写率（被改写的正确值 / 正确值锚定数，%d/%d）", misrewritten, correctAnchors)
                .isLessThanOrEqualTo(0.05);
    }

    private static List<String> notesOf(Case c) {
        return c.expectedNotes() == null ? List.of() : c.expectedNotes();
    }

    private static List<ToolInvocation> toPool(List<PoolEntry> entries) {
        List<ToolInvocation> pool = new ArrayList<>();
        for (PoolEntry entry : entries) {
            pool.add(new ToolInvocation(
                    entry.toolName(),
                    Map.of(),
                    entry.value() + entry.suffix(),
                    List.of(),
                    entry.isFailed() ? null : "2026-10-05 14:59:32",
                    entry.isFailed() ? ToolInvocation.AsOfKind.DATA
                            : entry.isMcp() ? ToolInvocation.AsOfKind.CALL : ToolInvocation.AsOfKind.DATA,
                    entry.isFailed()));
        }
        return pool;
    }
}
