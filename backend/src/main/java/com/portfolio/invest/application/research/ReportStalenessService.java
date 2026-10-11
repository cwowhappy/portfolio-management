package com.portfolio.invest.application.research;

import com.portfolio.invest.application.cache.ApplicationCache;
import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.research.ReportAnchorRepository;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 研报假设过期重查服务（MS-32 B12 / F02，设计规格 §3.8）：detail 打开时对研报全部锚点
 * 做瞬态四态计算，结果整体入 {@link ApplicationCache}（键 {@code report-staleness:{reportId}}，
 * TTL = {@code invest.report.staleness.recheck-ttl} 默认 5 分钟——防抖：TTL 内重复打开不重查）。
 * 锚点行创建后不可变（审计真相），本服务全程只读、状态不落库。
 *
 * <p>调用方（B11 detail 编排）须先完成研报归属校验（非本人 404），本服务不再校验——
 * 缓存键不带 userId（同键跨用户共享，归属由调用方先校验保证）；userId 仅为
 * search_announcements/search_wiki 两类用户态工具的重查入参。
 * 缓存值含「重查时刻」语义：TTL 即重查结果的最大陈旧窗（B11 以 stalenessCheckedAt 透出）。
 */
@Service
public class ReportStalenessService {

    /** 缓存键前缀（§3.8：report-staleness:{reportId}）。 */
    static final String CACHE_KEY_PREFIX = "report-staleness:";

    /** 重查四态（F02 假设徽标）：FRESH 现值再现 / STALE 数据时点超线 / CHANGED 现值漂移 / UNVERIFIABLE 不可重查。 */
    public enum AnchorState { FRESH, STALE, CHANGED, UNVERIFIABLE }

    /** 单锚点重查结果（currentValue = 与锚定值偏差最小的现值 plain string，不可得为 null；B11 detail 消费）。 */
    public record AnchorStatus(Long anchorId, AnchorState state, String currentValue) {}

    private final ReportAnchorRepository anchors;
    private final ApplicationCache cache;
    private final ReportAnchorRecheckPort rechecker;
    private final InvestProperties properties;

    public ReportStalenessService(ReportAnchorRepository anchors, ApplicationCache cache,
                                  ReportAnchorRecheckPort rechecker, InvestProperties properties) {
        this.anchors = anchors;
        this.cache = cache;
        this.rechecker = rechecker;
        this.properties = properties;
    }

    /**
     * 研报全部锚点的重查状态（§3.8）：缓存命中直返；miss 逐锚点重查
     * （{@link ReportAnchorRecheckPort}——agent 侧白名单直调实现）后全量按 TTL 写缓存。
     * 锚点顺序沿仓库返回序（id 升序）；全程只读不回写。
     */
    public List<AnchorStatus> statusesFor(Long userId, Long reportId) {
        List<AnchorStatus> cached = cache.get(CACHE_KEY_PREFIX + reportId);
        if (cached != null) {
            return cached;
        }
        List<AnchorStatus> statuses = anchors.findByReportId(reportId).stream()
                .map(anchor -> rechecker.recheck(userId, anchor))
                .toList();
        cache.put(CACHE_KEY_PREFIX + reportId, statuses, recheckTtl());
        return statuses;
    }

    private Duration recheckTtl() {
        return properties.getReport().getStaleness().recheckTtl();
    }
}
