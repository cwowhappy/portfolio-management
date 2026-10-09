package com.portfolio.invest.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.invest.application.skill.SkillApplicationService;
import com.portfolio.invest.config.InvestProperties;
import io.agentscope.core.model.Model;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** HarnessAgent 装配：skillFilter 白名单语义 + build() 装配契约（compaction/memory 等配置生效归集成层）。 */
class HarnessAgentFactoryTest {

    @TempDir
    Path tempDir;

    @DisplayName("空启用集合 → 白名单空白名单，无 skill 放行")
    @Test
    void givenEmptyEnabled_whenSkillFilter_thenNoSkillAllowed() {
        var filter = HarnessAgentFactory.skillFilter(List.of());
        assertThat(filter.isAllowed("tushare_data")).isFalse();
        assertThat(filter.isAllowed("wind_finance")).isFalse();
    }

    @DisplayName("启用集合 → 仅启用的 skill 放行")
    @Test
    void givenEnabled_whenSkillFilter_thenOnlyEnabledAllowed() {
        var filter = HarnessAgentFactory.skillFilter(List.of("tushare_data"));
        assertThat(filter.isAllowed("tushare_data")).isTrue();
        assertThat(filter.isAllowed("wind_finance")).isFalse();
    }

    @DisplayName("启用技能非空 → build 装配出 HarnessAgent，且以该用户构建工具")
    @Test
    void givenEnabledSkills_whenBuild_thenAgentBuiltWithUserToolkit() throws IOException {
        UserToolkitFactory toolkitFactory = mock(UserToolkitFactory.class);
        SkillApplicationService skillApplicationService = mock(SkillApplicationService.class);
        when(skillApplicationService.enabledSkillCodes(1L)).thenReturn(List.of("tushare_data"));
        when(toolkitFactory.build(1L)).thenReturn(new Toolkit());

        HarnessAgent agent = factory(toolkitFactory, skillApplicationService).build(1L);

        assertThat(agent).isNotNull();
        verify(toolkitFactory).build(1L);
    }

    @DisplayName("无启用技能 → 空白名单仍完成装配")
    @Test
    void givenNoEnabledSkills_whenBuild_thenAgentBuiltWithEmptyWhitelist() throws IOException {
        UserToolkitFactory toolkitFactory = mock(UserToolkitFactory.class);
        SkillApplicationService skillApplicationService = mock(SkillApplicationService.class);
        when(skillApplicationService.enabledSkillCodes(1L)).thenReturn(List.of());
        when(toolkitFactory.build(1L)).thenReturn(new Toolkit());

        HarnessAgent agent = factory(toolkitFactory, skillApplicationService).build(1L);

        assertThat(agent).isNotNull();
    }

    @DisplayName("MS-29 B5 + MS-30 B6 挂载：build() 装配双 hook（trust 先行、观测后行）与观察中间件")
    @Test
    void givenBuild_whenAssemble_thenTrustAndObservabilityHooksAndMiddlewareMounted() throws IOException {
        UserToolkitFactory toolkitFactory = mock(UserToolkitFactory.class);
        SkillApplicationService skillApplicationService = mock(SkillApplicationService.class);
        when(skillApplicationService.enabledSkillCodes(1L)).thenReturn(List.of());
        when(toolkitFactory.build(1L)).thenReturn(new Toolkit());

        HarnessAgent agent = factory(toolkitFactory, skillApplicationService).build(1L);

        List<io.agentscope.core.hook.Hook> hooks = agent.getDelegate().getHooks();
        assertThat(hooks)
                .anyMatch(h -> h instanceof com.portfolio.invest.agent.trust.TrustAgentHook);
        assertThat(hooks)
                .anyMatch(h -> h instanceof com.portfolio.invest.agent.observability.ObservabilityAgentHook);
        // 装配序钉死：trust 先行（默认 priority 100）、观测后行（priority 200 读其产物）
        int trustIndex = indexOfInstance(hooks, com.portfolio.invest.agent.trust.TrustAgentHook.class);
        int observabilityIndex =
                indexOfInstance(hooks, com.portfolio.invest.agent.observability.ObservabilityAgentHook.class);
        assertThat(trustIndex).isLessThan(observabilityIndex);
        assertThat(agent.getDelegate().getMiddlewares())
                .anyMatch(m -> m instanceof com.portfolio.invest.agent.trust.TrustWireMessageIdMiddleware);
    }

    private static int indexOfInstance(List<io.agentscope.core.hook.Hook> hooks, Class<?> type) {
        for (int i = 0; i < hooks.size(); i++) {
            if (type.isInstance(hooks.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /** 真实 InvestProperties（Harness 嵌套 POJO 有默认值），workspace/stateRoot 指向 @TempDir，避免触碰仓库工作目录。 */
    private InvestProperties tempDirProperties() throws IOException {
        // 预建目录：WorkspaceManager 对不存在的 workspace 会告警（测试脚手架，非生产改动）
        Files.createDirectories(tempDir.resolve("workspace"));
        Files.createDirectories(tempDir.resolve("state"));
        InvestProperties props = new InvestProperties();
        props.getMcp().getHarness().setWorkspace(tempDir.resolve("workspace").toString());
        props.getMcp().getHarness().setStateRoot(tempDir.resolve("state").toString());
        return props;
    }

    /** 构造依赖全 mock + 指向 @TempDir 的真实 InvestProperties + 观测端口/ObjectMapper 替身。 */
    private HarnessAgentFactory factory(UserToolkitFactory toolkitFactory,
                                        SkillApplicationService skillApplicationService) throws IOException {
        return new HarnessAgentFactory(toolkitFactory, skillApplicationService,
                mock(ClasspathSkillRepository.class), mock(Model.class), tempDirProperties(),
                mock(com.portfolio.invest.domain.observability.ObservabilityRecorder.class),
                new com.fasterxml.jackson.databind.ObjectMapper());
    }
}
