package com.portfolio.invest.application.observability;

import com.portfolio.invest.domain.eval.PromptAssetVersion;
import com.portfolio.invest.domain.eval.PromptAssetVersionRepository;
import com.portfolio.invest.domain.observability.ObservabilityQueryRepository;
import com.portfolio.invest.domain.observability.ObservabilityQueryRepository.DailyLatencyStat;
import com.portfolio.invest.domain.observability.ObservabilityQueryRepository.DailyTokenUsage;
import com.portfolio.invest.domain.observability.ObservabilityQueryRepository.ToolCallStat;
import com.portfolio.invest.domain.observability.ObservabilityQueryRepository.ToolLatencyStat;
import com.portfolio.invest.domain.observability.ObservabilityQueryRepository.TracePage;
import com.portfolio.invest.domain.observability.ObservabilityQueryRepository.TurnLatencyStat;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 可观测性看板查询用例（MS-30 B5，设计规格 §7.1）：trace 分页筛选透传、cost/latency 的
 * days→since 截断口径（同一截断喂两/三路聚合，Clock 双构造器沿 EvalHarvester 先例）、
 * 提示词版本链分组（版本链看板）。SQL 契约归 infrastructure 实现，本层只做用例编排与
 * 参数语义。
 */
@Service
public class ObservabilityApplicationService {

    private final ObservabilityQueryRepository queries;
    private final PromptAssetVersionRepository assetVersions;
    private final Clock clock;

    /** 主构造器（@Autowired：存在测试专用重载构造器时需显式指定注入入口）。 */
    @Autowired
    public ObservabilityApplicationService(ObservabilityQueryRepository queries,
                                           PromptAssetVersionRepository assetVersions) {
        this(queries, assetVersions, Clock.system(ZoneId.of("Asia/Shanghai")));
    }

    /** 测试便利构造：注入时钟（since 截断可断言）。 */
    ObservabilityApplicationService(ObservabilityQueryRepository queries,
                                    PromptAssetVersionRepository assetVersions, Clock clock) {
        this.queries = queries;
        this.assetVersions = assetVersions;
        this.clock = clock;
    }

    /** 工具调用明细分页（筛选参数语义见端口 {@link ObservabilityQueryRepository#findTrace}）。 */
    public TracePage trace(Instant from, Instant to, String tool, Boolean failed,
                           int page, int size) {
        return queries.findTrace(from, to, tool, failed, page, size);
    }

    /** 成本看板：按日 token 消耗（轮表）+ 按工具调用统计（工具表），同一 since 截断。 */
    public Cost cost(int days) {
        Instant since = cutoff(days);
        return new Cost(queries.tokenUsageByDay(since), queries.toolCallStats(since));
    }

    /** 时延看板：轮整体/按日 + 工具按日百分位，同一 since 截断。 */
    public Latency latency(int days) {
        Instant since = cutoff(days);
        return new Latency(queries.turnLatency(since), queries.turnLatencyByDay(since),
                queries.toolLatencyByTool(since));
    }

    /**
     * 提示词版本链（看板④）：按 (asset_type, asset_key) 分组、组内版本倒序最新居首——
     * current 标记由 web 层按「组内首元素」渲染（本层保证组内序）。
     */
    public List<PromptAssetChain> assetChains() {
        Map<String, PromptAssetChain> chains = new LinkedHashMap<>();
        for (PromptAssetVersion version : assetVersions.findVersionChain()) {
            chains.computeIfAbsent(version.assetType() + "/" + version.assetKey(),
                            key -> new PromptAssetChain(version.assetType(), version.assetKey(),
                                    new ArrayList<>()))
                    .versions().add(version);
        }
        return List.copyOf(chains.values());
    }

    private Instant cutoff(int days) {
        return Instant.now(clock).minus(Duration.ofDays(days));
    }

    /** 成本看板聚合形态（byDay 轮表 / byTool 工具表）。 */
    public record Cost(List<DailyTokenUsage> byDay, List<ToolCallStat> byTool) {}

    /** 时延看板聚合形态（turn 整体+按日 / tool 按工具）。 */
    public record Latency(TurnLatencyStat turn, List<DailyLatencyStat> byDay,
                          List<ToolLatencyStat> byTool) {}

    /** 版本链分组形态（versions 组内版本倒序）。 */
    public record PromptAssetChain(String assetType, String assetKey,
                                   List<PromptAssetVersion> versions) {}
}
