package com.portfolio.invest.application.screening;

import com.portfolio.invest.application.cache.ApplicationCache;
import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.screening.ScreeningCriteria;
import com.portfolio.invest.domain.screening.ScreeningErrorCode;
import com.portfolio.invest.domain.screening.ScreeningException;
import com.portfolio.invest.domain.screening.ScreeningRepository;
import com.portfolio.invest.domain.screening.StockScreeningResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.StringJoiner;

@Service
public class ScreeningApplicationService {

    private final ScreeningRepository repository;
    private final ApplicationCache cache;
    /** 匿名宽扫结果缓存 TTL（默认 5min；生产经 invest.app-cache.ttl 配置）。 */
    private final Duration cacheTtl;

    @Autowired
    public ScreeningApplicationService(ScreeningRepository repository, ApplicationCache cache, InvestProperties props) {
        this(repository, cache, props.getAppCache().getTtl());
    }

    public ScreeningApplicationService(ScreeningRepository repository, ApplicationCache cache) {
        this(repository, cache, Duration.ofMinutes(5));
    }

    ScreeningApplicationService(ScreeningRepository repository, ApplicationCache cache, Duration cacheTtl) {
        this.repository = repository;
        this.cache = cache;
        this.cacheTtl = cacheTtl;
    }

    public List<StockScreeningResult> screen(ScreeningCriteria criteria) {
        if (!criteria.hasAnyCondition()) {
            throw new ScreeningException(ScreeningErrorCode.NO_CONDITION, "至少需要一个筛选条件");
        }
        if (criteria.sortBy() == null || !ScreeningCriteria.SORTABLE_FIELDS.contains(criteria.sortBy())) {
            throw new ScreeningException(ScreeningErrorCode.INVALID_SORT, "不支持的排序字段: " + criteria.sortBy());
        }
        if (criteria.limit() < 1 || criteria.limit() > 200) {
            throw new ScreeningException(ScreeningErrorCode.INVALID_LIMIT, "结果上限需在 1~200 之间");
        }
        String key = cacheKey(criteria);
        List<StockScreeningResult> hit = cache.get(key);
        if (hit != null) {
            return hit;
        }
        List<StockScreeningResult> result = repository.findStocks(criteria);
        cache.put(key, result, cacheTtl);
        return result;
    }

    /** 匿名公开端点的宽扫结果按完整查询条件为 key 缓存，避免同一筛选反复对全市场宽扫。 */
    private static String cacheKey(ScreeningCriteria c) {
        StringJoiner joiner = new StringJoiner("|");
        joiner.add(String.valueOf(c.peTtmMax())).add(String.valueOf(c.pbMax()))
                .add(String.valueOf(c.dividendYieldMin())).add(String.valueOf(c.roeMin()))
                .add(String.valueOf(c.roaMin())).add(String.valueOf(c.grossMarginMin()))
                .add(String.valueOf(c.debtToAssetsMax())).add(String.valueOf(c.currentRatioMin()))
                .add(String.valueOf(c.revenueYoyMin())).add(String.valueOf(c.netprofitYoyMin()))
                .add(String.valueOf(c.totalMvMin())).add(String.valueOf(c.turnoverRateMin()))
                .add(String.valueOf(c.industryCode())).add(c.sortBy())
                .add(String.valueOf(c.sortDirection())).add(String.valueOf(c.limit()));
        return "screening:stocks:" + joiner;
    }
}
