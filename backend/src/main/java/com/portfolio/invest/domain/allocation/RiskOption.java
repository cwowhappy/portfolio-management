package com.portfolio.invest.domain.allocation;

/** 题目选项：id 为展示序标识（A 最高分 → E 最低分），score 为 1-5。 */
public record RiskOption(String id, String text, int score) {}
