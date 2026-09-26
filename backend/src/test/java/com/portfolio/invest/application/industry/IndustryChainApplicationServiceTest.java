package com.portfolio.invest.application.industry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.invest.domain.industry.ChainMember;
import com.portfolio.invest.domain.industry.ChainStage;
import com.portfolio.invest.domain.industry.ChainTier;
import com.portfolio.invest.domain.industry.IndustryChain;
import com.portfolio.invest.domain.industry.IndustryChainRepository;
import com.portfolio.invest.domain.industry.IndustryErrorCode;
import com.portfolio.invest.domain.industry.IndustryException;
import com.portfolio.invest.domain.industry.IndustryRepository;
import com.portfolio.invest.domain.industry.UnlistedCompany;
import com.portfolio.invest.domain.industry.UnlistedCompanyRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 产业链应用服务单测（Mockito 构造注入，照 IndustryCurationApplicationServiceTest 先例）：
 * 读侧行业白名单；写侧校验矩阵（tier 枚举/成员类型二选一约束镜像 DB CHECK/UNLISTED 引用
 * 存在性）+ 全文档保存委托 + UNIQUE(name) 转译 CHAIN_DUPLICATE + 更新前置 CHAIN_NOT_FOUND。
 */
class IndustryChainApplicationServiceTest {

    private final IndustryChainRepository chainRepository = mock(IndustryChainRepository.class);
    private final UnlistedCompanyRepository companyRepository = mock(UnlistedCompanyRepository.class);
    private final IndustryRepository industryRepository = mock(IndustryRepository.class);

    private final IndustryChainApplicationService service =
            new IndustryChainApplicationService(chainRepository, companyRepository, industryRepository);

    private static final SaveChainCommand.MemberCommand LISTED_MEMBER =
            new SaveChainCommand.MemberCommand("LISTED", "300750", null, "宁德时代");
    private static final SaveChainCommand.MemberCommand UNLISTED_MEMBER =
            new SaveChainCommand.MemberCommand("UNLISTED", null, 7L, "示例康源生物");

    private static SaveChainCommand command(SaveChainCommand.MemberCommand... members) {
        return new SaveChainCommand("测试链", "描述", List.of(
                new SaveChainCommand.StageCommand("UPSTREAM", "锂矿", 1, List.of(members))));
    }

    private static IndustryChain savedChain(Long id) {
        return new IndustryChain(id, "测试链", "描述", List.of(new ChainStage(11L, ChainTier.UPSTREAM,
                "锂矿", 1, List.of(new ChainMember(21L, "LISTED", "300750", null, "宁德时代")))));
    }

    @DisplayName("chains：行业白名单通过 → 派生查询映射 ChainView（tier 双字段）")
    @Test
    void givenKnownIndustry_whenChains_thenViewsMapped() {
        when(industryRepository.existsIndustry("801730")).thenReturn(true);
        when(chainRepository.findRelatedToIndustry("801730")).thenReturn(List.of(savedChain(1L)));

        var views = service.chains("801730");

        assertThat(views).hasSize(1);
        assertThat(views.get(0).name()).isEqualTo("测试链");
        assertThat(views.get(0).stages().get(0).tier()).isEqualTo("UPSTREAM");
        assertThat(views.get(0).stages().get(0).tierLabel()).isEqualTo("上游");
        assertThat(views.get(0).stages().get(0).members().get(0).displayName()).isEqualTo("宁德时代");
    }

    @DisplayName("chains：行业不在白名单 → INDUSTRY_NOT_FOUND 且不触链仓储")
    @Test
    void givenUnknownIndustry_whenChains_thenIndustryNotFound() {
        when(industryRepository.existsIndustry("999999")).thenReturn(false);

        assertThatThrownBy(() -> service.chains("999999"))
                .isInstanceOfSatisfying(IndustryException.class,
                        e -> assertThat(e.code()).isEqualTo(IndustryErrorCode.INDUSTRY_NOT_FOUND));

        verify(chainRepository, never()).findRelatedToIndustry(any());
    }

    @DisplayName("save 新建：校验通过 → 委托仓储全文档落库并回读整包视图")
    @Test
    void givenValidCommand_whenSave_thenDelegateAndReturnView() {
        when(chainRepository.save(any())).thenReturn(42L);
        when(chainRepository.findByIdFull(42L)).thenReturn(Optional.of(savedChain(42L)));

        var view = service.save(null, command(LISTED_MEMBER));

        assertThat(view.id()).isEqualTo(42L);
        var captor = ArgumentCaptor.forClass(IndustryChain.class);
        verify(chainRepository).save(captor.capture());
        assertThat(captor.getValue().id()).isNull(); // 插入路径 id 由库生成
        assertThat(captor.getValue().stages().get(0).tier()).isEqualTo(ChainTier.UPSTREAM);
        assertThat(captor.getValue().stages().get(0).members().get(0).stockCode()).isEqualTo("300750");
    }

    @DisplayName("save 新建 UNLISTED 成员：策展企业存在 → 保留引用落库")
    @Test
    void givenExistingUnlistedCompany_whenSave_thenMemberKeepsReference() {
        when(companyRepository.findById(7L)).thenReturn(Optional.of(new UnlistedCompany(7L, "801150",
                "示例康源生物", "Biotech", null, null, null, null, null, null)));
        when(chainRepository.save(any())).thenReturn(43L);
        when(chainRepository.findByIdFull(43L)).thenReturn(Optional.of(savedChain(43L)));

        service.save(null, command(UNLISTED_MEMBER));

        var captor = ArgumentCaptor.forClass(IndustryChain.class);
        verify(chainRepository).save(captor.capture());
        assertThat(captor.getValue().stages().get(0).members().get(0).unlistedCompanyId()).isEqualTo(7L);
        assertThat(captor.getValue().stages().get(0).members().get(0).stockCode()).isNull();
    }

    @DisplayName("save 更新：目标链存在 → 同 id 全文档替换")
    @Test
    void givenExistingChainId_whenSave_thenReplacedWithSameId() {
        when(chainRepository.findByIdFull(5L)).thenReturn(Optional.of(savedChain(5L)));
        when(chainRepository.save(any())).thenReturn(5L);
        when(chainRepository.findByIdFull(5L)).thenReturn(Optional.of(savedChain(5L)));

        var view = service.save(5L, command(LISTED_MEMBER));

        assertThat(view.id()).isEqualTo(5L);
        verify(chainRepository).save(any(IndustryChain.class));
    }

    @DisplayName("save 更新不存在的 id：抛 CHAIN_NOT_FOUND 且不落库")
    @Test
    void givenMissingChainId_whenSave_thenChainNotFound() {
        when(chainRepository.findByIdFull(5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.save(5L, command(LISTED_MEMBER)))
                .isInstanceOfSatisfying(IndustryException.class,
                        e -> assertThat(e.code()).isEqualTo(IndustryErrorCode.CHAIN_NOT_FOUND));

        verify(chainRepository, never()).save(any());
    }

    @DisplayName("save 撞链名 UNIQUE：仓储 DIVE 转译 CHAIN_DUPLICATE（409）")
    @Test
    void givenDuplicateChainName_whenSave_thenChainDuplicate() {
        when(chainRepository.save(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> service.save(null, command(LISTED_MEMBER)))
                .isInstanceOfSatisfying(IndustryException.class,
                        e -> assertThat(e.code()).isEqualTo(IndustryErrorCode.CHAIN_DUPLICATE));
    }

    @DisplayName("save tier 非枚举名：抛 INVALID_TIER 且不落库")
    @Test
    void givenInvalidTier_whenSave_thenInvalidTier() {
        var cmd = new SaveChainCommand("测试链", null, List.of(new SaveChainCommand.StageCommand(
                "上游", "锂矿", 1, List.of(LISTED_MEMBER))));

        assertThatThrownBy(() -> service.save(null, cmd))
                .isInstanceOfSatisfying(IndustryException.class, e -> {
                    assertThat(e.code()).isEqualTo(IndustryErrorCode.INVALID_TIER);
                    assertThat(e.getMessage()).contains("上游");
                });

        verify(chainRepository, never()).save(any());
    }

    @DisplayName("save 成员约束镜像 DB CHECK：LISTED 缺代码 / UNLISTED 缺企业 / 类型非法 → INVALID_MEMBER")
    @Test
    void givenMemberViolations_whenSave_thenInvalidMember() {
        var noStockCode = new SaveChainCommand.MemberCommand("LISTED", null, null, "无代码成员");
        assertThatThrownBy(() -> service.save(null, command(noStockCode)))
                .isInstanceOfSatisfying(IndustryException.class,
                        e -> assertThat(e.code()).isEqualTo(IndustryErrorCode.INVALID_MEMBER));

        var noCompany = new SaveChainCommand.MemberCommand("UNLISTED", null, null, "无企业成员");
        assertThatThrownBy(() -> service.save(null, command(noCompany)))
                .isInstanceOfSatisfying(IndustryException.class,
                        e -> assertThat(e.code()).isEqualTo(IndustryErrorCode.INVALID_MEMBER));

        var badType = new SaveChainCommand.MemberCommand("MAYBE", "300750", null, "怪类型成员");
        assertThatThrownBy(() -> service.save(null, command(badType)))
                .isInstanceOfSatisfying(IndustryException.class,
                        e -> assertThat(e.code()).isEqualTo(IndustryErrorCode.INVALID_MEMBER));

        verify(chainRepository, never()).save(any());
    }

    @DisplayName("save UNLISTED 引用不存在策展企业：抛 UNLISTED_NOT_FOUND（复用既有码）")
    @Test
    void givenMissingUnlistedCompany_whenSave_thenUnlistedNotFound() {
        when(companyRepository.findById(99L)).thenReturn(Optional.empty());
        var ghost = new SaveChainCommand.MemberCommand("UNLISTED", null, 99L, "幽灵企业");

        assertThatThrownBy(() -> service.save(null, command(ghost)))
                .isInstanceOfSatisfying(IndustryException.class,
                        e -> assertThat(e.code()).isEqualTo(IndustryErrorCode.UNLISTED_NOT_FOUND));

        verify(chainRepository, never()).save(any());
    }

    @DisplayName("deleteChain：委托仓储（仓储自身幂等）")
    @Test
    void whenDeleteChain_thenDelegateToRepository() {
        service.deleteChain(7L);
        verify(chainRepository).deleteById(7L);
    }
}
