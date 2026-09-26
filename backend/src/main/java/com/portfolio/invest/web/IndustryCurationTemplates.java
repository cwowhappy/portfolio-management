package com.portfolio.invest.web;

/**
 * 策展 CSV 导入模板（设计规格 §五）：UTF-8 BOM 头（Excel 双击直接识别编码与列分隔）+
 * 表头 + 示例行。正文与 {@code UnlistedCompanyCsvParserTest}/{@code FundingEventCsvParserTest}
 * 样例逐行同源——用户下载的模板必须能原样通过解析层校验，两处样例不允许各自漂移。
 */
public final class IndustryCurationTemplates {

    public static final String COMPANIES_CSV = "﻿" + """
            industry_code,company_name,segment,latest_round,last_funding_date,total_funding_yi,summary,source_note
            801730,示例电池科技,动力电池,B,2026-08-15,120.50,动力电池新锐,爱企查人工核对
            """;

    public static final String FUNDING_EVENTS_CSV = "﻿" + """
            event_date,company_name,round,amount_yi,investors,industry_code,segment,source_title,source_url
            2026-08-15,某生物科技公司,B_PLUS,3.20,高瓴·红杉,801150,CXO,睿兽分析2026-08月报,
            """;

    private IndustryCurationTemplates() {}
}
