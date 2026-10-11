package com.portfolio.invest.domain.research;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 研报域三 record 值对象（锚点/假设/配额，设计规格 §二）合并测试：
 * 锚点行创建后不可变（审计真相），假设 anchorId 可空（门未绑定语义），配额为纯读模型。
 */
class ReportRecordModelsTest {

    private static final Instant AS_OF = Instant.parse("2026-10-11T07:59:59Z");

    // ---- ReportAnchor.of：合法构造 ----

    @DisplayName("of：全字段贯通，id 留空（落库生成）")
    @Test
    void givenValidFields_whenAnchorOf_thenCarriedThrough() {
        Map<String, Object> args = Map.of("symbol", "600519.SH");
        var anchor = ReportAnchor.of(31L, "1520.33", 1, "VERIFIED",
                "get_quote", args, AS_OF, "DATA", "\"price\":1520.33");
        assertThat(anchor.id()).isNull();
        assertThat(anchor.reportId()).isEqualTo(31L);
        assertThat(anchor.snippet()).isEqualTo("1520.33");
        assertThat(anchor.occ()).isEqualTo(1);
        assertThat(anchor.state()).isEqualTo("VERIFIED");
        assertThat(anchor.toolName()).isEqualTo("get_quote");
        assertThat(anchor.args()).containsEntry("symbol", "600519.SH");
        assertThat(anchor.asOf()).isEqualTo(AS_OF);
        assertThat(anchor.asOfKind()).isEqualTo("DATA");
        assertThat(anchor.raw()).isEqualTo("\"price\":1520.33");
    }

    @DisplayName("of：三态 VERIFIED/SOURCED/UNVERIFIED 均合法（沿 TrustVerdict.name()）；可空字段允许 null")
    @Test
    void givenEachStateOrNullables_whenAnchorOf_thenPass() {
        for (String state : new String[]{"VERIFIED", "SOURCED", "UNVERIFIED"}) {
            assertThat(ReportAnchor.of(31L, "1520.33", 1, state, null, null, null, null, null).state())
                    .isEqualTo(state);
        }
        var bare = ReportAnchor.of(31L, "15.2", 2, "UNVERIFIED", null, null, null, null, null);
        assertThat(bare.toolName()).isNull();
        assertThat(bare.args()).isNull();
        assertThat(bare.asOf()).isNull();
        assertThat(bare.asOfKind()).isNull();
        assertThat(bare.raw()).isNull();
    }

    @DisplayName("of：args 防御性拷贝——构造后改源 Map 不影响锚点，锚点 Map 不可再改")
    @Test
    void givenMutableSourceArgs_whenAnchorOf_thenDefensiveCopy() {
        Map<String, Object> source = new HashMap<>();
        source.put("symbol", "600519.SH");
        var anchor = ReportAnchor.of(31L, "1520.33", 1, "VERIFIED", "get_quote", source, AS_OF, "DATA", "raw");
        source.put("symbol", "000001.SZ");
        assertThat(anchor.args()).containsEntry("symbol", "600519.SH");
        assertThatThrownBy(() -> anchor.args().put("symbol", "hack"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ---- ReportAnchor.of：校验 ----

    @DisplayName("of：归属研报缺失抛 REPORT_REQUIRED")
    @Test
    void givenNullReportId_whenAnchorOf_thenThrowReportRequired() {
        assertThatThrownBy(() -> ReportAnchor.of(null, "1520.33", 1, "VERIFIED", null, null, null, null, null))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.REPORT_REQUIRED));
    }

    @DisplayName("of：snippet null/空白抛 ANCHOR_INVALID")
    @Test
    void givenBlankSnippet_whenAnchorOf_thenThrowAnchorInvalid() {
        assertThatThrownBy(() -> ReportAnchor.of(31L, null, 1, "VERIFIED", null, null, null, null, null))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.ANCHOR_INVALID));
        assertThatThrownBy(() -> ReportAnchor.of(31L, "  ", 1, "VERIFIED", null, null, null, null, null))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.ANCHOR_INVALID));
    }

    @DisplayName("of：occ ≤ 0 抛 ANCHOR_INVALID（occ 为 1-based 出现位次）")
    @Test
    void givenNonPositiveOcc_whenAnchorOf_thenThrowAnchorInvalid() {
        assertThatThrownBy(() -> ReportAnchor.of(31L, "1520.33", 0, "VERIFIED", null, null, null, null, null))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.ANCHOR_INVALID));
        assertThatThrownBy(() -> ReportAnchor.of(31L, "1520.33", -1, "VERIFIED", null, null, null, null, null))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.ANCHOR_INVALID));
    }

    @DisplayName("of：state 非 VERIFIED/SOURCED/UNVERIFIED（null/小写/杜撰）抛 ANCHOR_INVALID")
    @Test
    void givenInvalidState_whenAnchorOf_thenThrowAnchorInvalid() {
        for (String bad : new String[]{null, "verified", "SOURCED ", "BOGUS"}) {
            assertThatThrownBy(() -> ReportAnchor.of(31L, "1520.33", 1, bad, null, null, null, null, null))
                    .isInstanceOfSatisfying(ResearchException.class,
                            e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.ANCHOR_INVALID));
        }
    }

    // ---- ReportAssumption（紧凑构造器校验） ----

    @DisplayName("假设：全字段贯通；anchorId 可空（门未绑定假设语义，§3.5）")
    @Test
    void givenValidFields_whenNewAssumption_thenCarriedThrough() {
        var bound = new ReportAssumption(41L, 31L, 1, "2026 年营收增速不低于 15%", 51L);
        assertThat(bound.id()).isEqualTo(41L);
        assertThat(bound.reportId()).isEqualTo(31L);
        assertThat(bound.seq()).isEqualTo(1);
        assertThat(bound.statement()).isEqualTo("2026 年营收增速不低于 15%");
        assertThat(bound.anchorId()).isEqualTo(51L);
        var unbound = new ReportAssumption(null, 31L, 2, "消费税改革影响有限", null);
        assertThat(unbound.id()).isNull();
        assertThat(unbound.anchorId()).isNull();
    }

    @DisplayName("假设：归属研报缺失抛 REPORT_REQUIRED")
    @Test
    void givenNullReportId_whenNewAssumption_thenThrowReportRequired() {
        assertThatThrownBy(() -> new ReportAssumption(null, null, 1, "假设", 51L))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.REPORT_REQUIRED));
    }

    @DisplayName("假设：seq ≤ 0 或 statement 空白抛 ASSUMPTION_INVALID")
    @Test
    void givenInvalidSeqOrBlankStatement_whenNewAssumption_thenThrowAssumptionInvalid() {
        assertThatThrownBy(() -> new ReportAssumption(null, 31L, 0, "假设", null))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.ASSUMPTION_INVALID));
        assertThatThrownBy(() -> new ReportAssumption(null, 31L, 1, " ", null))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.ASSUMPTION_INVALID));
        assertThatThrownBy(() -> new ReportAssumption(null, 31L, 1, null, null))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.ASSUMPTION_INVALID));
    }

    // ---- ReportTokenQuota（纯读模型，无校验） ----

    @DisplayName("配额：组件贯通 + 值语义（user+date 定位，tokensUsed 累计读数）")
    @Test
    void givenComponents_whenNewQuota_thenCarriedThrough() {
        var quota = new ReportTokenQuota(7L, LocalDate.of(2026, 10, 11), 320_000L);
        assertThat(quota.userId()).isEqualTo(7L);
        assertThat(quota.quotaDate()).isEqualTo(LocalDate.of(2026, 10, 11));
        assertThat(quota.tokensUsed()).isEqualTo(320_000L);
        assertThat(quota).isEqualTo(new ReportTokenQuota(7L, LocalDate.of(2026, 10, 11), 320_000L));
    }
}
