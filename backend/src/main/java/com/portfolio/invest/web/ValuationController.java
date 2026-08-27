package com.portfolio.invest.web;

import com.portfolio.invest.application.valuation.IndustryValuationView;
import com.portfolio.invest.application.valuation.ValuationApplicationService;
import com.portfolio.invest.application.valuation.ValuationHistoryView;
import com.portfolio.invest.application.valuation.ValuationOverviewView;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 市场估值 REST 接口（P2 前端反代消费；无需登录）。 */
@RestController
@RequestMapping("/api/valuation")
public class ValuationController {

    private final ValuationApplicationService valuationApplicationService;

    public ValuationController(ValuationApplicationService valuationApplicationService) {
        this.valuationApplicationService = valuationApplicationService;
    }

    @GetMapping("/overview")
    public ValuationOverviewView overview() {
        return valuationApplicationService.overview();
    }

    @GetMapping("/industries")
    public List<IndustryValuationView> industries(@RequestParam(defaultValue = "pe") String sort) {
        return valuationApplicationService.industries(sort);
    }

    @GetMapping("/history")
    public ValuationHistoryView history() {
        return valuationApplicationService.history();
    }
}
