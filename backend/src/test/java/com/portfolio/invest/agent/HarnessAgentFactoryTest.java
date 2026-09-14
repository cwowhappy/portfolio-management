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

    /** 四个构造依赖全 mock + 指向 @TempDir 的真实 InvestProperties。 */
    private HarnessAgentFactory factory(UserToolkitFactory toolkitFactory,
                                        SkillApplicationService skillApplicationService) throws IOException {
        return new HarnessAgentFactory(toolkitFactory, skillApplicationService,
                mock(ClasspathSkillRepository.class), mock(Model.class), tempDirProperties());
    }
}
