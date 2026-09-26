package com.portfolio.invest.application.industry;

import com.portfolio.invest.domain.industry.FundingEvent;
import com.portfolio.invest.domain.industry.FundingEventRepository;
import com.portfolio.invest.domain.industry.FundingRound;
import com.portfolio.invest.domain.industry.IndustryErrorCode;
import com.portfolio.invest.domain.industry.IndustryException;
import com.portfolio.invest.domain.industry.IndustryRepository;
import com.portfolio.invest.domain.industry.IndustryStock;
import com.portfolio.invest.domain.industry.UnlistedCompanyRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * 未上市读聚合服务（MS-10 P2，设计规格 §四读侧/§七口径）：三方法均先行行业白名单校验
 * （照 IndustryApplicationService.stocks 的 INDUSTRY_NOT_FOUND 路径）。读侧无缓存
 * （设计规格 §九#4：百~千行直查库，策展编辑即可见）。
 */
@Service
public class UnlistedResearchApplicationService {

    static final int MAX_MONTHS = 60;

    private final UnlistedCompanyRepository companyRepository;
    private final FundingEventRepository eventRepository;
    private final IndustryRepository industryRepository;

    public UnlistedResearchApplicationService(UnlistedCompanyRepository companyRepository,
                                              FundingEventRepository eventRepository,
                                              IndustryRepository industryRepository) {
        this.companyRepository = companyRepository;
        this.eventRepository = eventRepository;
        this.industryRepository = industryRepository;
    }

    /** 策展名单（F07）：仓储已按 lastFundingDate DESC NULLS LAST + 同日轮次序倒序返回。 */
    public List<UnlistedCompanyView> companies(String industryCode) {
        requireIndustry(industryCode);
        return companyRepository.findByIndustry(industryCode).stream()
                .map(UnlistedCompanyView::from).toList();
    }

    /** 融资事件（F08）：months ∈ [1,60]，窗口 since = today.minusMonths(months)。 */
    public List<FundingEventView> fundingEvents(String industryCode, int months) {
        if (months < 1 || months > MAX_MONTHS) {
            throw new IndustryException(IndustryErrorCode.INVALID_LIMIT,
                    "months 须在 1~" + MAX_MONTHS + " 之间");
        }
        requireIndustry(industryCode);
        return eventRepository.findByIndustrySince(industryCode, LocalDate.now().minusMonths(months))
                .stream().map(FundingEventView::from).toList();
    }

    /**
     * 全景卡（F06，§七口径）：listedCount/listedMarketCapYi 复用成员表同源查询
     * （total_mv/DESC/1000，市值元→亿元 = movePointLeft(8) 后保留两位；null 市值计入家数
     * 不计入求和）；近 12 月窗口 = today.minusDays(365)；轮次分布按 FundingRound.order()
     * 排序、零计数轮次不透出。
     */
    public UnlistedOverviewView overview(String industryCode) {
        requireIndustry(industryCode);
        List<IndustryStock> stocks = industryRepository.findIndustryStocks(
                industryCode, "total_mv", "DESC", 1000);
        BigDecimal marketCapYi = stocks.stream()
                .map(IndustryStock::totalMv).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .movePointLeft(8).setScale(2, RoundingMode.HALF_UP);
        long curatedCount = companyRepository.countByIndustry(industryCode);
        List<FundingEvent> since12m = eventRepository.findByIndustrySince(
                industryCode, LocalDate.now().minusDays(365));
        List<UnlistedOverviewView.RoundCount> distribution = since12m.stream()
                .collect(Collectors.groupingBy(FundingEvent::round, Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparingInt(FundingRound::order)))
                .map(e -> new UnlistedOverviewView.RoundCount(e.getKey().name(), e.getValue().intValue()))
                .toList();
        return new UnlistedOverviewView(stocks.size(), marketCapYi, (int) curatedCount,
                since12m.size(), distribution, UnlistedOverviewView.COVERAGE_NOTE);
    }

    private void requireIndustry(String industryCode) {
        if (!industryRepository.existsIndustry(industryCode)) {
            throw new IndustryException(IndustryErrorCode.INDUSTRY_NOT_FOUND,
                    "行业不存在: " + industryCode); // 文案口径照 IndustryApplicationService.stocks
        }
    }
}
