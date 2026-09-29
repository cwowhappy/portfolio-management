package com.portfolio.invest.application.intelligence;

import com.portfolio.invest.domain.intelligence.BriefSection;
import com.portfolio.invest.domain.intelligence.Direction;
import com.portfolio.invest.domain.intelligence.NewsRecord;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 简报 markdown 拼装纯函数（D17/决策 #20/#23）：5+1 节固定顺序（节名照决策 #20），节内
 * 条目行格式 {@code - [标题](url)（重要度 85 · 利好）}——条目本身是结构化事实不重写，
 * LLM 只产出节导语（由服务层生成后传入）；top_stocks 为选中条目 stock_codes 频次
 * top 20 快照。无状态非 Spring bean（同 BriefSelectionPolicy 惯例）。
 */
public final class BriefComposer {

    /** 市场时区（窗口/截止时刻展示口径，与调度任务 zone 一致）。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 决策 #23：top_stocks 快照容量上限。 */
    static final int TOP_STOCKS_MAX = 20;

    private BriefComposer() {
    }

    /** 拼装产物：正文 markdown + top_stocks 频次快照。 */
    public record Composed(String contentMd, List<String> topStocks) {
    }

    /**
     * 正常版拼装：标题 + 数据截止行 + 六节（BriefSection 固定顺序），节内=导语（可选）
     * + 条目行（无 url 退化为纯标题、无方向省略方向段）；空节以占位行交代。
     *
     * @param leads 各节导语（无导语节缺席于 map 即可，null 安全）
     */
    public static Composed compose(LocalDate tradeDate, BriefSelectionPolicy.Selection selection,
                                   Map<BriefSection, String> leads, Instant windowStart,
                                   Instant windowEnd) {
        StringBuilder md = new StringBuilder();
        md.append("# 盘前情报速递（").append(tradeDate).append("）\n\n");
        md.append("数据截止：").append(format(windowEnd))
                .append(" · 选取窗口 ").append(format(windowStart)).append(" 起\n");
        for (BriefSection section : BriefSection.values()) {
            md.append("\n## ").append(sectionTitle(section)).append("\n\n");
            String lead = leads == null ? null : leads.get(section);
            if (lead != null && !lead.isBlank()) {
                md.append(lead.trim()).append("\n\n");
            }
            List<NewsRecord> items = selection.bySection().getOrDefault(section, List.of());
            if (items.isEmpty()) {
                md.append("（本节暂无入选条目）\n");
            } else {
                items.forEach(item -> md.append(itemLine(item)).append('\n'));
            }
        }
        return new Composed(md.toString(), topStocks(selection.selected()));
    }

    /** 空简版（决策 #22）：一句话 + 数据截止期别，top_stocks 为空。 */
    public static Composed empty(LocalDate tradeDate, Instant windowStart, Instant windowEnd) {
        String md = "# 盘前情报速递（" + tradeDate + "）\n\n"
                + "今日无重大情报。\n\n"
                + "数据截止：" + format(windowEnd) + " · 选取窗口 " + format(windowStart) + " 起\n";
        return new Composed(md, List.of());
    }

    /** 节名（决策 #20 主干五节 + 其他）。 */
    static String sectionTitle(BriefSection section) {
        return switch (section) {
            case MACRO -> "宏观与政策";
            case INDUSTRY -> "行业与板块";
            case COMPANY -> "公司要闻与公告";
            case LIQUIDITY -> "资金与市场";
            case GLOBAL -> "海外与大宗";
            case OTHER -> "其他要闻";
        };
    }

    /** 条目行：有 url 为链接形态；无方向省略「· 方向」段（无法判断 ≠ 中性）。 */
    static String itemLine(NewsRecord item) {
        String meta = "重要度 " + item.importance()
                + (item.direction() == null ? "" : " · " + directionLabel(item.direction()));
        String paren = "（" + meta + "）";
        if (item.url() == null || item.url().isBlank()) {
            return "- " + item.title() + paren;
        }
        return "- [" + item.title() + "](" + item.url() + ")" + paren;
    }

    /** top_stocks：选中条目 stock_codes 计数频次降序（同频按代码字典序）截断前 20。 */
    static List<String> topStocks(List<NewsRecord> selected) {
        Map<String, Integer> counts = new HashMap<>();
        for (NewsRecord item : selected) {
            for (String code : item.stockCodes()) {
                if (code != null && !code.isBlank()) {
                    counts.merge(code, 1, Integer::sum);
                }
            }
        }
        List<Map.Entry<String, Integer>> ranked = new ArrayList<>(counts.entrySet());
        ranked.sort(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                .thenComparing(Map.Entry.comparingByKey()));
        return ranked.stream().limit(TOP_STOCKS_MAX).map(Map.Entry::getKey).toList();
    }

    private static String directionLabel(Direction direction) {
        return switch (direction) {
            case BULLISH -> "利好";
            case BEARISH -> "利空";
            case NEUTRAL -> "中性";
        };
    }

    private static String format(Instant instant) {
        return TIMESTAMP.format(instant.atZone(ZONE));
    }
}
