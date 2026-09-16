package com.portfolio.invest.web;

import com.portfolio.invest.application.screening.ScreeningApplicationService;
import com.portfolio.invest.domain.screening.ScreeningCriteria;
import com.portfolio.invest.domain.screening.ScreeningErrorCode;
import com.portfolio.invest.domain.screening.ScreeningException;
import com.portfolio.invest.domain.screening.SortDirection;
import com.portfolio.invest.domain.screening.StockSearchHit;
import com.portfolio.invest.domain.screening.StockScreeningResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 价值筛选 REST 接口（P3 前端反代消费；无需登录）。
 *
 * <p>A1 取舍（显式记录）：{@link StockScreeningResult} 为纯数据读模型（仓库行映射，无行为），
 * 直接作为 @ResponseBody 即对外契约；若未来该对象引入行为/内部变化再拆应用层 DTO。
 */
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
            @RequestParam(required = false) String indexCode,
            @RequestParam(defaultValue = "pe_ttm") String sortBy,
            @RequestParam(defaultValue = "ASC") SortDirection sortDirection,
            @RequestParam(defaultValue = "200") int limit) {
        var criteria = new ScreeningCriteria(
                peTtmMax, pbMax, dividendYieldMin, roeMin, roaMin, grossMarginMin,
                debtToAssetsMax, currentRatioMin, revenueYoyMin, netprofitYoyMin,
                totalMvMin, turnoverRateMin, industryCode, indexCode, sortBy, sortDirection, limit);
        return screeningApplicationService.screen(criteria);
    }

    /** 股票搜索候选（自选手动添加用；公开只读）。 */
    @GetMapping("/stocks/search")
    public List<StockSearchHit> search(@RequestParam String q,
                                       @RequestParam(defaultValue = "10") int limit) {
        if (q == null || q.isBlank()) {
            throw new ScreeningException(ScreeningErrorCode.NO_CONDITION, "搜索词不能为空");
        }
        return screeningApplicationService.search(q.trim(), Math.min(Math.max(limit, 1), 20));
    }

    /**
     * 筛选结果导出 CSV（公开；参数与 /stocks 完全一致，复用同一查询与缓存——导出口径与页面必然一致）。
     */
    @GetMapping(value = "/stocks/export", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> export(
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
            @RequestParam(required = false) String indexCode,
            @RequestParam(defaultValue = "pe_ttm") String sortBy,
            @RequestParam(defaultValue = "ASC") SortDirection sortDirection,
            @RequestParam(defaultValue = "200") int limit) {
        var criteria = new ScreeningCriteria(
                peTtmMax, pbMax, dividendYieldMin, roeMin, roaMin, grossMarginMin,
                debtToAssetsMax, currentRatioMin, revenueYoyMin, netprofitYoyMin,
                totalMvMin, turnoverRateMin, industryCode, indexCode, sortBy, sortDirection, limit);
        var rows = screeningApplicationService.screen(criteria); // 同校验（NO_CONDITION 等）同缓存
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(ZoneId.systemDefault()).format(Instant.now());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"screening-" + stamp + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(ScreeningCsv.toCsv(rows));
    }
}
