package com.portfolio.invest.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Flyway 迁移数守护（MS-29 B9-⑤）：db/migration 目录 .sql 文件数须与预期常量一致。
 * 历史三次「新增迁移漏改版本断言」教训——本守护在漏加/漏删迁移文件时变红，强制同步常量。
 */
class FlywayMigrationCountGuardTest {

    /**
     * 当前迁移文件数（V1 基线 squash + V2 research + V3 intelligence + V4 p2 索引清理
     * + V5 MS-30 评测/观测四表 + V6 eval_run 增 track 列）。
     * 新增迁移须同步 +1（删并迁移则相应调整），随 commit 一起改。
     */
    private static final int EXPECTED_MIGRATION_COUNT = 6;

    @Test
    @DisplayName("迁移目录 .sql 文件数与预期常量一致（新增迁移须同步 +1，B9-⑤）")
    void givenMigrationDirectory_whenCountSqlFiles_thenMatchesExpectedConstant() throws Exception {
        Resource[] sqls = new PathMatchingResourcePatternResolver()
                .getResources("classpath:db/migration/*.sql");
        List<String> names = new ArrayList<>();
        for (Resource r : sqls) {
            names.add(r.getFilename());
        }
        assertThat(names)
                .as("db/migration 实际文件 %s 与常量不一致：新增/删除迁移须同步调整 EXPECTED_MIGRATION_COUNT",
                        names)
                .hasSize(EXPECTED_MIGRATION_COUNT);
    }
}
