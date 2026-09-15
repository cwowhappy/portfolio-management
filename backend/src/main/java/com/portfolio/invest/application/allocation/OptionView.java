package com.portfolio.invest.application.allocation;

/** 选项视图不含分值：评分口径不外泄（规格 NFR「评分口径一致性」）。 */
public record OptionView(String id, String text) {}
