package com.portfolio.invest.web;

import com.portfolio.invest.application.industry.ChainView;
import com.portfolio.invest.application.industry.FundingEventView;
import com.portfolio.invest.application.industry.IndustryApplicationService;
import com.portfolio.invest.application.industry.IndustryChainApplicationService;
import com.portfolio.invest.application.industry.IndustryBoardView;
import com.portfolio.invest.application.industry.UnlistedCompanyView;
import com.portfolio.invest.application.industry.UnlistedOverviewView;
import com.portfolio.invest.application.industry.UnlistedResearchApplicationService;
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
 *
 * <p>MS-10 P2 追加未上市三读端点（/unlisted/**，设计规格 §四读侧）：挂既有公开前缀
 * 零安全改动；读侧无缓存（§九#4），行业存在性与 months 范围校验在
 * {@link UnlistedResearchApplicationService}。
 */
@RestController
@RequestMapping("/api/industry")
public class IndustryController {

    private final IndustryApplicationService industryApplicationService;
    private final UnlistedResearchApplicationService unlistedResearchService;
    private final IndustryChainApplicationService chainService;

    public IndustryController(IndustryApplicationService industryApplicationService,
                              UnlistedResearchApplicationService unlistedResearchService,
                              IndustryChainApplicationService chainService) {
        this.industryApplicationService = industryApplicationService;
        this.unlistedResearchService = unlistedResearchService;
        this.chainService = chainService;
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

    /** 未上市策展名单（F07）：lastFundingDate DESC NULLS LAST + 同日轮次序倒序。 */
    @GetMapping("/{industryCode}/unlisted/companies")
    public List<UnlistedCompanyView> unlistedCompanies(@PathVariable String industryCode) {
        return unlistedResearchService.companies(industryCode);
    }

    /** 行业融资动态（F08）：默认近 24 月，months ∈ [1,60]；月度摘录非全量口径见 source 字段。 */
    @GetMapping("/{industryCode}/unlisted/funding-events")
    public List<FundingEventView> unlistedFundingEvents(@PathVariable String industryCode,
            @RequestParam(defaultValue = "24") int months) {
        return unlistedResearchService.fundingEvents(industryCode, months);
    }

    /** 行业全景卡（F06）：库内派生四指标 + 近 12 月轮次分布 + coverageNote 口径字段。 */
    @GetMapping("/{industryCode}/unlisted/overview")
    public UnlistedOverviewView unlistedOverview(@PathVariable String industryCode) {
        return unlistedResearchService.overview(industryCode);
    }

    /** 行业相关产业链（F11，MS-10 P3）：成员派生关联，整包返回（量小不缓存不片段化）。 */
    @GetMapping("/{industryCode}/chains")
    public List<ChainView> chains(@PathVariable String industryCode) {
        return chainService.chains(industryCode);
    }
}

