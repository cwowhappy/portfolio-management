package com.portfolio.invest.web.dto;

import com.portfolio.invest.application.observability.ObservabilityApplicationService;
import com.portfolio.invest.domain.eval.PromptAssetVersion;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

/**
 * 提示词版本链视图（GET /api/admin/prompt-assets，MS-30 B5 §7.1）：按资产分组的版本链，
 * 组内版本倒序、最新版 current=true；id 为<b>版本行</b>主键（补注 PUT 的目标键，规格 §7.1
 * 版本项未列 id 属疏漏——无 id 前端无法定位补注目标，此处补上）。
 */
public record PromptAssetsView(List<AssetChain> assets) {

    /** 单资产版本链。 */
    public record AssetChain(String assetType, String assetKey, List<Version> versions) {}

    /** 版本行（current=组内最新）。 */
    public record Version(long id, int version, String contentHash, String note,
                          Instant registeredAt, boolean current) {}

    /** 用例分组 → 视图（组内首元素即最新 → current=true）。 */
    public static PromptAssetsView from(List<ObservabilityApplicationService.PromptAssetChain> chains) {
        return new PromptAssetsView(chains.stream()
                .map(chain -> new AssetChain(chain.assetType(), chain.assetKey(),
                        versionsOf(chain)))
                .toList());
    }

    private static List<Version> versionsOf(ObservabilityApplicationService.PromptAssetChain chain) {
        List<PromptAssetVersion> versions = chain.versions();
        return IntStream.range(0, versions.size())
                .mapToObj(i -> {
                    PromptAssetVersion v = versions.get(i);
                    return new Version(v.id(), v.version(), v.contentHash(), v.note(),
                            v.registeredAt(), i == 0);
                })
                .toList();
    }
}
