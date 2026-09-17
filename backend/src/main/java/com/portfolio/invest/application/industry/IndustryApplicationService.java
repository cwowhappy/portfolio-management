package com.portfolio.invest.application.industry;

import com.portfolio.invest.application.cache.ApplicationCache;
import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.industry.IndustryErrorCode;
import com.portfolio.invest.domain.industry.IndustryException;
import com.portfolio.invest.domain.industry.IndustryProsperitySnapshot;
import com.portfolio.invest.domain.industry.IndustryRepository;
import com.portfolio.invest.domain.industry.IndustryStock;
import com.portfolio.invest.domain.industry.IndustryValuationPoint;
import com.portfolio.invest.domain.industry.IndustryValuationRow;
import com.portfolio.invest.domain.industry.Prosperity;
import com.portfolio.invest.domain.industry.WindowedPercentile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** 行业板面/成分股应用服务（MS-09）：纯函数组装 + 端口读 + 应用级缓存。 */
@Service
public class IndustryApplicationService {

    /** stocks 排序字段白名单（对齐 F03 查询列）。 */
    static final Set<String> SORTABLE = Set.of("total_mv", "revenue", "roe");
    static final int MAX_LIMIT = 1000;

    private final IndustryRepository repository;
    private final ApplicationCache cache;
    /** 行业板面/成分股缓存 TTL（默认 5min；生产经 invest.app-cache.ttl 配置）。 */
    private final Duration cacheTtl;

    @Autowired
    public IndustryApplicationService(IndustryRepository repository, ApplicationCache cache, InvestProperties props) {
        this(repository, cache, props.getAppCache().getTtl());
    }

    public IndustryApplicationService(IndustryRepository repository, ApplicationCache cache) {
        this(repository, cache, Duration.ofMinutes(5));
    }

    IndustryApplicationService(IndustryRepository repository, ApplicationCache cache, Duration cacheTtl) {
        this.repository = repository;
        this.cache = cache;
        this.cacheTtl = cacheTtl;
    }

    public List<IndustryBoardView> board() {
        return cached("board:" + LocalDate.now(), this::loadBoard);
    }

    private List<IndustryBoardView> loadBoard() {
        List<IndustryValuationRow> latest = repository.findLatestIndustries();
        List<IndustryValuationPoint> history = repository.findValuationHistorySince(LocalDate.now().minusYears(5));
        Map<String, List<BigDecimal>> peBy = history.stream().collect(Collectors.groupingBy(
                IndustryValuationPoint::industryCode, Collectors.mapping(IndustryValuationPoint::pe, Collectors.toList())));
        Map<String, List<BigDecimal>> pbBy = history.stream().collect(Collectors.groupingBy(
                IndustryValuationPoint::industryCode, Collectors.mapping(IndustryValuationPoint::pb, Collectors.toList())));
        Map<String, IndustryProsperitySnapshot> prosperityBy = repository.findIndustryProsperity().stream()
                .collect(Collectors.toMap(IndustryProsperitySnapshot::industryCode, Function.identity(), (a, b) -> a));
        return latest.stream().map(row -> {
            IndustryProsperitySnapshot snap = prosperityBy.get(row.industryCode());
            // 快照存在但样本为 0：中位数无意义，不标注景气；中位数缺失由 Prosperity.of 自行返回 null。
            // 两种退化都仍透出 prosperityInputs 原始值（含 sampleSize=0），供前端提示「样本不足」。
            Prosperity prosperity = snap == null || snap.sampleSize() <= 0
                    ? null : Prosperity.of(snap.roeDeltaMedian(), snap.revenueYoyMedian());
            return new IndustryBoardView(row.industryCode(), row.industryName(),
                    row.pe(), row.pb(), row.roe(), row.dividendYield(),
                    WindowedPercentile.of(row.pe(), peBy.get(row.industryCode())),
                    WindowedPercentile.of(row.pb(), pbBy.get(row.industryCode())),
                    prosperity,
                    snap == null ? null : new IndustryBoardView.ProsperityInputs(
                            snap.roeDeltaMedian(), snap.revenueYoyMedian(), snap.sampleSize()));
        }).toList();
    }

    public List<IndustryStock> stocks(String industryCode, String sortBy, String sortDirection, int limit) {
        if (sortBy == null || !SORTABLE.contains(sortBy)
                || !"ASC".equals(sortDirection) && !"DESC".equals(sortDirection)) {
            throw new IndustryException(IndustryErrorCode.INVALID_SORT, "排序参数非法: " + sortBy + " " + sortDirection);
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IndustryException(IndustryErrorCode.INVALID_LIMIT, "limit 须在 1~" + MAX_LIMIT + " 之间");
        }
        if (!repository.existsIndustry(industryCode)) {
            throw new IndustryException(IndustryErrorCode.INDUSTRY_NOT_FOUND, "行业不存在: " + industryCode);
        }
        return cached(industryCode + ":stocks:" + sortBy + "|" + sortDirection + "|" + limit,
                () -> repository.findIndustryStocks(industryCode, sortBy, sortDirection, limit));
    }

    private <T> T cached(String kind, Supplier<T> loader) {
        // 应用级共享缓存（ApplicationCache 端口），key 带域前缀防冲突
        String key = "industry:" + kind;
        T hit = cache.get(key);
        if (hit != null) {
            return hit;
        }
        T value = loader.get();
        cache.put(key, value, cacheTtl);
        return value;
    }
}
