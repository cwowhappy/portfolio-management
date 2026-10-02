package com.portfolio.invest.domain.intelligence;

import java.util.Optional;

/**
 * 飞书绑定仓库端口（intelligence_feishu_binding 单表，V3 D8）：user_id 主键、open_id 唯一，
 * 重复绑定覆盖本行。绑定写入/解绑流程属 P4（SubscriptionService 绑定面），本端口现阶段
 * 只承载读取——公告定向推送（Task 7）以 open_id 单发；未绑定返回 empty，调用方留痕
 * SKIPPED_NO_BINDING（P4 绑定就位后自然生效，无需改推送侧）。
 */
public interface FeishuBindingRepository {

    /** 用户已绑定的飞书 open_id；未绑定返回 empty。 */
    Optional<String> findOpenIdByUserId(Long userId);
}
