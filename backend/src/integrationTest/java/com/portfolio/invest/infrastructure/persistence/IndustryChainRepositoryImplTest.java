package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.industry.ChainMember;
import com.portfolio.invest.domain.industry.ChainStage;
import com.portfolio.invest.domain.industry.ChainTier;
import com.portfolio.invest.domain.industry.IndustryChain;
import com.portfolio.invest.domain.industry.IndustryChainRepository;
import com.portfolio.invest.support.PostgresTestSupport;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 产业链仓储集成测试（照 UnlistedCompanyRepositoryImplTest 基座）：成员派生关联查询
 * （LISTED 经 shenwan_industry_mapping——测试事务内自种映射行，迁移不种该表；
 * UNLISTED 经 V19 种子策展企业 industry_code，无需额外造数）、全文档替换 save、
 * CHECK 兜底与幂等删除。V20 种子（锂电池/创新药两链）由迁移保证在场。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(IndustryChainRepositoryImpl.class)
class IndustryChainRepositoryImplTest {

    @ServiceConnection
    static PostgreSQLContainer<?> postgres = PostgresTestSupport.postgres();

    @Autowired
    private IndustryChainRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static ChainMember listed(String code, String name) {
        return new ChainMember(null, "LISTED", code, null, name);
    }

    private static ChainMember unlisted(Long unlistedCompanyId, String name) {
        return new ChainMember(null, "UNLISTED", null, unlistedCompanyId, name);
    }

    private static ChainStage stage(ChainTier tier, String name, int sortOrder, ChainMember... members) {
        return new ChainStage(null, tier, name, sortOrder, List.of(members));
    }

    /** V19 种子策展企业 id（创新药链未上市成员的引用目标；子查询定位防 V19.1 改日期漂移）。 */
    private Long seedUnlistedId(String companyName) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM industry_unlisted_company WHERE company_name = ?", Long.class, companyName);
    }

    @DisplayName("成员派生（LISTED 路径）：上市成员行业映射命中 → V20 种子锂电池链整包返回")
    @Test
    void givenListedMemberMapping_whenFindRelatedToIndustry_thenSeedLithiumChain() {
        // 迁移不种 shenwan_industry_mapping（跨服务数据面）——测试事务内自种宁德时代→801730
        jdbcTemplate.update(
                "INSERT INTO shenwan_industry_mapping(stock_code, stock_name, industry_code, industry_name)"
                        + " VALUES ('300750', '宁德时代', '801730', '电力设备')");

        List<IndustryChain> chains = repository.findRelatedToIndustry("801730");

        assertThat(chains).extracting(IndustryChain::name).containsExactly("锂电池");
        IndustryChain lithium = chains.get(0);
        // stages 按 tier 序 + sortOrder：锂矿/锂电材料（上游）→ 电芯（中游）→ 整车（下游）
        assertThat(lithium.stages()).extracting(ChainStage::name)
                .containsExactly("锂矿", "锂电材料", "电芯", "整车");
        assertThat(lithium.stages()).extracting(ChainStage::tier)
                .containsExactly(ChainTier.UPSTREAM, ChainTier.UPSTREAM,
                        ChainTier.MIDSTREAM, ChainTier.DOWNSTREAM);
        assertThat(lithium.stages()).allSatisfy(s -> assertThat(s.members()).hasSize(2));
        // 成员按 id 序且字段完整（display_name 快照 + stock_code 供行情台跳转）
        var cell = lithium.stages().stream().filter(s -> s.name().equals("电芯")).findFirst().orElseThrow();
        assertThat(cell.members()).extracting(ChainMember::displayName)
                .anySatisfy(n -> assertThat(n).isIn("宁德时代", "亿纬锂能"));
        assertThat(cell.members()).extracting(ChainMember::memberType).containsOnly("LISTED");
    }

    @DisplayName("成员派生（UNLISTED 路径）：策展企业 industry_code 命中 → V20 种子创新药链")
    @Test
    void givenUnlistedMemberCompany_whenFindRelatedToIndustry_thenSeedPharmaChain() {
        List<IndustryChain> chains = repository.findRelatedToIndustry("801150");

        assertThat(chains).extracting(IndustryChain::name).containsExactly("创新药");
        IndustryChain pharma = chains.get(0);
        assertThat(pharma.stages()).extracting(ChainStage::name)
                .containsExactly("CXO", "Biotech", "制药");
        // 未上市成员：memberType=UNLISTED 且引用 V19 种子企业 id
        var biotech = pharma.stages().stream().filter(s -> s.name().equals("Biotech"))
                .findFirst().orElseThrow();
        assertThat(biotech.members()).extracting(ChainMember::memberType).contains("UNLISTED");
        assertThat(biotech.members()).extracting(ChainMember::displayName).contains("示例康源生物");
        assertThat(biotech.members().stream()
                .filter(m -> "UNLISTED".equals(m.memberType()))
                .findFirst().orElseThrow().unlistedCompanyId())
                .isEqualTo(seedUnlistedId("示例康源生物"));
    }

    @DisplayName("无任何成员归属的行业（801780 银行）：派生结果为空")
    @Test
    void givenIndustryWithoutMembers_whenFindRelatedToIndustry_thenEmpty() {
        assertThat(repository.findRelatedToIndustry("801780")).isEmpty();
    }

    @DisplayName("save 首插：无 id 插入回带库生成 id，findByIdFull 整包可见")
    @Test
    void givenNewChain_whenSave_thenInsertedWithGeneratedId() {
        Long unlistedId = seedUnlistedId("示例安塞生物");
        Long id = repository.save(new IndustryChain(null, "测试光伏链", "测试描述", List.of(
                stage(ChainTier.UPSTREAM, "硅料", 1, listed("601012", "隆基绿能")),
                stage(ChainTier.DOWNSTREAM, "电站", 1, unlisted(unlistedId, "示例安塞生物")))));

        assertThat(id).isNotNull();
        Optional<IndustryChain> full = repository.findByIdFull(id);
        assertThat(full).isPresent();
        assertThat(full.orElseThrow().name()).isEqualTo("测试光伏链");
        assertThat(full.orElseThrow().description()).isEqualTo("测试描述");
        assertThat(full.orElseThrow().stages()).hasSize(2);
        assertThat(full.orElseThrow().stages().get(0).id()).isNotNull();
        assertThat(full.orElseThrow().stages().get(0).members().get(0).id()).isNotNull();
    }

    @DisplayName("save 全文档替换：同 id 重存（链头改写 + 环节同名重排）→ 旧环节成员消失、新成员就位")
    @Test
    void givenExistingChain_whenSaveAgain_thenFullDocumentReplaced() {
        Long id = repository.save(new IndustryChain(null, "测试替换链", "旧描述", List.of(
                stage(ChainTier.UPSTREAM, "环节甲", 1,
                        listed("600000", "旧成员一"), listed("600001", "旧成员二")),
                stage(ChainTier.DOWNSTREAM, "环节乙", 1, listed("600002", "旧成员三")))));

        // 同 (tier, name) 环节重插：证明删旧 stages 先于插新（否则撞 UNIQUE(chain_id,tier,name)）
        repository.save(new IndustryChain(id, "测试替换链改", "新描述", List.of(
                stage(ChainTier.UPSTREAM, "环节甲", 2, listed("300750", "新成员一")))));

        Optional<IndustryChain> full = repository.findByIdFull(id);
        assertThat(full).isPresent();
        IndustryChain replaced = full.orElseThrow();
        assertThat(replaced.name()).isEqualTo("测试替换链改");
        assertThat(replaced.description()).isEqualTo("新描述");
        assertThat(replaced.stages()).extracting(ChainStage::name).containsExactly("环节甲");
        assertThat(replaced.stages().get(0).members()).extracting(ChainMember::displayName)
                .containsExactly("新成员一"); // 旧成员二/三已随全文档替换消失
        // 全文档替换后链内 stage/member 行数 = 新文档行数（无残留）
        Integer stages = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM industry_chain_stage WHERE chain_id = ?", Integer.class, id);
        Integer members = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM industry_chain_member m"
                        + " JOIN industry_chain_stage s ON m.stage_id = s.id WHERE s.chain_id = ?",
                Integer.class, id);
        assertThat(stages).isEqualTo(1);
        assertThat(members).isEqualTo(1);
    }

    @DisplayName("CHECK 兜底：LISTED 成员缺 stockCode 抛 DataIntegrityViolation（服务层前置校验之外的第二道防线）")
    @Test
    void givenListedMemberWithoutStockCode_whenSave_thenDataIntegrityViolation() {
        IndustryChain bad = new IndustryChain(null, "测试CHECK链", null, List.of(
                stage(ChainTier.UPSTREAM, "坏环节", 1,
                        new ChainMember(null, "LISTED", null, null, "无代码上市成员"))));

        assertThatThrownBy(() -> repository.save(bad))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @DisplayName("deleteById 幂等：删不存在的 id 不抛；删已有链后 findByIdFull 为空")
    @Test
    void givenChainDelete_whenDeleteById_thenIdempotentAndGone() {
        assertThatCode(() -> repository.deleteById(999_999_999L)).doesNotThrowAnyException();

        Long id = repository.save(new IndustryChain(null, "测试删除链", null, List.of(
                stage(ChainTier.MIDSTREAM, "环节", 1, listed("600000", "成员")))));
        repository.deleteById(id);
        assertThat(repository.findByIdFull(id)).isEmpty();
    }
}
