package com.portfolio.invest.application.alert;

import java.math.BigDecimal;

/** 巡检产出的一条违规：metricLabel 规则中文名 / subject 股票名(代码)或行业名 / current 当前值 / threshold 阈值。 */
public record AlertViolation(String metricLabel, String subject, BigDecimal current, BigDecimal threshold,
                             String description) {}
