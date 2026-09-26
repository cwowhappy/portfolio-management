package com.portfolio.invest.application.industry;

import com.portfolio.invest.domain.industry.FundingEventRepository;
import com.portfolio.invest.domain.industry.FundingRound;
import com.portfolio.invest.domain.industry.IndustryErrorCode;
import com.portfolio.invest.domain.industry.IndustryException;
import com.portfolio.invest.domain.industry.IndustryRepository;
import com.portfolio.invest.domain.industry.UnlistedCompany;
import com.portfolio.invest.domain.industry.UnlistedCompanyRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 策展单条 CRUD 服务（设计规格 §四写侧，决策 #1 内嵌管理）：全局研究数据无 user 归属
 * （§九#1），入口不带用户参数、不按人过滤。save 双态：id=null 插入、否则按 id 更新
 * （更新前 findById 不存在抛 UNLISTED_NOT_FOUND）；行业白名单与轮次枚举校验先行。
 * 删除委托仓储（仓储自身幂等）。
 */
@Service
public class IndustryCurationApplicationService {

    private final UnlistedCompanyRepository companyRepository;
    private final FundingEventRepository eventRepository;
    private final IndustryRepository industryRepository;

    public IndustryCurationApplicationService(UnlistedCompanyRepository companyRepository,
                                              FundingEventRepository eventRepository,
                                              IndustryRepository industryRepository) {
        this.companyRepository = companyRepository;
        this.eventRepository = eventRepository;
        this.industryRepository = industryRepository;
    }

    @Transactional
    public UnlistedCompany save(Long id, SaveUnlistedCompanyCommand cmd) {
        // 轮次枚举校验先行（命令 latestRound 为字符串 wire 形态，非法即 INVALID_ROUND）
        FundingRound round = parseRound(cmd.latestRound());
        if (!industryRepository.existsIndustry(cmd.industryCode())) {
            throw new IndustryException(IndustryErrorCode.INDUSTRY_NOT_FOUND,
                    "行业不存在: " + cmd.industryCode()); // 文案口径照 IndustryApplicationService.stocks
        }
        if (id != null && companyRepository.findById(id).isEmpty()) {
            throw new IndustryException(IndustryErrorCode.UNLISTED_NOT_FOUND, "策展企业不存在: " + id);
        }
        return companyRepository.save(new UnlistedCompany(id, cmd.industryCode(), cmd.companyName(),
                cmd.segment(), round, cmd.lastFundingDate(), cmd.totalFundingYi(),
                cmd.summary(), cmd.sourceNote(), Instant.now()));
    }

    @Transactional
    public void deleteCompany(Long id) {
        companyRepository.deleteById(id); // 仓储幂等：删不存在不抛
    }

    @Transactional
    public void deleteFundingEvent(Long id) {
        eventRepository.deleteById(id);
    }

    private static FundingRound parseRound(String raw) {
        try {
            return FundingRound.parse(raw);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IndustryException(IndustryErrorCode.INVALID_ROUND, "轮次无效: " + raw);
        }
    }
}
