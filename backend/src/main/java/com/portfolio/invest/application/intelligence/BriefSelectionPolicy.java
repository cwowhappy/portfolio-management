package com.portfolio.invest.application.intelligence;

import com.portfolio.invest.domain.intelligence.BriefSection;
import com.portfolio.invest.domain.intelligence.NewsRecord;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 简报选取策略纯函数（D17/决策 #21/#22）：输入候选条目（重要度已抽取完成）与阈值，
 * 输出入选清单。无状态非 Spring bean（同 NewsExtractor/PrincipleAlertEvaluator 惯例）。
 *
 * <p>选取规则：
 * <ol>
 *   <li>MAJOR 档（importance ≥ majorAt）全部必选；</li>
 *   <li>WATCH 档（watchAt ≤ importance &lt; majorAt）为各节候选池：按 BriefSection 固定
 *       顺序逐节、节内 importance 降序补足，直至总量达 min；候选全给仍不足 min 则如实
 *       全给（不硬凑）；</li>
 *   <li>总量超 max 按 importance 降序截断（稳定排序，同分保持候选输入序）；</li>
 *   <li>候选池空（无 MAJOR 亦无 WATCH）→ 空简版判定（调用方落 EMPTY_SIMPLE）。</li>
 * </ol>
 * IGNORE 档（&lt; watchAt）不参与——仓库查询阈值即 watchAt，正常不会出现，防御性排除。
 *
 * <p>节归属（单条单节，决策 #20）：抽取结果无 BriefSection 字段，按 event_type 关键词
 * 映射（{@link #sectionOf}，常量表钉死可测）。判定顺序 MACRO→INDUSTRY→COMPANY→
 * LIQUIDITY→GLOBAL——INDUSTRY 须先于 GLOBAL（词内含 GLOBAL 关键词子串 "US"），
 * 未命中/空/null 归 OTHER。
 */
public final class BriefSelectionPolicy {

    /**
     * event_type → 节关键词表（eventType 归一大写后子串命中；英文关键词以大写存储。
     * 英文标签为抽取提示词 D6 受控词表及其常见变体，中文关键词面向人工归档/联调数据）。
     * POLICY 归「宏观与政策」节；M&A 不缩写为 MA（MARGIN 等词含 MA 子串易误伤）。
     */
    private static final Map<BriefSection, List<String>> SECTION_KEYWORDS = Map.of(
            BriefSection.MACRO, List.of("宏观", "央行", "利率", "货币", "财政", "政策",
                    "MACRO", "POLICY", "MONETARY", "RATE", "CPI", "PPI", "PMI", "GDP", "LPR", "FISCAL"),
            BriefSection.INDUSTRY, List.of("行业", "板块", "INDUSTRY", "SECTOR"),
            BriefSection.COMPANY, List.of("公司", "个股", "EARNINGS", "GUIDANCE", "M&A",
                    "PRODUCT", "LEGAL", "COMPANY", "BUYBACK", "DIVIDEND", "EQUITY", "IPO",
                    "RESTRUCTURE", "DELISTING", "STAKE", "SHAREHOLDER", "CONTRACT", "ORDER"),
            BriefSection.LIQUIDITY, List.of("资金", "流动性", "LIQUIDITY", "FUND", "FLOW", "MARGIN"),
            BriefSection.GLOBAL, List.of("海外", "美股", "大宗", "外盘",
                    "GLOBAL", "OVERSEAS", "COMMODITY", "FED", "WORLD", "US", "HK"));

    /** 判定顺序（Map.of 无序，序即本数组序）：INDUSTRY 必须先于 GLOBAL。 */
    private static final BriefSection[] MATCH_ORDER = {
            BriefSection.MACRO, BriefSection.INDUSTRY, BriefSection.COMPANY,
            BriefSection.LIQUIDITY, BriefSection.GLOBAL};

    private BriefSelectionPolicy() {
    }

    /**
     * 选取结果：入选清单（MAJOR 降序在前、逐节补足的 WATCH 随后；截断路径整体按重要度
     * 降序）与按节分组视图（六节俱全，空节为空列表；节内重要度降序）。
     */
    public record Selection(boolean empty, List<NewsRecord> selected,
                            Map<BriefSection, List<NewsRecord>> bySection) {
    }

    /**
     * event_type → 节归属：按 {@link #MATCH_ORDER} 逐节做大小写不敏感子串命中，
     * 首个命中节胜出；null/空/未命中归 OTHER。
     */
    public static BriefSection sectionOf(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            return BriefSection.OTHER;
        }
        String normalized = eventType.trim().toUpperCase(Locale.ROOT);
        for (BriefSection section : MATCH_ORDER) {
            for (String keyword : SECTION_KEYWORDS.get(section)) {
                if (normalized.contains(keyword)) {
                    return section;
                }
            }
        }
        return BriefSection.OTHER;
    }

    /** 选取主入口（规则见类 javadoc；candidates 为窗口内 ≥ watchAt 的全体候选）。 */
    public static Selection select(List<NewsRecord> candidates, int majorAt, int watchAt,
                                   int min, int max) {
        List<NewsRecord> majors = new ArrayList<>();
        List<NewsRecord> watches = new ArrayList<>();
        for (NewsRecord candidate : candidates) {
            Integer importance = candidate.importance();
            if (importance == null || importance < watchAt) {
                continue; // IGNORE 档不参与（防御：正常候选池经仓库 watchAt 阈值过滤）
            }
            if (importance >= majorAt) {
                majors.add(candidate);
            } else {
                watches.add(candidate);
            }
        }

        Builder selection = new Builder();
        if (majors.isEmpty() && watches.isEmpty()) {
            return selection.build(true); // 空简版判定
        }

        List<NewsRecord> chosen = new ArrayList<>(majors);
        chosen.sort(Comparator.comparingInt(NewsRecord::importance).reversed()); // 稳定：同分保持输入序
        if (chosen.size() < min) {
            fillFromWatchPools(chosen, watches, min); // 各节候选池逐节补足
        }
        if (chosen.size() > max) {
            chosen.sort(Comparator.comparingInt(NewsRecord::importance).reversed());
            chosen = new ArrayList<>(chosen.subList(0, max)); // 总量截断 max
        }
        chosen.forEach(selection::add);
        return selection.build(false);
    }

    /** 逐节（BriefSection 固定顺序）从 WATCH 候选池按 importance 降序补足至 min。 */
    private static void fillFromWatchPools(List<NewsRecord> chosen, List<NewsRecord> watches, int min) {
        Map<BriefSection, List<NewsRecord>> pools = new EnumMap<>(BriefSection.class);
        for (BriefSection section : BriefSection.values()) {
            pools.put(section, new ArrayList<>());
        }
        for (NewsRecord watch : watches) {
            pools.get(sectionOf(watch.eventType())).add(watch);
        }
        for (BriefSection section : BriefSection.values()) {
            List<NewsRecord> pool = pools.get(section);
            pool.sort(Comparator.comparingInt(NewsRecord::importance).reversed());
            for (NewsRecord candidate : pool) {
                if (chosen.size() >= min) {
                    return;
                }
                chosen.add(candidate);
            }
        }
    }

    /** 分组视图装配（六节俱全）。 */
    private static final class Builder {
        private final List<NewsRecord> selected = new ArrayList<>();
        private final Map<BriefSection, List<NewsRecord>> bySection = new EnumMap<>(BriefSection.class);

        private Builder() {
            for (BriefSection section : BriefSection.values()) {
                bySection.put(section, new ArrayList<>());
            }
        }

        private void add(NewsRecord record) {
            selected.add(record);
            bySection.get(sectionOf(record.eventType())).add(record);
        }

        private Selection build(boolean empty) {
            return new Selection(empty, List.copyOf(selected), freezeSections());
        }

        private Map<BriefSection, List<NewsRecord>> freezeSections() {
            Map<BriefSection, List<NewsRecord>> frozen = new EnumMap<>(BriefSection.class);
            bySection.forEach((section, items) -> frozen.put(section, List.copyOf(items)));
            return frozen;
        }
    }
}
