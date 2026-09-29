package com.portfolio.invest.application.intelligence;

/**
 * 新闻 LLM 结构化抽取提示词（D6）：系统提示词钉死 JSON 输出契约与受控枚举，
 * 用户提示词只装标题+摘要。抽取核心 {@link NewsExtractor} 与 Task 13 eval runner 共用本常量。
 */
public final class NewsExtractPrompt {

    /** 输出契约：单 JSON 对象七字段；direction 受控枚举；无法判断填 null；仅 JSON 无围栏。 */
    public static final String SYSTEM_PROMPT = """
            你是 A 股财经新闻结构化抽取器。对给定的新闻（标题+摘要）抽取以下字段，输出一个 JSON 对象：
            {
              "event_type": "简短英文事件类型标签，如 EARNINGS、POLICY、M&A、GUIDANCE、MACRO、PRODUCT、LEGAL、OTHER，无法判断填 null",
              "stock_codes": ["关联 A 股证券代码，如 600519、000858、300750；无关联则空数组"],
              "industry_codes": ["关联申万行业代码，如 801250（白酒）；无关联则空数组"],
              "summary": "一句话中文摘要，不超过 80 字",
              "direction": "对相关标的的影响方向，BULLISH、BEARISH、NEUTRAL 三选一，无法判断填 null",
              "key_numbers": ["关键数字，各自带口径描述，如：营收 12.34 亿元、净利同比 +25.3%；无则空数组"],
              "importance": 对 A 股投资者的重要度，0 到 100 的整数
            }
            约束：
            - 仅输出 JSON 对象本身，不要 Markdown 代码围栏，不要任何解释文字。
            - direction 只能是 BULLISH、BEARISH、NEUTRAL 或 null。
            - importance 必须是 0 到 100 的整数。
            - 摘要、关键数字只能来自给定新闻内容，严禁编造；无法判断的字段填 null（数组字段填 []）。""";

    private NewsExtractPrompt() {
    }

    /** 用户提示词：标题+摘要（抽取唯一输入，D7 全量过批不做预过滤）。 */
    public static String userPrompt(String title, String summary) {
        return "新闻标题：%s\n新闻摘要：%s\n请输出 JSON。".formatted(
                title == null ? "" : title.trim(),
                summary == null || summary.isBlank() ? "（无摘要）" : summary.trim());
    }
}
