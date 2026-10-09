package com.portfolio.invest.application.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.invest.agent.InvestTools;
import com.portfolio.invest.agent.ToolkitPromptAssetCollector;
import com.portfolio.invest.domain.eval.PromptAssetVersion;
import com.portfolio.invest.domain.eval.PromptAssetVersionRepository;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 启动对账幂等单元守护（MS-30 B3，设计规格 §4.1/§4.2）：同 hash 不增行、变 hash
 * version+1（对账锚点断言）；真实采集面覆盖 main 四类 27 项（系统提示词 1 + @Tool 16 +
 * SKILL 6 + 情报 prompt 4——LEAD_SYSTEM_PROMPT 改 public 后含第 4 项）；run() 旁路降级
 * 不阻断启动。端口 mock，采集面走真实 Toolkit/Skill classpath（零 LLM 零网络）。
 */
class PromptVersionRegistrarTest {

    private final PromptAssetVersionRepository repository = mock(PromptAssetVersionRepository.class);

    /** 固定双资产采集面（系统提示词 + 单工具），幂等语义测试用。 */
    private final AgentPromptAssetPort fixedAssets = () -> List.of(
            new AgentPromptAssetPort.PromptAsset(
                    PromptAssetVersion.TYPE_SYSTEM_PROMPT, "system.invest", "系统提示词"),
            new AgentPromptAssetPort.PromptAsset(
                    PromptAssetVersion.TYPE_TOOL_DESC, "tool.get_quote", "行情工具描述"));

    private PromptVersionRegistrar registrarWith(AgentPromptAssetPort assets) {
        return new PromptVersionRegistrar(repository, assets, mock(ClasspathSkillRepository.class));
    }

    @Test
    @DisplayName("给定全资产 hash 均已登记，when启动对账，then不插入任何新版本行")
    void givenSameHashForAllAssets_whenRegisterAll_thenNoInsert() {
        when(repository.findVersionByHash(anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(3));

        registrarWith(fixedAssets).run(null);

        verify(repository, never()).insert(any());
    }

    @Test
    @DisplayName("给定单项内容变更（hash 未命中），when启动对账，then仅该项插入 version+1=4")
    void givenChangedHashOnOneAsset_whenRegisterAll_thenInsertBumpedVersionOnly() {
        when(repository.findVersionByHash(anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(3));
        when(repository.findVersionByHash(
                eq(PromptAssetVersion.TYPE_TOOL_DESC), eq("tool.get_quote"), anyString()))
                .thenReturn(Optional.empty());
        when(repository.latestSnapshot()).thenReturn(List.of(new PromptAssetVersion(
                1L, PromptAssetVersion.TYPE_TOOL_DESC, "tool.get_quote", 3, "旧内容hash", null, null)));

        registrarWith(fixedAssets).registerAll();

        verify(repository, times(1)).insert(argThat(v -> v.assetType().equals(PromptAssetVersion.TYPE_TOOL_DESC)
                && v.assetKey().equals("tool.get_quote") && v.version() == 4));
    }

    @Test
    @DisplayName("给定真实采集面，when启动对账，then覆盖 main 四类 27 项且 hash 均为 64 位十六进制")
    void givenRealMainAssets_whenRegisterAll_thenCovers27ItemsAcrossFourTypes() throws IOException {
        try (ClasspathSkillRepository skills = new ClasspathSkillRepository("skills")) {
            AgentPromptAssetPort collector = new ToolkitPromptAssetCollector(
                    new InvestTools(null, null, null, null, null, null, null, null),
                    null, null, null, null);
            new PromptVersionRegistrar(repository, collector, skills).registerAll();
        }

        ArgumentCaptor<String> hashes = ArgumentCaptor.forClass(String.class);
        verify(repository, times(27)).findVersionByHash(anyString(), anyString(), hashes.capture());
        assertThat(hashes.getAllValues()).allMatch(h -> h.matches("[0-9a-f]{64}"));
        verify(repository, times(1)).findVersionByHash(
                eq(PromptAssetVersion.TYPE_SYSTEM_PROMPT), eq("system.invest"), anyString());
        verify(repository, times(16)).findVersionByHash(
                eq(PromptAssetVersion.TYPE_TOOL_DESC), startsWith("tool."), anyString());
        verify(repository, times(6)).findVersionByHash(
                eq(PromptAssetVersion.TYPE_SKILL), startsWith("skill."), anyString());
        verify(repository, times(4)).findVersionByHash(
                eq(PromptAssetVersion.TYPE_INTEL_PROMPT), startsWith("intel."), anyString());
        verify(repository, times(1)).findVersionByHash(
                eq(PromptAssetVersion.TYPE_INTEL_PROMPT), eq("intel.brief_lead"), anyString());
    }

    @Test
    @DisplayName("给定仓库不可用，when run，then吞异常不阻断启动（旁路可观测降级）")
    void givenRepositoryFailure_whenRun_thenSwallowsAndDoesNotBlockStartup() {
        when(repository.findVersionByHash(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("库不可用"));

        assertThatCode(() -> registrarWith(fixedAssets).run(null))
                .doesNotThrowAnyException();
    }
}
