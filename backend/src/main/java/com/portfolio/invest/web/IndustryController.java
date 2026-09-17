package com.portfolio.invest.web;

import com.portfolio.invest.application.industry.IndustryApplicationService;
import com.portfolio.invest.application.industry.IndustryBoardView;
import com.portfolio.invest.domain.industry.IndustryStock;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 行业研究 REST 接口（P3 /industry 板面与下钻页消费；无需登录）。
 *
 * <p>A1 取舍（同 /api/screening）：{@link IndustryStock} 纯数据读模型直接作响应契约；
 * 排序白名单/limit 范围/行业存在性校验与缓存均在应用服务层。
 */
@RestController
@RequestMapping("/api/industry")
public class IndustryController {

    private final IndustryApplicationService industryApplicationService;

    public IndustryController(IndustryApplicationService industryApplicationService) {
        this.industryApplicationService = industryApplicationService;
    }

    /** 行业板面（估值 + 5 年窗口分位 + 景气标注及原始输入）。 */
    @GetMapping("/board")
    public List<IndustryBoardView> board() {
        return industryApplicationService.board();
    }

    /** 行业成员排名（市值/营收/ROE）。 */
    @GetMapping("/{industryCode}/stocks")
    public List<IndustryStock> stocks(@PathVariable String industryCode,
            @RequestParam(defaultValue = "total_mv") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDirection,
            @RequestParam(defaultValue = "1000") int limit) {
        return industryApplicationService.stocks(industryCode, sortBy, sortDirection, limit);
    }
}
