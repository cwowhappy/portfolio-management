package com.portfolio.invest.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.agent.research.ResearchDraftSpec;
import com.portfolio.invest.config.InvestProperties;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 投研草稿域子组件（2026-10-12 自 {@code InvestTools} 按域拆分，纯搬移）：research_draft
 * 工具方法体 + draftJson 解析/摘要全套私有助手。门面保留 @Tool 注解与方法签名
 * （装配面字节不变），此处仅承载实现。
 */
public class ResearchDraftTools {

    private static final Logger log = LoggerFactory.getLogger(ResearchDraftTools.class);

    private final ObjectMapper mapper;
    /** invest.trust 配置组（research_draft draftJson 字节上限等，B9-②）。 */
    private final InvestProperties properties;

    public ResearchDraftTools(ObjectMapper mapper, InvestProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    public String researchDraft(String stage, String draftJson) {
        // 入参字节上限（B9-②，invest.trust.draft-json-max-bytes，UTF-8 字节口径）：超大草稿
        // 会打满会话上下文——超限直接走 {"error","hint"} 信封（不进 buildDraftSpec 的参数错误文本路径）
        int draftJsonBytes = draftJson == null ? 0 : draftJson.getBytes(StandardCharsets.UTF_8).length;
        int maxBytes = properties.getTrust().getDraftJsonMaxBytes();
        if (draftJsonBytes > maxBytes) {
            log.warn("research_draft draftJson 超限: {} 字节 > 上限 {}", draftJsonBytes, maxBytes);
            return ToolResultBlocks.toError(mapper,
                    "draftJson 超过大小上限（上限 " + maxBytes + " 字节，实际 " + draftJsonBytes + " 字节）",
                    "请精简草稿内容（如截断长文本、只保留关键字段）后重试");
        }
        try {
            ResearchDraftSpec spec = buildDraftSpec(stage, draftJson);
            return draftSummary(spec) + "\n```research-draft\n" + mapper.writeValueAsString(spec) + "\n```";
        } catch (Exception e) {
            // 参数错误兜底：绝不抛异常打断会话，错误文本交还 LLM 自行修正重试
            String reason = e.getMessage() == null ? "无法解析草稿参数" : e.getMessage();
            log.warn("research_draft 参数错误: stage={}, reason={}", stage, reason);
            return "[research_draft] 参数错误：" + reason;
        }
    }

    /** 解析 draftJson 并按 stage 构造对应变体：字段缺失容忍（null 允许），类型错/未知 stage 抛 IAE 由调用方兜底。 */
    private ResearchDraftSpec buildDraftSpec(String stage, String draftJson) throws Exception {
        String normalized = stage == null ? "" : stage.trim();
        if (draftJson == null || draftJson.isBlank()) {
            throw new IllegalArgumentException("draftJson 不能为空");
        }
        JsonNode root = mapper.readTree(draftJson);
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("draftJson 须为 JSON 对象");
        }
        return switch (normalized) {
            case "NEW_ANALYSIS" -> ResearchDraftSpec.analysis(
                    text(root, "symbol"), text(root, "companyName"), text(root, "industry"),
                    textList(root, "checklistDone"), text(root, "summary"));
            case "STRATEGY" -> ResearchDraftSpec.strategy(
                    decimal(root, "valuationLow"), decimal(root, "valuationHigh"),
                    text(root, "thesis"), text(root, "positionPlan"), text(root, "buyConditions"),
                    falsifierItems(root));
            case "POSITION" -> ResearchDraftSpec.entryPlan(
                    batchItems(root), decimal(root, "winRate"), decimal(root, "payoffRatio"),
                    text(root, "note"));
            case "REVIEW" -> ResearchDraftSpec.review(
                    text(root, "tier"), text(root, "periodStart"), text(root, "periodEnd"),
                    text(root, "narrative"));
            default -> throw new IllegalArgumentException(
                    "未知 stage: " + normalized + "（合法值：NEW_ANALYSIS/STRATEGY/POSITION/REVIEW）");
        };
    }

    /** 各阶段一句话摘要（进 LLM/stateStore），null 字段静默跳过。 */
    private static String draftSummary(ResearchDraftSpec spec) {
        return switch (spec) {
            case ResearchDraftSpec.AnalysisDraft a -> {
                StringJoiner s = new StringJoiner("，");
                String head = joinSpace(a.symbol(), a.companyName(), a.industry());
                if (!head.isEmpty()) {
                    s.add(head);
                }
                if (a.checklistDone() != null) {
                    s.add("清单 " + a.checklistDone().size() + " 项");
                }
                yield summaryDone("分析草稿", s);
            }
            case ResearchDraftSpec.StrategyDraft d -> {
                StringJoiner s = new StringJoiner("，");
                String range = joinTilde(plain(d.valuationLow()), plain(d.valuationHigh()));
                if (!range.isEmpty()) {
                    s.add("估值区间 " + range);
                }
                if (d.riskItems() != null) {
                    s.add("证伪条件 " + d.riskItems().size() + " 条");
                }
                yield summaryDone("策略草稿", s);
            }
            case ResearchDraftSpec.EntryPlanDraft e -> {
                StringJoiner s = new StringJoiner("，");
                if (e.batches() != null) {
                    s.add(e.batches().size() + " 批建仓");
                }
                if (e.winRate() != null) {
                    s.add("胜率 " + e.winRate().toPlainString());
                }
                if (e.payoffRatio() != null) {
                    s.add("盈亏比 " + e.payoffRatio().toPlainString());
                }
                yield summaryDone("建仓草稿", s);
            }
            case ResearchDraftSpec.ReviewDraft r -> {
                StringJoiner s = new StringJoiner("，");
                if (r.tier() != null) {
                    s.add("档位 " + r.tier());
                }
                String period = joinTilde(r.periodStart(), r.periodEnd());
                if (!period.isEmpty()) {
                    s.add("复盘区间 " + period);
                }
                yield summaryDone("复盘草稿", s);
            }
        };
    }

    private static String summaryDone(String label, StringJoiner s) {
        return s.length() == 0 ? label + "已回显" : label + "已回显：" + s;
    }

    /** 非空段以空格拼接（全空返回空串）。 */
    private static String joinSpace(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p != null && !p.isBlank()) {
                if (!sb.isEmpty()) {
                    sb.append(' ');
                }
                sb.append(p);
            }
        }
        return sb.toString();
    }

    /** 两侧任一存在即以 ~ 连接（全空返回空串）。 */
    private static String joinTilde(String left, String right) {
        if (left == null || left.isBlank()) {
            return right == null ? "" : right;
        }
        return right == null || right.isBlank() ? left : left + "~" + right;
    }

    private static String plain(BigDecimal v) {
        return v == null ? null : v.toPlainString();
    }

    /** 字符串字段：缺失/null 容忍，非文本节点为类型错。 */
    private static String text(JsonNode node, String field) {
        JsonNode n = node.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        if (!n.isTextual()) {
            throw new IllegalArgumentException("字段 " + field + " 须为字符串");
        }
        return n.asText();
    }

    /** 数字字段：接受 JSON 数值与数值文本，无法解析为 BigDecimal 即类型错。 */
    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode n = node.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        try {
            return new BigDecimal(n.asText());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("字段 " + field + " 须为数字");
        }
    }

    /** 整数字段：缺失默认 0，非整数即类型错。 */
    private static long longValue(JsonNode node, String field) {
        JsonNode n = node.get(field);
        if (n == null || n.isNull()) {
            return 0L;
        }
        try {
            return new BigDecimal(n.asText()).longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            throw new IllegalArgumentException("字段 " + field + " 须为整数");
        }
    }

    private static List<String> textList(JsonNode root, String field) {
        JsonNode arr = arrayNode(root, field);
        if (arr == null) {
            return null;
        }
        List<String> out = new ArrayList<>(arr.size());
        for (JsonNode el : arr) {
            if (!el.isTextual()) {
                throw new IllegalArgumentException("字段 " + field + " 的元素须为字符串");
            }
            out.add(el.asText());
        }
        return out;
    }

    private static List<ResearchDraftSpec.FalsifierItem> falsifierItems(JsonNode root) {
        JsonNode arr = arrayNode(root, "riskItems");
        if (arr == null) {
            return null;
        }
        List<ResearchDraftSpec.FalsifierItem> items = new ArrayList<>(arr.size());
        for (JsonNode el : arr) {
            requireObject(el, "riskItems");
            items.add(new ResearchDraftSpec.FalsifierItem(
                    text(el, "kind"), text(el, "predicate"), decimal(el, "threshold"), text(el, "note")));
        }
        return items;
    }

    private static List<ResearchDraftSpec.BatchItem> batchItems(JsonNode root) {
        JsonNode arr = arrayNode(root, "batches");
        if (arr == null) {
            return null;
        }
        List<ResearchDraftSpec.BatchItem> items = new ArrayList<>(arr.size());
        for (JsonNode el : arr) {
            requireObject(el, "batches");
            items.add(new ResearchDraftSpec.BatchItem(
                    decimal(el, "priceLow"), decimal(el, "priceHigh"), longValue(el, "quantity"),
                    decimal(el, "ratio")));
        }
        return items;
    }

    /** 数组字段：缺失/null 容忍，非数组为类型错。 */
    private static JsonNode arrayNode(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        if (!n.isArray()) {
            throw new IllegalArgumentException("字段 " + field + " 须为数组");
        }
        return n;
    }

    private static void requireObject(JsonNode el, String field) {
        if (!el.isObject()) {
            throw new IllegalArgumentException("字段 " + field + " 的元素须为 JSON 对象");
        }
    }
}
