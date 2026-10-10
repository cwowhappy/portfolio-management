package com.portfolio.invest.web.dto;

/**
 * 画像授权四开关全量保存请求体（沿 {@link SaveSkillConfigRequest} 先例，无 Bean Validation：
 * 四值恒全量提交，缺省键按 false 处理）。
 */
public record SaveProfileConfigRequest(boolean style, boolean industry, boolean watchlist, boolean holdings) {}
