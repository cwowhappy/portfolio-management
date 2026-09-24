package com.portfolio.invest.domain.analytics;

import java.util.Map;

/** 个股→申万行业映射读侧端口（shenwan_industry_mapping，V3 契约，周更任务写入）。 */
public interface IndustryMappingPort {

    record IndustryRef(String industryCode, String industryName) {}

    Map<String, IndustryRef> byStock();
}
