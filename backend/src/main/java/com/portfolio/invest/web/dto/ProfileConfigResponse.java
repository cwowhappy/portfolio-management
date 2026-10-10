package com.portfolio.invest.web.dto;

/**
 * 画像授权配置 + 四段预览（MS-31 设计规格 §3.5：开关与预览一次拉取，同刻同源）。
 * preview 为 {@code ProfileSectionRenderer} 直出的段文本（与注入同一渲染路径），
 * 失败/为空段为 null（前端渲染「暂不可用」）。
 */
public record ProfileConfigResponse(Section style, Section industry, Section watchlist, Section holdings) {

    public record Section(boolean enabled, String preview) {}
}
