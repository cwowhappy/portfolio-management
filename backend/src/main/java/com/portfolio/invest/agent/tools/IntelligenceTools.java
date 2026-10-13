package com.portfolio.invest.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.application.intelligence.IntelligenceQueryService;
import com.portfolio.invest.application.intelligence.IntelligenceQueryService.MacroBriefResult;
import com.portfolio.invest.application.intelligence.IntelligenceQueryService.MacroIndicatorView;
import com.portfolio.invest.application.intelligence.IntelligenceQueryService.NewsSearchResult;
import com.portfolio.invest.application.intelligence.MacroBriefFilter;
import com.portfolio.invest.application.intelligence.NewsSearchFilter;
import com.portfolio.invest.domain.intelligence.MacroPoint;
import io.agentscope.core.tool.ToolEmitter;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 情报域子组件（2026-10-12 自 {@code InvestTools} 按域拆分，纯搬移）：search_news /
 * macro_brief 两个工具的方法体。门面保留 @Tool 注解与方法签名（装配面字节不变），
 * 此处仅承载实现。
 */
public class IntelligenceTools {

    private static final Logger log = LoggerFactory.getLogger(IntelligenceTools.class);

    /** search_news 空结果信封话术：新闻仅 90 天滚动保留（与清理口径一致）。 */
    private static final String NEWS_EMPTY_MESSAGE = "该条件下暂无情报（新闻仅保留 90 天内）";

    /** macro_brief 空政策话术：窗口内无命中（政策库长期保留，非「无数据」）。 */
    private static final String POLICY_EMPTY_NOTE = "该窗口内暂无政策事件（可调大 policyDays 或稍后再试）";

    /** TY 两点无历史序列 note（T4 裁定随行：跨表只合成最新点——note 交代而非空数组，勿当数据缺失）。 */
    private static final String TY_NO_HISTORY_NOTE = "国债收益率仅最新点、无历史序列";

    private final IntelligenceQueryService intelligenceQuery;
    private final ObjectMapper mapper;

    public IntelligenceTools(IntelligenceQueryService intelligenceQuery, ObjectMapper mapper) {
        this.intelligenceQuery = intelligenceQuery;
        this.mapper = mapper;
    }

    public String searchNews(
            String q, String stock, String industry,
            String from, String to, Integer minImportance, Integer limit) {
        // 日期前置校验：格式错走参数错误（run() 兜底会误导为“工具执行失败”）
        LocalDate fromDate;
        LocalDate toDate;
        try {
            fromDate = parseDate(from);
            toDate = parseDate(to);
        } catch (DateTimeParseException e) {
            return ToolResultBlocks.toError(mapper,
                    "日期格式须为 yyyy-MM-dd（如 2026-09-01），实际收到 from=" + from + " to=" + to,
                    "请修正 from/to 后重试");
        }
        return ToolResultBlocks.runString(log, mapper, () -> {
            NewsSearchResult result = intelligenceQuery.searchNews(
                    new NewsSearchFilter(q, stock, industry, fromDate, toDate, minImportance, limit));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("items", result.items());
            if (result.items().isEmpty()) {
                body.put("message", NEWS_EMPTY_MESSAGE);
            } else {
                body.put("total", result.total());
            }
            return mapper.writeValueAsString(body);
        });
    }

    /** 空白串归一 null；非法格式抛 DateTimeParseException 由调用方前置校验兜底。 */
    private static LocalDate parseDate(String s) {
        return s == null || s.isBlank() ? null : LocalDate.parse(s);
    }

    public String macroBrief(String indicators, Integer policyDays) {
        // YAGNI 裁定（MS-22 Task 6）：「市场含义」不在本工具内调 LLM 生成模板句——工具输出
        // 纯结构化事实 + 数据截止期别，市场含义解读留 Agent 对话层基于工具结果自然生成
        // （事实与观点分离；待简报/推送到宏观节的落地需求出现再评估，v1 不做）。
        return ToolResultBlocks.runString(log, mapper, () -> {
            MacroBriefResult result = intelligenceQuery.macroBrief(
                    new MacroBriefFilter(indicators, policyDays));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("indicators", result.indicators().stream()
                    .map(IntelligenceTools::indicatorEntry).toList());
            body.put("policies", result.policies());
            if (result.policies().isEmpty()) {
                body.put("note", POLICY_EMPTY_NOTE);
            } else {
                body.put("total", result.total());
            }
            body.put("missing", result.missing().stream()
                    .map(IntelligenceTools::missingEntry).toList());
            body.put("generatedAt", result.generatedAt());
            return mapper.writeValueAsString(body);
        });
    }

    /** 指标条目 JSON：TY 两点 series 空翻译为 note 字段（T4 裁定随行，勿当数据缺失）。 */
    private static Map<String, Object> indicatorEntry(MacroIndicatorView v) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("indicator", v.indicator());
        entry.put("value", v.value());
        if (v.yoy() != null) {
            entry.put("yoy", v.yoy());
        }
        entry.put("period", v.period());
        entry.put("periodType", v.periodType());
        if (v.series().isEmpty() && (MacroPoint.INDICATOR_TY1Y.equals(v.indicator())
                || MacroPoint.INDICATOR_TY10Y.equals(v.indicator()))) {
            entry.put("note", TY_NO_HISTORY_NOTE);
        } else {
            entry.put("series", v.series());
        }
        return entry;
    }

    /** 缺失指标条目 JSON：{indicator,missing:true} 显式列出（F13 缺失不编造不省略）。 */
    private static Map<String, Object> missingEntry(String indicator) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("indicator", indicator);
        entry.put("missing", true);
        return entry;
    }
}
