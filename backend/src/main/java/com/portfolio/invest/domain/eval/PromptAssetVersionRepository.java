package com.portfolio.invest.domain.eval;

import java.util.List;
import java.util.Optional;

/**
 * 提示词资产版本仓库端口（infrastructure/persistence/eval 实现，风格 A JdbcTemplate）：
 * 启动对账（application/eval 的 PromptVersionRegistrar）与 eval 收割回流（EvalHarvester，
 * Task 6 消费）共用同一对账语义。
 *
 * <p>对账命中查询按 idx_prompt_asset_latest 前缀（asset_type + asset_key）定位后过滤
 * content_hash——Task 1 裁定不另建 (asset_key, content_hash) 索引（表规模约 31 资产 ×
 * 少量版本，前缀扫描足够）。
 */
public interface PromptAssetVersionRepository {

    /**
     * 对账命中：该 (asset_type, asset_key) 下是否已存在该 content_hash 的版本行。
     *
     * @return 命中版本的 version；未命中 empty（调用方据此 insert version+1 新行）
     */
    Optional<Integer> findVersionByHash(String assetType, String assetKey, String contentHash);

    /** 插入新版本行（append-only；(asset_type, asset_key, version) 唯一约束兜底并发双写）。 */
    int insert(PromptAssetVersion version);

    /** 全资产最新版本快照（每 (asset_type, asset_key) 取 version 最高行），供新版本号计算与版本链看板。 */
    List<PromptAssetVersion> latestSnapshot();
}
