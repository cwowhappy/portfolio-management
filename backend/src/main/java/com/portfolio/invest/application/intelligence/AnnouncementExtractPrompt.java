package com.portfolio.invest.application.intelligence;

/**
 * 公告 LLM 结构化抽取提示词（F07/决策 #11，设计规格 §4.4）：系统提示词钉死六字段业绩要点
 * 契约（未披露填 null 且字段名进 undisclosed，严禁编造）与 annTypes 受控十类枚举，用户
 * 提示词只装标题 + PDF 正文（截前 {@link #MAX_PDF_TEXT_CHARS} 字符）。抽取核心
 * {@link AnnouncementExtractor} 与 Task 9 eval runner 共用本常量。
 */
public final class AnnouncementExtractPrompt {

    /** PDF 正文输入上限（字符）：年报全文动辄数十万字，只喂前 8000 字符（摘要/主要会计数据节在前）。 */
    public static final int MAX_PDF_TEXT_CHARS = 8000;

    /** 输出契约：单 JSON 对象 metrics（六字段 + undisclosed）+ annTypes（十类 + OTHER）；未披露填 null 严禁编造。 */
    public static final String SYSTEM_PROMPT = """
            你是 A 股上市公司公告结构化抽取器。对给定的公告（标题 + PDF 正文文本）抽取业绩要点与公告类型，输出一个 JSON 对象：
            {
              "metrics": {
                "revenueYi": 营业收入，单位亿元，数字；未披露填 null,
                "netProfitYi": 归母净利润，单位亿元，数字；未披露填 null,
                "netProfitYoyPct": 归母净利润同比，单位%，相对上年同期，数字；未披露填 null,
                "deductedProfitYi": 扣非净利润，单位亿元，数字；未披露填 null,
                "grossMarginPct": 毛利率，单位%，数字；未披露填 null,
                "dividendDesc": 分红描述，如"每10股派2元"；未披露填 null,
                "undisclosed": ["未披露字段的规范名：营业收入、归母净利润、归母净利润同比、扣非净利润、毛利率、分红；全部已披露则为空数组"]
              },
              "annTypes": ["公告类型，只能从以下枚举名中选择：INCREASE_HOLD（增持）、DECREASE_HOLD（减持）、BUYBACK（回购）、PLACEMENT（定增配股）、RELATED_TRANSACTION（关联交易）、EARNINGS_FORECAST（业绩预告）、EARNINGS_FLASH（业绩快报）、PERIODIC_REPORT（定期报告）、EQUITY_INCENTIVE（股权激励）、DELISTING_RISK（退市风险）、OTHER（其他）；可多选，无法判断填空数组"]
            }
            约束：
            - 仅输出 JSON 对象本身，不要 Markdown 代码围栏，不要任何解释文字。
            - 六字段数值只能来自给定公告内容，严禁编造或推算；未披露的字段必须填 null，并把该字段的规范中文名加入 undisclosed 数组。
            - 同比（Yoy）口径为与上年同期相比；金额一律换算为亿元、比率一律换算为百分数。
            - 非业绩类公告（如增持、回购、关联交易、定增）没有业绩数据：metrics 各字段全部填 null，六个字段名全部进 undisclosed。
            - annTypes 只能是上述十个枚举名 + OTHER 之一，不得自造类型。""";

    private AnnouncementExtractPrompt() {
    }

    /**
     * 用户提示词：标题 + PDF 正文（截前 {@link #MAX_PDF_TEXT_CHARS} 字符并附截断标记）。
     * 正文缺失/空白（无 pdf_url 或空文本层降级）标注「仅凭标题判断」。
     */
    public static String userPrompt(String title, String pdfText) {
        String trimmedTitle = title == null ? "" : title.trim();
        String text = pdfText == null ? "" : pdfText.strip();
        String body;
        if (text.isEmpty()) {
            body = "（无PDF正文文本，仅凭标题判断）";
        } else if (text.length() > MAX_PDF_TEXT_CHARS) {
            body = text.substring(0, MAX_PDF_TEXT_CHARS)
                    + "\n（PDF正文超长，已截断至前 " + MAX_PDF_TEXT_CHARS + " 字符）";
        } else {
            body = text;
        }
        return "公告标题：%s\n公告PDF正文：%s\n请输出 JSON。".formatted(trimmedTitle, body);
    }
}
