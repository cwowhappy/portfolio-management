package com.portfolio.invest.domain.intelligence;

/** 推送结果三态（intelligence_push_log.status CHECK）：SKIPPED_NO_BINDING 为 P2 Task 7 契约
 * （订阅命中但用户未绑飞书，跳过单发仍留痕），建表已含、不因后到缩窄。 */
public enum PushStatus { OK, FAIL, SKIPPED_NO_BINDING }
