package com.portfolio.invest.domain.intelligence;

import java.util.Optional;

/**
 * 飞书绑定仓库端口（intelligence_feishu_binding 单表，V3 D8）：user_id 主键、open_id 唯一，
 * 重复绑定覆盖本行。本端口承载读取（定向推送以 open_id 单发，未绑定返回 empty 留痕
 * SKIPPED_NO_BINDING）与解绑（P4 Task 2 设置页）；绑定写入/核销属 P4 Task 5
 * （BindingCommandHandler）按需扩展。事务边界在 application 层（照既有仓库先例）。
 */
public interface FeishuBindingRepository {

    /** 用户已绑定的飞书 open_id；未绑定返回 empty。 */
    Optional<String> findOpenIdByUserId(Long userId);

    /**
     * 解绑：删除该用户绑定行（open_id 随行释放——解绑后立即不再收定向推送）。
     *
     * @return true = 已删除；false = 本就未绑定（幂等语义）
     */
    boolean deleteByUserId(Long userId);
}
