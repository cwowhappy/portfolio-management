package com.portfolio.invest.domain.industry;

import java.util.List;
import java.util.Optional;

/**
 * 产业链端口（设计规格 §三）：行业关联由成员派生（§九#3：LISTED 经 shenwan_industry_mapping、
 * UNLISTED 经策展企业 industry_code，一条 join 查询无关联表）。保存语义全文档替换
 * （§四）：save 携带 id 即删旧 stages（cascade members）再插，单事务内完成——
 * 事务边界在 application 层，实现不挂 @Transactional。
 */
public interface IndustryChainRepository {

    /** 与行业相关的链（成员派生 join）：stages 按 tier 序 + sortOrder、members 按 id。 */
    List<IndustryChain> findRelatedToIndustry(String industryCode);

    /** 单链整包读取（写侧前置校验/编辑回显用）。 */
    Optional<IndustryChain> findByIdFull(Long id);

    /** 全文档保存：chain.id()==null 插入（返回库生成 id），否则更新链头并整体替换 stages+members。 */
    Long save(IndustryChain chain);

    /** 幂等删除（stage/member 由 V20 ON DELETE CASCADE 级联清理）。 */
    void deleteById(Long id);
}
