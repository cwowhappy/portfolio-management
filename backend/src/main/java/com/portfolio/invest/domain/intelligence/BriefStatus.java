package com.portfolio.invest.domain.intelligence;

/** 盘前简报归档三态（D17）：正常生成 / 空简版（决策 #22）/ 生成失败留档。 */
public enum BriefStatus { GENERATED, EMPTY_SIMPLE, FAILED }
