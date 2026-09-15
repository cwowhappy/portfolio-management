package com.portfolio.invest.domain.allocation;

import java.util.List;

/** 内置风险测评题库：8 题三类维度（承受能力·客观 Q1-Q4 / 容忍意愿·主观 Q5-Q7 / 经验 Q8）。 */
public enum RiskQuestion {
    Q1("承受能力·客观", "您的年龄段是？", List.of(
            new RiskOption("A", "30 岁及以下", 5),
            new RiskOption("B", "31–40 岁", 4),
            new RiskOption("C", "41–50 岁", 3),
            new RiskOption("D", "51–60 岁", 2),
            new RiskOption("E", "60 岁以上", 1))),
    Q2("承受能力·客观", "这笔投资计划放置多久？", List.of(
            new RiskOption("A", "5 年以上", 5),
            new RiskOption("B", "3–5 年", 4),
            new RiskOption("C", "1–3 年", 3),
            new RiskOption("D", "6 个月–1 年", 2),
            new RiskOption("E", "6 个月以内", 1))),
    Q3("承受能力·客观", "投入资金占您可投资金融资产的比例？", List.of(
            new RiskOption("A", "小部分，大部分另有安排", 5),
            new RiskOption("B", "约三成", 4),
            new RiskOption("C", "约一半", 3),
            new RiskOption("D", "约七成", 2),
            new RiskOption("E", "几乎全部", 1))),
    Q4("承受能力·客观", "您的收入状况更接近？", List.of(
            new RiskOption("A", "收入稳定且持续增长", 5),
            new RiskOption("B", "收入稳定", 4),
            new RiskOption("C", "略有波动", 3),
            new RiskOption("D", "波动较大", 2),
            new RiskOption("E", "无固定收入", 1))),
    Q5("容忍意愿·主观", "组合单月下跌多少您会明显不安？", List.of(
            new RiskOption("A", "超过 30% 也可接受", 5),
            new RiskOption("B", "20%–30%", 4),
            new RiskOption("C", "10%–20%", 3),
            new RiskOption("D", "5%–10%", 2),
            new RiskOption("E", "5% 以内", 1))),
    Q6("容忍意愿·主观", "组合亏损 10% 时，您会？", List.of(
            new RiskOption("A", "逢低加仓摊低成本", 5),
            new RiskOption("B", "继续持有等待回升", 4),
            new RiskOption("C", "卖出部分降低仓位", 3),
            new RiskOption("D", "卖出大部分", 2),
            new RiskOption("E", "全部清仓并远离市场", 1))),
    Q7("容忍意愿·主观", "收益与波动，您更看重？", List.of(
            new RiskOption("A", "追求长期高收益，可接受大波动", 5),
            new RiskOption("B", "偏向较高收益，容忍一定波动", 4),
            new RiskOption("C", "收益与波动兼顾", 3),
            new RiskOption("D", "偏向稳健，波动小一些", 2),
            new RiskOption("E", "保本优先", 1))),
    Q8("经验", "您参与股票/基金等权益投资的年限？", List.of(
            new RiskOption("A", "10 年以上", 5),
            new RiskOption("B", "5–10 年", 4),
            new RiskOption("C", "3–5 年", 3),
            new RiskOption("D", "1–3 年", 2),
            new RiskOption("E", "1 年以内", 1)));

    private final String dimension;
    private final String text;
    private final List<RiskOption> options;

    RiskQuestion(String dimension, String text, List<RiskOption> options) {
        this.dimension = dimension;
        this.text = text;
        this.options = List.copyOf(options);
    }

    public String dimension() { return dimension; }
    public String text() { return text; }
    public List<RiskOption> options() { return options; }

    /** 取该题某选项的分值；选项不属于该题抛 INVALID_ANSWERS。 */
    public int scoreOf(String optionId) {
        return options.stream()
                .filter(o -> o.id().equals(optionId))
                .findFirst()
                .map(RiskOption::score)
                .orElseThrow(() -> new AllocationException(AllocationErrorCode.INVALID_ANSWERS,
                        "选项不合法: " + name() + "/" + optionId));
    }
}
