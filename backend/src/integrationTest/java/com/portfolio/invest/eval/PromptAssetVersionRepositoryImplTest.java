package com.portfolio.invest.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.invest.application.eval.PromptVersionRegistrar;
import com.portfolio.invest.domain.eval.PromptAssetVersion;
import com.portfolio.invest.domain.eval.PromptAssetVersionRepository;
import com.portfolio.invest.support.PostgresTestSupport;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 提示词版本登记真库契约（Testcontainers PG16 + V5 prompt_asset_version）：启动对账 27 项
 * 幂等（重复 registerAll 不增行、恒 v1）；upsert 同 hash 单行、变 hash version+1 且旧行保留
 * （append-only）；(asset_type, asset_key, version) 唯一约束兜底并发双写。
 *
 * <p>probe.* 键为测试专造资产，@BeforeEach/@AfterEach 双向清理（Task 3 教训：共享容器不留
 * 跨类污染）；真实 27 项由上下文启动 runner 与 registerAll 幂等共写，键固定、断言确定性不受
 * 类执行顺序影响。
 */
@SpringBootTest
class PromptAssetVersionRepositoryImplTest extends PostgresTestSupport {

    @Autowired
    PromptAssetVersionRepository repository;
    @Autowired
    PromptVersionRegistrar registrar;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void cleanProbeRows() {
        jdbc.update("DELETE FROM prompt_asset_version WHERE asset_key LIKE 'probe.%'");
    }

    @Test
    @DisplayName("给定真实 main 四类资产，when重复 registerAll，then恒 27 项且全部 v1（幂等不增行）")
    void givenRealMainAssets_whenRegisterAllRepeated_thenStays27RowsAtVersion1() {
        registrar.registerAll();
        registrar.registerAll();

        List<PromptAssetVersion> snapshot = repository.latestSnapshot();
        assertThat(snapshot).hasSize(27);
        assertThat(snapshot).allMatch(v -> v.version() == 1);
        assertThat(snapshot.stream().map(PromptAssetVersion::assetType).distinct())
                .containsExactlyInAnyOrder(PromptAssetVersion.TYPE_SYSTEM_PROMPT,
                        PromptAssetVersion.TYPE_TOOL_DESC, PromptAssetVersion.TYPE_SKILL,
                        PromptAssetVersion.TYPE_INTEL_PROMPT);
    }

    @Test
    @DisplayName("给定同 hash 两次 upsert，when对账，then仅一行且版本 1")
    void givenSameHash_whenUpsertTwice_thenSingleRowAtVersion1() {
        registrar.upsert(PromptAssetVersion.TYPE_SKILL, "probe.skill", "hash-a");
        registrar.upsert(PromptAssetVersion.TYPE_SKILL, "probe.skill", "hash-a");

        assertThat(repository.findVersionByHash(PromptAssetVersion.TYPE_SKILL, "probe.skill", "hash-a"))
                .contains(1);
        assertThat(countRows("probe.skill")).isEqualTo(1);
    }

    @Test
    @DisplayName("给定内容变 hash，when再 upsert，then版本 +1 且旧行保留（append-only 版本链）")
    void givenChangedHash_whenUpsertAgain_thenVersionBumpedAndHistoryKept() {
        registrar.upsert(PromptAssetVersion.TYPE_SKILL, "probe.skill", "hash-a");
        registrar.upsert(PromptAssetVersion.TYPE_SKILL, "probe.skill", "hash-b");

        assertThat(repository.findVersionByHash(PromptAssetVersion.TYPE_SKILL, "probe.skill", "hash-b"))
                .contains(2);
        assertThat(repository.latestSnapshot().stream()
                .filter(v -> "probe.skill".equals(v.assetKey()))
                .findFirst().orElseThrow().version())
                .isEqualTo(2);
        assertThat(countRows("probe.skill")).isEqualTo(2);
    }

    @Test
    @DisplayName("给定重复 (类型,键,版本)，when直接 insert，then唯一约束拒绝")
    void givenDuplicateVersionRow_whenInsert_thenRejectedByUniqueConstraint() {
        repository.insert(PromptAssetVersion.newRegistration(
                PromptAssetVersion.TYPE_SKILL, "probe.dup", 1, "hash-a"));

        assertThatThrownBy(() -> repository.insert(PromptAssetVersion.newRegistration(
                PromptAssetVersion.TYPE_SKILL, "probe.dup", 1, "hash-b")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("给定多资产多版本，when查版本链，then按类型键升序版本倒序（看板链形态）")
    void givenMultipleAssetsAndVersions_whenFindVersionChain_thenOrderedTypeKeyAscVersionDesc() {
        registrar.upsert(PromptAssetVersion.TYPE_SKILL, "probe.skill", "hash-a");  // v1
        registrar.upsert(PromptAssetVersion.TYPE_SKILL, "probe.skill", "hash-b");  // v2
        registrar.upsert(PromptAssetVersion.TYPE_TOOL_DESC, "probe.tool", "hash-c"); // v1

        List<PromptAssetVersion> chain = repository.findVersionChain().stream()
                .filter(v -> v.assetKey().startsWith("probe.")).toList();

        assertThat(chain).extracting(PromptAssetVersion::assetKey, PromptAssetVersion::version)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("probe.skill", 2),
                        org.assertj.core.groups.Tuple.tuple("probe.skill", 1),
                        org.assertj.core.groups.Tuple.tuple("probe.tool", 1));
        assertThat(chain.get(0).id()).isNotNull();
        assertThat(chain.get(0).registeredAt()).isNotNull();
    }

    private Integer countRows(String assetKey) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM prompt_asset_version WHERE asset_key = ?", Integer.class, assetKey);
    }
}
