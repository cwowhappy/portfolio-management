package com.portfolio.invest.application.allocation;

/** 回测曲线点位：date=ISO-8601，value=净值字符串（期初 1000；首日与期初同日期两点为引擎既定约定）。 */
public record CurvePointView(String date, String value) {}
