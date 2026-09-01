package com.portfolio.invest.web;

import com.portfolio.invest.application.screening.ScreeningApplicationService;
import com.portfolio.invest.domain.screening.ScreeningCriteria;
import com.portfolio.invest.domain.screening.SortDirection;
import com.portfolio.invest.domain.screening.StockScreeningResult;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 价值筛选 REST 接口（P3 前端反代消费；无需登录）。 */
@RestController
@RequestMapping("/api/screening")
public class ScreeningController {

    private final ScreeningApplicationService screeningApplicationService;

    public ScreeningController(ScreeningApplicationService screeningApplicationService) {
        this.screeningApplicationService = screeningApplicationService;
    }

    @GetMapping("/stocks")
    public List<StockScreeningResult> stocks(
            @RequestParam(required = false) BigDecimal peTtmMax,
            @RequestParam(required = false) BigDecimal pbMax,
            @RequestParam(required = false) BigDecimal dividendYieldMin,
            @RequestParam(required = false) BigDecimal roeMin,
            @RequestParam(required = false) BigDecimal roaMin,
            @RequestParam(required = false) BigDecimal grossMarginMin,
            @RequestParam(required = false) BigDecimal debtToAssetsMax,
            @RequestParam(required = false) BigDecimal currentRatioMin,
            @RequestParam(required = false) BigDecimal revenueYoyMin,
            @RequestParam(required = false) BigDecimal netprofitYoyMin,
            @RequestParam(required = false) BigDecimal totalMvMin,
            @RequestParam(required = false) BigDecimal turnoverRateMin,
            @RequestParam(required = false) String industryCode,
            @RequestParam(defaultValue = "pe_ttm") String sortBy,
            @RequestParam(defaultValue = "ASC") SortDirection sortDirection,
            @RequestParam(defaultValue = "200") int limit) {
        var criteria = new ScreeningCriteria(
                peTtmMax, pbMax, dividendYieldMin, roeMin, roaMin, grossMarginMin,
                debtToAssetsMax, currentRatioMin, revenueYoyMin, netprofitYoyMin,
                totalMvMin, turnoverRateMin, industryCode, sortBy, sortDirection, limit);
        return screeningApplicationService.screen(criteria);
    }
}
