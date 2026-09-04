package com.portfolio.invest.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import java.nio.file.Paths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 测试分层守护：test source set（单元 + 切片）禁止依赖 Testcontainers，
 * 守住「单元/切片无 Docker」边界；真实容器只允许出现在 integrationTest / bdd
 * source set（共享基座见 testFixtures 的 PostgresTestSupport）。
 * 同时强制 {@code *SliceTest} 命名是真切片（@WebMvcTest/@JsonTest）。
 *
 * <p>{@code @AnalyzeClasses} 的 DoNotIncludeTests 会排除测试类，故此处用
 * {@link ClassFileImporter} 直接自分析 test 编译输出目录。
 */
class TestSourceSetConventionsTest {

    @DisplayName("testSourceSet禁止依赖Testcontainers")
    @Test
    void whenAnalyzingTestSourceSet_thenForbidsTestcontainersDependency() {
        JavaClasses testClasses = new ClassFileImporter()
                .importPath(Paths.get("build/classes/java/test"));
        ArchRule rule = noClasses()
                .should().dependOnClassesThat().resideInAnyPackage("org.testcontainers..")
                .as("test source set（单元+切片）禁 Docker，真实 PG 测试放 integrationTest/bdd");
        rule.check(testClasses);
    }

    /**
     * web 层双轨制命名强制：{@code *SliceTest} 必须是真切片（@WebMvcTest/@JsonTest），
     * standalone 单元测试命名为 {@code *Test}。按注解简单名判断，不锁定具体包路径
     * （Spring Boot 4 已把 WebMvcTest 移到 org.springframework.boot.webmvc 下）。
     */
    @DisplayName("sliceTest必须是真切片")
    @Test
    void givenSliceTestNamedClasses_whenChecked_thenMustBeTrueSlice() {
        JavaClasses testClasses = new ClassFileImporter()
                .importPath(Paths.get("build/classes/java/test"));
        DescribedPredicate<JavaAnnotation<?>> sliceAnnotation =
                new DescribedPredicate<>("带 @WebMvcTest 或 @JsonTest 注解") {
                    @Override
                    public boolean test(JavaAnnotation<?> annotation) {
                        String name = annotation.getRawType().getSimpleName();
                        return name.equals("WebMvcTest") || name.equals("JsonTest");
                    }
                };
        ArchRule rule = classes().that().haveSimpleNameEndingWith("SliceTest")
                .should().beAnnotatedWith(sliceAnnotation)
                .as("*SliceTest 必须是真切片（@WebMvcTest/@JsonTest），standalone 测试命名为 *Test");
        rule.check(testClasses);
    }

    /**
     * 测试方法命名规范（G6）：@Test 方法名须以 given/when/then 开头（GWT 风格），
     * 且必须带 @DisplayName 中文说明。覆盖 test 与 integrationTest 两个 source set
     * （BDD 的 Cucumber 步骤定义不在 @Test 范畴，不适用本条）。
     */
    @DisplayName("测试方法须GWT开头且带@DisplayName")
    @Test
    void givenTestMethod_whenCheckNamingConvention_thenMustFollowGwtAndHaveDisplayName() {
        JavaClasses testClasses = new ClassFileImporter()
                .importPath(Paths.get("build/classes/java/test"));
        JavaClasses integrationClasses = new ClassFileImporter()
                .importPath(Paths.get("build/classes/java/integrationTest"));

        ArchRule gwtName = methods().that().areAnnotatedWith(Test.class)
                .should().haveNameMatching("^(given|when|then).*")
                .as("测试方法名须以 given/when/then 开头（GWT 风格）");
        ArchRule displayName = methods().that().areAnnotatedWith(Test.class)
                .should().beAnnotatedWith(DisplayName.class)
                .as("测试方法须带 @DisplayName 中文说明");

        gwtName.check(testClasses);
        gwtName.check(integrationClasses);
        displayName.check(testClasses);
        displayName.check(integrationClasses);
    }
}
