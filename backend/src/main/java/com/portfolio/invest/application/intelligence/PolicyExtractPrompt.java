package com.portfolio.invest.application.intelligence;

/**
 * 政策 LLM 结构化抽取提示词（F11/F12，MS-22 Task 5）：系统提示词钉死六字段 JSON 输出契约
 * （三受控枚举 + isPolicy 判据 + 无依据填 null 严禁编造），用户提示词只装标题 + 政策正文
 * （截前 {@link #MAX_CONTENT_CHARS} 字符）。抽取核心 {@link PolicyExtractor} 与 Task 8
 * eval runner 共用本常量。
 */
public final class PolicyExtractPrompt {

    /** 政策正文输入上限（字符）：collector 侧已截 8000 字，此处再截前 6000（政策文头含核心条款）。 */
    public static final int MAX_CONTENT_CHARS = 6000;

    /**
     * 输出契约：单 JSON 对象六字段；direction/strength/confidence 受控枚举；
     * isPolicy 布尔判据（仅正式政策发文 true，领导活动/会议新闻/转载/行政事务 false）；
     * 无法判断填 null；仅 JSON 无围栏。
     */
    public static final String SYSTEM_PROMPT = """
            你是中国宏观政策事件结构化抽取器。对给定的政策网站内容（标题 + 正文）抽取以下字段，输出一个 JSON 对象：
            {
              "isPolicy": 是否政策类发布，true 或 false。仅正式政策发文（法律/法规/条例/通知/办法/意见/决定/指引/公告/方案等）为 true；领导活动、会议新闻、转载报道、行政事务动态为 false,
              "direction": "政策取向，EASING（宽松，如降息降准减税放松管制）、TIGHTENING（收紧，如加息收紧监管收紧杠杆）、NEUTRAL（中性或方向不明）三选一，无法判断填 null",
              "strength": "政策力度，HIGH（强，超预期/全面/大幅）、MEDIUM（中，常规力度）、LOW（弱，边际/局部/试点）三选一，无法判断填 null",
              "affectedAreas": ["影响领域，中文短语，如：利率、房地产、基建、资本市场、制造业、外贸；无明确领域则空数组"],
              "summary": "一句话中文摘要，不超过 80 字",
              "confidence": "判断置信度，HIGH（正文依据充分）、LOW（依据不足或含推测）二选一，无法判断填 null"
            }
            约束：
            - 仅输出 JSON 对象本身，不要 Markdown 代码围栏，不要任何解释文字。
            - direction 只能是 EASING、TIGHTENING、NEUTRAL 或 null；strength 只能是 HIGH、MEDIUM、LOW 或 null；confidence 只能是 HIGH、LOW 或 null。
            - isPolicy 必须是 true 或 false：仅政策类发布（法规/通知/办法/意见/决定等正式发文）为 true；领导活动/会议新闻/转载/行政事务为 false。
            - 摘要与影响领域只能来自给定内容，无依据填 null（数组字段填 []），严禁编造。""";

    private PolicyExtractPrompt() {
    }

    /**
     * 用户提示词：标题 + 政策正文（截前 {@link #MAX_CONTENT_CHARS} 字符并附截断标记）。
     * 正文缺失/空白（collector 详情页抓取失败落空）标注「仅凭标题判断」。
     */
    public static String userPrompt(String title, String contentText) {
        String trimmedTitle = title == null ? "" : title.trim();
        String text = contentText == null ? "" : contentText.strip();
        String body;
        if (text.isEmpty()) {
            body = "（无正文文本，仅凭标题判断）";
        } else if (text.length() > MAX_CONTENT_CHARS) {
            body = text.substring(0, MAX_CONTENT_CHARS)
                    + "\n（正文超长，已截断至前 " + MAX_CONTENT_CHARS + " 字符）";
        } else {
            body = text;
        }
        return "政策标题：%s\n政策正文：%s\n请输出 JSON。".formatted(trimmedTitle, body);
    }
}
