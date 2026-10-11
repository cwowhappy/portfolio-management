package com.portfolio.invest.agent.report;

/**
 * 深研指引块（MS-32 B6，设计规格 §3.3）：{@link #BLOCK} 由 buildReportAgent（B8）追加在
 * {@code systemPrompt(userId)} 尾部（恒注入，非 skill——skill 走用户开关，需求 D1 要求
 * 强制生效）。六要素：①工作流三阶段说明 ②五源工具点名（含 search_wiki）③输出契约
 * （单个 {@code ```research-report} 围栏 JSON、数字必须直接引用工具返回值、禁凭记忆）
 * ④关键假设章节要求（编号列表、每条注明来源工具与数字）⑤limitations 要求 ⑥禁用
 * research_draft 工具（本工作流走最终消息出稿）。
 * 版本资产登记 system.invest.report（沿 MS-31 system.invest.profile 先例）。
 *
 * <p>B9 增四阶段用户指令模板（{@link #COLLECT_PROMPT_TEMPLATE}/{@link #ANALYZE_PROMPT_TEMPLATE}/
 * {@link #DRAFT_PROMPT_TEMPLATE}/{@link #REGEN_PROMPT_TEMPLATE}）——编排器逐轮 String.format
 * 下发（点名项目 stockName/stockCode/title）；仅 BLOCK 入版本资产链，阶段模板为编排内部指令不入链。
 */
public final class ReportWorkflowPrompt {

    public static final String BLOCK = """
            ## 深度研究工作流（研报任务模式）
            当前会话是研报任务，与日常问答不同：系统会分多轮下发阶段指令，请按以下三阶段推进，最终产出一份结构化研报。

            ### 工作流三阶段
            1. 收集阶段：围绕研究项目的标的与行业，用下方五源工具充分取数；本阶段产出工具清单式中间小结（调用了哪些工具、各拿到哪些关键数据）
            2. 分析阶段：基于已收集数据展开分析——估值水平、财务质量、情报信号（新闻/公告/宏观）、主要风险；一般不再大规模重复取数，缺什么补什么
            3. 草稿阶段：整合前两轮成果撰写研报，最终消息只包含一个研报围栏块（输出契约见下）

            ### 数据来源五源（本任务只用内置工具）
            - 行情：get_quote（实时行情）、get_kline（K线走势）、get_market_overview（大盘总览）
            - 财务与估值：get_financials（核心财务指标）、analyze_financials（财报拆解）、get_valuation（估值参考）、analyze_industry（行业对比）
            - 新闻情报：search_news（近 90 天结构化情报，引用注明方向与重要度）
            - 公告与宏观：search_announcements（公告与业绩要点，引用附原文链接）、macro_brief（宏观五指标与政策事件，引用注明期别）
            - 用户知识库：search_wiki（检索用户投资笔记与研究心得，作观点佐证或框架参考）

            ### 输出契约（草稿阶段最终消息）
            最终消息只包含一个 ```research-report 围栏块，围栏内是合法 JSON 对象，字段固定四个：
            {"specVersion": 1, "title": "研报标题", "contentMd": "研报全文 markdown", "limitations": "数据局限注记"}
            - 所有数字必须直接引用本轮工具返回值，禁止凭记忆填写、禁止编造；二次计算单独成段展示计算过程
            - 工具失败或数据缺失处如实说明，不得用推测数字顶替

            ### contentMd 章节要求
            - 必须包含「## 关键假设」章节：编号列表（1. 2. 3. …），每条假设注明来源工具与所依据的数字
            - 必须如实填写 limitations：注明哪些工具失败、哪些数据缺失、时效性风险等；确无局限时写「无特别局限」
            - 禁止使用 research_draft 工具：本工作流不走对话草稿卡，研报一律经最终消息围栏块出稿
            """;

    /**
     * 收集阶段用户指令模板（B9 编排器逐轮下发；占位序：%1$s=stockName %2$s=stockCode %3$s=title）。
     * 产出工具清单式中间小结，数字原样引用工具返回值（供后续阶段锚定）。
     */
    public static final String COLLECT_PROMPT_TEMPLATE = """
            【研究项目：%3$s——%1$s（%2$s）】
            现在进入收集阶段：围绕 %1$s（%2$s）用五源工具充分取数，覆盖——
            - 实时行情与近期走势：get_quote / get_kline / get_market_overview
            - 核心财务与估值：get_financials / analyze_financials / get_valuation
            - 行业对比：analyze_industry
            - 近期情报与公告：search_news / search_announcements / macro_brief
            - 你的既有研究笔记：search_wiki
            本阶段产出：工具清单式中间小结——调用了哪些工具、各拿到哪些关键数据，数字原样引用工具返回值，不做推断加工。
            """;

    /** 分析阶段用户指令模板（占位序同上；引用收集阶段已入会话的数据，不重发原文）。 */
    public static final String ANALYZE_PROMPT_TEMPLATE = """
            【研究项目：%3$s——%1$s（%2$s）】
            现在进入分析阶段：基于收集阶段已获取的数据展开分析，一般不再大规模重复取数（缺什么补什么）：
            - 估值水平：现价与市值、市盈率/市净率、行业与历史分位
            - 财务质量：盈利能力、成长性、现金流
            - 情报信号：新闻/公告的方向与重要度、宏观环境影响
            - 主要风险：业务、估值、市场三个维度
            本阶段产出：结论式分析小结，数字必须直接引用已收集的工具返回值，禁止凭记忆填写。
            """;

    /** 草稿阶段用户指令模板（占位序同上）：最终消息 = 单个 research-report 围栏 JSON。 */
    public static final String DRAFT_PROMPT_TEMPLATE = """
            【研究项目：%3$s——%1$s（%2$s）】
            现在进入草稿阶段：整合前两轮成果撰写研报。最终消息只包含一个 ```research-report 围栏块，围栏内是合法 JSON 对象，字段固定四个：
            {"specVersion": 1, "title": "研报标题", "contentMd": "研报全文 markdown", "limitations": "数据局限注记"}
            - contentMd 必须包含「## 关键假设」章节：编号列表（1. 2. 3. …），每条假设注明来源工具与所依据的数字
            - 所有数字必须直接引用本轮工具返回值；二次计算单独成段展示计算过程
            - limitations 如实填写工具失败、数据缺失与时效性风险；确无局限时写「无特别局限」
            """;

    /**
     * 回炉轮指令模板（占位序：%1$s=失败反馈清单——校验门 feedbackLines 或格式错误消息）：
     * 门失败与 payload 格式失败共用（整任务格式反馈重试仅 1 轮、门回炉 ≤ max-regen-rounds，
     * 由编排器计数控制）。
     */
    public static final String REGEN_PROMPT_TEMPLATE = """
            上一稿未通过校验，问题清单如下：
            %1$s
            请重新输出完整的研报：最终消息只包含一个 ```research-report 围栏块（围栏内 JSON 契约与系统提示一致，specVersion=1、title/contentMd/limitations 四字段齐全）。
            - 数字误述处改用工具返回原值；无真值支撑的数字删除，或先补工具调用取证再引用
            - 「## 关键假设」章节保留：编号列表，每条假设注明来源工具与所依据的数字
            - 重新取数会更新会话内数据，引用以最新工具返回值为准
            """;

    private ReportWorkflowPrompt() {}
}
