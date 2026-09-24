package com.portfolio.invest.domain.analytics;

import java.math.BigDecimal;
import java.util.Map;

/** 基准指数行业权重读侧端口：index_constituent × shenwan_industry_mapping 聚合，归一化小数（Σ=1）。 */
public interface BenchmarkIndustryWeightPort {

    String UNMAPPED_KEY = "UNMAPPED";

    Map<String, BigDecimal> industryWeights(String indexCode);
}
