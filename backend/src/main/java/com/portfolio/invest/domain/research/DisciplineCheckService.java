package com.portfolio.invest.domain.research;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 纪律检查唯一计算点（M16-F09/F10/F12，NFR-3 纯函数）：检查上下文 + 规则集 → 检查项列表，
 * 无状态无副作用——不落库、不阻断（D5 软提醒，留痕走 {@link CheckRecord} append-only）。
 *
 * <p>三态语义：比值/倍数不越线 → PASS；严格大于上限 → HIT；规则缺失的指标 → UNSET
 * （「未设定」中性，非 PASS 非 HIT）。F01 必查项为布尔条目（勾选 → PASS，未勾选 → HIT）；
 * SELL/REDUCE 额外注入证伪条件「待核对」条目（outcome=UNSET——命中与否由人工核对语境决定，v1 不自动判定）。
 *
 * <p>规则已配置但上下文值缺失（行情/估值不可得）时跳过该条目，与 FalsifierEvaluator 缺数据
 * 不自动命中同口径；「按最近可得价」类口径标注由 application 组装侧负责。
 */
public final class DisciplineCheckService {

    /** 规则指标名：与 wiki PrincipleMetric 枚举名逐字一致（application 层转换，NFR-4）。 */
    public static final String METRIC_SINGLE_POSITION_RATIO = "SINGLE_POSITION_RATIO";
    public static final String METRIC_INDUSTRY_POSITION_RATIO = "INDUSTRY_POSITION_RATIO";
    public static final String METRIC_STOCK_PE_MAX = "STOCK_PE_MAX";
    public static final String METRIC_STOCK_PB_MAX = "STOCK_PB_MAX";

    /** F01 必查项名：ctx.f01MustItems 勾选值与前端确认卡逐字对齐（P3-T4/T6 消费同一常量）。 */
    public static final String F01_CIRCLE_OF_COMPETENCE = "能力圈";
    public static final String F01_SAFETY_MARGIN = "安全边际";
    public static final String F01_VALUATION_CHECK = "估值核对";
    public static final String F01_BUY_CONDITIONS = "买入条件";

    private static final List<String> F01_MUST_ITEMS = List.of(
            F01_CIRCLE_OF_COMPETENCE, F01_SAFETY_MARGIN, F01_VALUATION_CHECK, F01_BUY_CONDITIONS);

    private DisciplineCheckService() {}

    /**
     * 计算检查项列表（顺序固定：规则项 单票/行业/PE/PB → F01 四项 → 证伪核对项）。
     * rules 为 null 或未识别指标名的规则条目按缺失处理（wiki 新增指标须同步本服务映射）。
     *
     * @return 不可修改的检查项列表
     */
    public static List<CheckItemResult> check(CheckContext ctx, CheckType type, List<RuleInput> rules) {
        if (ctx == null) {
            throw new ResearchException(ResearchErrorCode.CHECK_CONTEXT_REQUIRED, "检查上下文不能为空");
        }
        if (type == null) {
            throw new ResearchException(ResearchErrorCode.CHECK_TYPE_REQUIRED, "检查类型不能为空");
        }
        List<CheckItemResult> items = new ArrayList<>();
        addRuleItem(items, METRIC_SINGLE_POSITION_RATIO, ctx.plannedSingleRatio(), rules);
        addRuleItem(items, METRIC_INDUSTRY_POSITION_RATIO, ctx.plannedIndustryRatio(), rules);
        addRuleItem(items, METRIC_STOCK_PE_MAX, ctx.currentPe(), rules);
        addRuleItem(items, METRIC_STOCK_PB_MAX, ctx.currentPb(), rules);
        for (String mustItem : F01_MUST_ITEMS) {
            items.add(new CheckItemResult(mustItem, null, null,
                    ctx.f01MustItems().contains(mustItem) ? CheckOutcome.PASS : CheckOutcome.HIT));
        }
        if (type.requiresFalsifierCheck()) {
            for (Falsifier falsifier : ctx.falsifiers()) {
                if (!falsifier.enabled()) {
                    continue;
                }
                items.add(new CheckItemResult(falsifierLabel(falsifier), falsifier.threshold(),
                        null, CheckOutcome.UNSET));
            }
        }
        return List.copyOf(items);
    }

    /** 四指标均为上限语义：有规则且值可得才比较（&gt; 上限 HIT、≤ 上限 PASS），规则缺失 UNSET、值缺失跳过。 */
    private static void addRuleItem(List<CheckItemResult> items, String metric,
                                    BigDecimal currentValue, List<RuleInput> rules) {
        RuleInput rule = findRule(metric, rules);
        if (rule == null) {
            items.add(new CheckItemResult(metric, null, null, CheckOutcome.UNSET));
            return;
        }
        if (currentValue == null) {
            return;
        }
        boolean hit = currentValue.compareTo(rule.threshold()) > 0;
        items.add(new CheckItemResult(metric, rule.threshold(), currentValue,
                hit ? CheckOutcome.HIT : CheckOutcome.PASS));
    }

    private static RuleInput findRule(String metric, List<RuleInput> rules) {
        if (rules == null) {
            return null;
        }
        for (RuleInput rule : rules) {
            if (metric.equals(rule.metric())) {
                return rule;
            }
        }
        return null;
    }

    /** 证伪核对条目标识：PREDICATE 用「谓词名 阈值」，EVENT 用文字说明（与 DB 存储契约一致的可读形态）。 */
    private static String falsifierLabel(Falsifier falsifier) {
        if (falsifier.kind() == FalsifierKind.PREDICATE) {
            return falsifier.predicate().name() + " " + falsifier.threshold().toPlainString();
        }
        return falsifier.note();
    }
}
