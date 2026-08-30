package com.portfolio.invest.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

/**
 * 测试分层守护：test source set（单元 + 切片）禁止依赖 Testcontainers，
 * 守住「单元/切片无 Docker」边界；真实容器只允许出现在 integrationTest / bdd
 * source set（共享基座见 testFixtures 的 PostgresTestSupport）。
 *
 * <p>{@code @AnalyzeClasses} 的 DoNotIncludeTests 会排除测试类，故此处用
 * {@link ClassFileImporter} 直接自分析 test 编译输出目录。
 */
class TestSourceSetConventionsTest {

    @Test
    void testSourceSet禁止依赖Testcontainers() {
        JavaClasses testClasses = new ClassFileImporter()
                .importPath(Paths.get("build/classes/java/test"));
        ArchRule rule = noClasses()
                .should().dependOnClassesThat().resideInAnyPackage("org.testcontainers..")
                .as("test source set（单元+切片）禁 Docker，真实 PG 测试放 integrationTest/bdd");
        rule.check(testClasses);
    }
}
