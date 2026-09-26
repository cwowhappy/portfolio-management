package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.industry.ChainMember;
import com.portfolio.invest.domain.industry.ChainStage;
import com.portfolio.invest.domain.industry.ChainTier;
import com.portfolio.invest.domain.industry.IndustryChain;
import com.portfolio.invest.domain.industry.IndustryChainRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 产业链仓储实现。读侧（成员派生 join / 两步批查组装）照 IndustryRepositoryImpl 用
 * JdbcTemplate——派生口径（§九#3）为跨四表 EXISTS 连接，SQL 形态可读性最好；写侧链头
 * 三列行与全文档替换的删旧直接 JdbcTemplate，层级插入（需生成 id）用 stage/member 两
 * JPA 仓储（IDENTITY 策略即时 INSERT，CHECK 违例在事务内抛出）。事务边界在 application
 * 层，本类不挂 @Transactional（照 UnlistedCompanyRepositoryImpl）。
 */
@Repository
public class IndustryChainRepositoryImpl implements IndustryChainRepository {

    /** tier 排序键（UPSTREAM→MIDSTREAM→DOWNSTREAM，域枚举声明序的 SQL 镜像）。 */
    private static final String TIER_ORDER =
            "CASE tier WHEN 'UPSTREAM' THEN 0 WHEN 'MIDSTREAM' THEN 1 ELSE 2 END";

    /** 组装中间行：stage 原始行携带 chainId（域记录不含，需中转挂接）。 */
    private record StageRow(Long chainId, Long stageId, ChainTier tier, String name, int sortOrder) {}

    private final JdbcTemplate jdbc;
    private final IndustryChainStageJpaRepository stageJpa;
    private final IndustryChainMemberJpaRepository memberJpa;

    public IndustryChainRepositoryImpl(JdbcTemplate jdbc,
                                       IndustryChainStageJpaRepository stageJpa,
                                       IndustryChainMemberJpaRepository memberJpa) {
        this.jdbc = jdbc;
        this.stageJpa = stageJpa;
        this.memberJpa = memberJpa;
    }

    @Override
    public List<IndustryChain> findRelatedToIndustry(String industryCode) {
        // 成员派生（设计规格 §九#3，一条 join 查询无关联表）：
        // LISTED 经 shenwan_industry_mapping、UNLISTED 经策展企业 industry_code
        List<Long> chainIds = jdbc.queryForList("""
                SELECT DISTINCT c.id FROM industry_chain c
                  JOIN industry_chain_stage s ON s.chain_id = c.id
                  JOIN industry_chain_member m ON m.stage_id = s.id
                 WHERE (m.member_type = 'LISTED' AND EXISTS (
                           SELECT 1 FROM shenwan_industry_mapping map
                            WHERE map.stock_code = m.stock_code AND map.industry_code = ?))
                    OR (m.member_type = 'UNLISTED' AND EXISTS (
                           SELECT 1 FROM industry_unlisted_company u
                            WHERE u.id = m.unlisted_company_id AND u.industry_code = ?))
                 ORDER BY c.id
                """, Long.class, industryCode, industryCode);
        return assemble(chainIds);
    }

    @Override
    public Optional<IndustryChain> findByIdFull(Long id) {
        return assemble(List.of(id)).stream().findFirst();
    }

    @Override
    public Long save(IndustryChain chain) {
        Long chainId;
        if (chain.id() == null) {
            chainId = jdbc.queryForObject(
                    "INSERT INTO industry_chain(name, description) VALUES (?, ?) RETURNING id",
                    Long.class, chain.name(), chain.description());
        } else {
            chainId = chain.id();
            jdbc.update("UPDATE industry_chain SET name = ?, description = ? WHERE id = ?",
                    chain.name(), chain.description(), chainId);
        }
        // 全文档替换：先删旧 stages（DB 级联清 members）——先删后插保同名环节重排不撞 UNIQUE
        jdbc.update("DELETE FROM industry_chain_stage WHERE chain_id = ?", chainId);
        for (ChainStage stage : chain.stages()) {
            Long stageId = stageJpa.saveAndFlush(
                    IndustryChainStageJpaEntity.fromDomain(chainId, stage)).toDomain().id();
            List<IndustryChainMemberJpaEntity> members = stage.members().stream()
                    .map(m -> IndustryChainMemberJpaEntity.fromDomain(stageId, m)).toList();
            memberJpa.saveAll(members);
        }
        memberJpa.flush(); // IDENTITY 已即时 INSERT，此处显式 flush 保 CHECK 违例在事务内抛出
        return chainId;
    }

    @Override
    public void deleteById(Long id) {
        jdbc.update("DELETE FROM industry_chain WHERE id = ?", id); // 0 行受影响即幂等；级联清 stages/members
    }

    /** 两步批查组装：链头 → stages（tier 序 + sortOrder + id）→ members（id 序）挂接。 */
    private List<IndustryChain> assemble(List<Long> chainIds) {
        if (chainIds.isEmpty()) {
            return List.of();
        }
        String chainPh = placeholders(chainIds.size());
        List<IndustryChain> chains = jdbc.query(
                "SELECT id, name, description FROM industry_chain WHERE id IN (" + chainPh + ") ORDER BY id",
                (rs, i) -> new IndustryChain(rs.getLong("id"), rs.getString("name"),
                        rs.getString("description"), new ArrayList<>()),
                chainIds.toArray());

        List<StageRow> stageRows = jdbc.query(
                "SELECT id, chain_id, tier, name, sort_order FROM industry_chain_stage"
                        + " WHERE chain_id IN (" + chainPh + ") ORDER BY " + TIER_ORDER + ", sort_order, id",
                (rs, i) -> new StageRow(rs.getLong("chain_id"), rs.getLong("id"),
                        ChainTier.valueOf(rs.getString("tier")), rs.getString("name"),
                        rs.getInt("sort_order")),
                chainIds.toArray());

        Map<Long, List<ChainMember>> membersByStage = queryMembers(stageRows);
        return chains.stream()
                .map(c -> new IndustryChain(c.id(), c.name(), c.description(),
                        stageRows.stream()
                                .filter(r -> r.chainId().equals(c.id()))
                                .map(r -> new ChainStage(r.stageId(), r.tier(), r.name(), r.sortOrder(),
                                        membersByStage.getOrDefault(r.stageId(), List.of())))
                                .toList()))
                .toList();
    }

    private Map<Long, List<ChainMember>> queryMembers(List<StageRow> stageRows) {
        if (stageRows.isEmpty()) {
            return Map.of();
        }
        Object[] stageIds = stageRows.stream().map(StageRow::stageId).toArray();
        return jdbc.query("SELECT id, stage_id, member_type, stock_code, unlisted_company_id, display_name"
                        + " FROM industry_chain_member WHERE stage_id IN (" + placeholders(stageRows.size()) + ")"
                        + " ORDER BY id",
                        (rs, i) -> Map.entry(rs.getLong("stage_id"), new ChainMember(rs.getLong("id"),
                                rs.getString("member_type"), rs.getString("stock_code"),
                                rs.getObject("unlisted_company_id") == null ? null
                                        : rs.getLong("unlisted_company_id"),
                                rs.getString("display_name"))),
                        stageIds).stream()
                .collect(Collectors.groupingBy(Map.Entry::getKey,
                        Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
    }

    private static String placeholders(int n) {
        return String.join(",", Collections.nCopies(n, "?"));
    }
}
