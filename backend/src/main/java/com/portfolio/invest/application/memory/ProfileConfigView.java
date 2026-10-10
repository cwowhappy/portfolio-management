package com.portfolio.invest.application.memory;

/** 画像授权四开关视图（user_profile_config，每用户至多一行；无行 = 全关）。 */
public record ProfileConfigView(boolean style, boolean industry, boolean watchlist, boolean holdings) {}
