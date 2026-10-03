package com.portfolio.invest.application.intelligence;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.domain.intelligence.BriefSection;
import com.portfolio.invest.domain.intelligence.Direction;
import com.portfolio.invest.domain.intelligence.ExtractStatus;
import com.portfolio.invest.domain.intelligence.NewsRecord;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 简报选取策略纯函数测试（D17/决策 #21/#22）：全部 MAJOR 必选但受 max 总量截断、
 * 各节 WATCH 候选池按 importance 降序逐节补足至 min（候选不足如实全给）、
 * 候选池空 → 空简版判定、event_type → BriefSection 关键词映射（映射表常量钉死）。
 */
class BriefSelectionPolicyTest {

    private static final int MAJOR_AT = 80;
    private static final int WATCH_AT = 50;

    @Test
    @DisplayName("给定30条全MAJOR超过上限25，when选取，then稳定截断为25条且判定非空")
    void givenThirtyAllMajor_whenSelect_thenTruncatedToMax() {
        List<NewsRecord> candidates = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            candidates.add(news(i, 85, "MACRO"));
        }

        BriefSelectionPolicy.Selection selection =
                BriefSelectionPolicy.select(candidates, MAJOR_AT, WATCH_AT, 15, 25);

        assertThat(selection.empty()).isFalse();
        assertThat(selection.selected()).hasSize(25); // 全 MAJOR 必选，但总量截断 max
        // 同分稳定排序：保持窗口内输入序，截去尾部 5 条
        assertThat(selection.selected()).extracting(r -> r.id())
                .containsExactlyElementsOf(ids(1, 25));
    }

    @Test
    @DisplayName("给定3条MAJOR加各节WATCH候选池，when选取，then按节序节内分值降序补足至min")
    void givenMajorsAndWatchPools_whenSelect_thenFillBySectionOrderAndScore() {
        List<NewsRecord> candidates = List.of(
                news(1, 90, "MACRO"), news(2, 85, "EARNINGS"), news(3, 82, "M&A"),
                // INDUSTRY 候选池：60/55/52（watch 档；注意勿用含「政策」的类型——MACRO 判定在先）
                news(4, 60, "INDUSTRY"), news(5, 55, "SECTOR"), news(6, 52, "板块轮动"),
                // LIQUIDITY 候选池：58
                news(7, 58, "LIQUIDITY"),
                // IGNORE 档不参与
                news(8, 30, "EARNINGS"), news(9, 10, "MACRO"));

        // min=6：3 条 MAJOR + 逐节补足——MACRO 池空，INDUSTRY 池 60/55/52 依次补入至 6
        BriefSelectionPolicy.Selection toSix =
                BriefSelectionPolicy.select(candidates, MAJOR_AT, WATCH_AT, 6, 25);
        assertThat(toSix.selected()).extracting(r -> r.id())
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
        assertThat(toSix.bySection().get(BriefSection.COMPANY))
                .extracting(NewsRecord::id).containsExactly(2L, 3L);
        assertThat(toSix.bySection().get(BriefSection.INDUSTRY))
                .extracting(NewsRecord::id).containsExactly(4L, 5L, 6L);

        // min=4：只补 INDUSTRY 池分值最高的 1 条即停（LIQUIDITY 池不动）
        BriefSelectionPolicy.Selection toFour =
                BriefSelectionPolicy.select(candidates, MAJOR_AT, WATCH_AT, 4, 25);
        assertThat(toFour.selected()).extracting(r -> r.id())
                .containsExactly(1L, 2L, 3L, 4L);

        // min=15：候选全给仍不足 min，如实全给（不硬凑），IGNORE 档始终排除
        BriefSelectionPolicy.Selection all =
                BriefSelectionPolicy.select(candidates, MAJOR_AT, WATCH_AT, 15, 25);
        assertThat(all.selected()).extracting(r -> r.id())
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L);
    }

    @Test
    @DisplayName("给定MAJOR已足min且WATCH池充裕，when选取，then不触发补足只取MAJOR")
    void givenMajorsReachMin_whenSelect_thenNoWatchFill() {
        List<NewsRecord> candidates = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            candidates.add(news(i, 95, "MACRO")); // 12 条 MAJOR，min=10 已足
        }
        for (int i = 13; i <= 22; i++) {
            candidates.add(news(i, 60, "INDUSTRY")); // watch 池 10 条备而不用
        }

        BriefSelectionPolicy.Selection selection =
                BriefSelectionPolicy.select(candidates, MAJOR_AT, WATCH_AT, 10, 25);

        assertThat(selection.empty()).isFalse();
        assertThat(selection.selected()).extracting(r -> r.id())
                .containsExactlyElementsOf(ids(1, 12)); // 只取 MAJOR，WATCH 不补足
        assertThat(selection.bySection().get(BriefSection.INDUSTRY)).isEmpty();
    }

    @Test
    @DisplayName("给定无候选或全部低于关注线，when选取，then空简版判定")
    void givenNoCandidates_whenSelect_thenEmptyVerdict() {
        assertThat(BriefSelectionPolicy.select(List.of(), MAJOR_AT, WATCH_AT, 15, 25).empty()).isTrue();
        assertThat(BriefSelectionPolicy.select(List.of(news(1, 30, "EARNINGS")), MAJOR_AT, WATCH_AT, 15, 25)
                .empty()).isTrue();
        // 空简版：selected 为空、bySection 各节皆空
        BriefSelectionPolicy.Selection selection =
                BriefSelectionPolicy.select(List.of(), MAJOR_AT, WATCH_AT, 15, 25);
        assertThat(selection.selected()).isEmpty();
        assertThat(selection.bySection().values()).allMatch(List::isEmpty);
    }

    @Test
    @DisplayName("给定各类event_type，when节归属映射，then关键词命中对应BriefSection其余入OTHER")
    void givenVariousEventTypes_whenSectionOf_thenMappedByConstantKeywordTable() {
        // MACRO：宏观/央行/利率族 + 英文标签（POLICY 属「宏观与政策」节）
        assertThat(BriefSelectionPolicy.sectionOf("MACRO")).isEqualTo(BriefSection.MACRO);
        assertThat(BriefSelectionPolicy.sectionOf("POLICY")).isEqualTo(BriefSection.MACRO);
        assertThat(BriefSelectionPolicy.sectionOf("央行降息")).isEqualTo(BriefSection.MACRO);
        assertThat(BriefSelectionPolicy.sectionOf("RATE")).isEqualTo(BriefSection.MACRO);
        // INDUSTRY（须先于 GLOBAL 判定——INDUSTRY 词内含 GLOBAL 关键词子串）
        assertThat(BriefSelectionPolicy.sectionOf("INDUSTRY")).isEqualTo(BriefSection.INDUSTRY);
        assertThat(BriefSelectionPolicy.sectionOf("SECTOR")).isEqualTo(BriefSection.INDUSTRY);
        assertThat(BriefSelectionPolicy.sectionOf("板块异动")).isEqualTo(BriefSection.INDUSTRY);
        // COMPANY：公司/个股 + 抽取提示词受控词表（EARNINGS/M&A/GUIDANCE/PRODUCT/LEGAL）
        assertThat(BriefSelectionPolicy.sectionOf("EARNINGS")).isEqualTo(BriefSection.COMPANY);
        assertThat(BriefSelectionPolicy.sectionOf("M&A")).isEqualTo(BriefSection.COMPANY);
        assertThat(BriefSelectionPolicy.sectionOf("GUIDANCE")).isEqualTo(BriefSection.COMPANY);
        assertThat(BriefSelectionPolicy.sectionOf("PRODUCT")).isEqualTo(BriefSection.COMPANY);
        assertThat(BriefSelectionPolicy.sectionOf("LEGAL")).isEqualTo(BriefSection.COMPANY);
        assertThat(BriefSelectionPolicy.sectionOf("个股公告")).isEqualTo(BriefSection.COMPANY);
        // LIQUIDITY
        assertThat(BriefSelectionPolicy.sectionOf("LIQUIDITY")).isEqualTo(BriefSection.LIQUIDITY);
        assertThat(BriefSelectionPolicy.sectionOf("资金面")).isEqualTo(BriefSection.LIQUIDITY);
        assertThat(BriefSelectionPolicy.sectionOf("NORTHBOUND_FLOW")).isEqualTo(BriefSection.LIQUIDITY);
        // GLOBAL（US 为子串关键词，须在 INDUSTRY 之后判定）
        assertThat(BriefSelectionPolicy.sectionOf("GLOBAL")).isEqualTo(BriefSection.GLOBAL);
        assertThat(BriefSelectionPolicy.sectionOf("US_MARKET")).isEqualTo(BriefSection.GLOBAL);
        assertThat(BriefSelectionPolicy.sectionOf("美股大跌")).isEqualTo(BriefSection.GLOBAL);
        assertThat(BriefSelectionPolicy.sectionOf("COMMODITY")).isEqualTo(BriefSection.GLOBAL);
        // OTHER：null/空/未命中
        assertThat(BriefSelectionPolicy.sectionOf(null)).isEqualTo(BriefSection.OTHER);
        assertThat(BriefSelectionPolicy.sectionOf("")).isEqualTo(BriefSection.OTHER);
        assertThat(BriefSelectionPolicy.sectionOf("SOMETHING_NEW")).isEqualTo(BriefSection.OTHER);
    }

    // ── fixture 助手 ───────────────────────────────────────────────

    /** SUCCESS 抽取完成的候选条目（importance/eventType 可配，标题按 id 唯一）。 */
    private static NewsRecord news(long id, int importance, String eventType) {
        String title = "第" + id + "号情报";
        return new NewsRecord(id, "eastmoney_724", "ext-" + id, title, title + "的摘要",
                Instant.parse("2026-09-29T00:30:00Z"), "https://example.com/n" + id, "[]",
                Instant.parse("2026-09-29T00:40:00Z"),
                eventType, List.of(), List.of(), title + "的AI摘要", Direction.BULLISH, List.of(),
                importance, ExtractStatus.SUCCESS, "deepseek-v4-flash",
                Instant.parse("2026-09-29T00:45:00Z"));
    }

    private static List<Long> ids(int from, int to) {
        List<Long> ids = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            ids.add((long) i);
        }
        return ids;
    }
}
