package com.portfolio.invest.application.research;

/**
 * 情报订阅挂接接口位（F11，D14 顺延）：M15 交付后实现——项目进入 POSITION 阶段挂接情报订阅，
 * 经 ADR-0013 飞书通道推送。当前仅留接口位锚定扩展点，无实现、无调用方；实现交付时按
 * {@link ResearchFalsifierNotifier} 同款端口模式补 infrastructure 实现与装配。
 * 2026-09-29 随 M15 命名原则（全名 intelligence，禁用 intel 缩写）由 IntelSubscriptionHook 改名。
 */
public interface IntelligenceSubscriptionHook {
}
