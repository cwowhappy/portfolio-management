package com.portfolio.invest.application.industry;

import com.portfolio.invest.domain.industry.ChainMember;
import com.portfolio.invest.domain.industry.ChainStage;
import com.portfolio.invest.domain.industry.ChainTier;
import com.portfolio.invest.domain.industry.IndustryChain;
import com.portfolio.invest.domain.industry.IndustryChainRepository;
import com.portfolio.invest.domain.industry.IndustryErrorCode;
import com.portfolio.invest.domain.industry.IndustryException;
import com.portfolio.invest.domain.industry.IndustryRepository;
import com.portfolio.invest.domain.industry.UnlistedCompanyRepository;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 产业链应用服务（MS-10 F11，设计规格 §四）：读侧成员派生查询（§九#3）+ 写侧全文档
 * 保存（§四：stages+members 嵌套整体替换，事务内删旧插新）。全局研究数据无 user 归属
 * （§九#1）。校验前置：tier 枚举、成员约束镜像 DB CHECK（LISTED 必带 stockCode、
 * UNLISTED 必带存在的策展企业引用）；UNIQUE(name) 撞键转译 CHAIN_DUPLICATE（照
 * UNLISTED_DUPLICATE 先例）。证券存在性不查库——种子/演示允许任意 A 股代码（计划口径）。
 */
@Service
public class IndustryChainApplicationService {

    private final IndustryChainRepository chainRepository;
    private final UnlistedCompanyRepository companyRepository;
    private final IndustryRepository industryRepository;

    public IndustryChainApplicationService(IndustryChainRepository chainRepository,
                                           UnlistedCompanyRepository companyRepository,
                                           IndustryRepository industryRepository) {
        this.chainRepository = chainRepository;
        this.companyRepository = companyRepository;
        this.industryRepository = industryRepository;
    }

    /** 行业相关产业链（F11）：成员派生查询，整包返回（链 2~3 条 × 成员十级量级，不缓存）。 */
    public List<ChainView> chains(String industryCode) {
        if (!industryRepository.existsIndustry(industryCode)) {
            throw new IndustryException(IndustryErrorCode.INDUSTRY_NOT_FOUND,
                    "行业不存在: " + industryCode); // 文案口径照 IndustryApplicationService.stocks
        }
        return chainRepository.findRelatedToIndustry(industryCode).stream()
                .map(ChainView::from).toList();
    }

    /** 全文档保存：id=null 插入（回带库生成 id）；否则同 id 整体替换 stages+members。 */
    @Transactional
    public ChainView save(Long id, SaveChainCommand cmd) {
        if (id != null && chainRepository.findByIdFull(id).isEmpty()) {
            throw new IndustryException(IndustryErrorCode.CHAIN_NOT_FOUND, "产业链不存在: " + id);
        }
        IndustryChain chain = new IndustryChain(id, cmd.name().trim(), cmd.description(),
                cmd.stages().stream().map(this::toStage).toList());
        Long chainId;
        try {
            chainId = chainRepository.save(chain);
        } catch (DataIntegrityViolationException e) {
            // UNIQUE(name) 兜底：新建撞名或更新改名撞名 → 友好 409
            throw new IndustryException(IndustryErrorCode.CHAIN_DUPLICATE, "已存在同名产业链");
        }
        return ChainView.from(chainRepository.findByIdFull(chainId).orElseThrow());
    }

    @Transactional
    public void deleteChain(Long id) {
        chainRepository.deleteById(id); // 仓储幂等：删不存在不抛
    }

    private ChainStage toStage(SaveChainCommand.StageCommand cmd) {
        return new ChainStage(null, parseTier(cmd.tier()), cmd.name().trim(), cmd.sortOrder(),
                cmd.members().stream().map(this::toMember).toList());
    }

    private ChainMember toMember(SaveChainCommand.MemberCommand cmd) {
        // 成员约束镜像 DB CHECK（V20 industry_chain_member）：类型二选一 + 各自必填引用
        if ("LISTED".equals(cmd.memberType())) {
            if (cmd.stockCode() == null || cmd.stockCode().isBlank()) {
                throw new IndustryException(IndustryErrorCode.INVALID_MEMBER,
                        "LISTED 成员必须提供证券代码: " + cmd.displayName());
            }
            return new ChainMember(null, "LISTED", cmd.stockCode().trim(), null, cmd.displayName().trim());
        }
        if ("UNLISTED".equals(cmd.memberType())) {
            if (cmd.unlistedCompanyId() == null) {
                throw new IndustryException(IndustryErrorCode.INVALID_MEMBER,
                        "UNLISTED 成员必须关联策展企业: " + cmd.displayName());
            }
            if (companyRepository.findById(cmd.unlistedCompanyId()).isEmpty()) {
                throw new IndustryException(IndustryErrorCode.UNLISTED_NOT_FOUND,
                        "策展企业不存在: " + cmd.unlistedCompanyId());
            }
            return new ChainMember(null, "UNLISTED", null, cmd.unlistedCompanyId(),
                    cmd.displayName().trim());
        }
        throw new IndustryException(IndustryErrorCode.INVALID_MEMBER, "成员类型无效: " + cmd.memberType());
    }

    private static ChainTier parseTier(String raw) {
        try {
            return ChainTier.parse(raw);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IndustryException(IndustryErrorCode.INVALID_TIER, "环节层级无效: " + raw);
        }
    }
}
