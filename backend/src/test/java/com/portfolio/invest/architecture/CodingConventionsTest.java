package com.portfolio.invest.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.springframework.transaction.annotation.Transactional;

/**
 * 后端架构与代码规范守护（详见 docs/technology/conventions/02-后端架构与代码规范.md 附录清单）。
 *
 * <p>与 {@link PackageConventionsTest}（01 规范：分包与依赖方向）互补，本类断言「类怎么写」的
 * ArchUnit 强制条目：A2 事务边界、A3/E4 时间与环境注入、C2 JPA 注解落位、H7 Jackson 2 锁定。
 */
@AnalyzeClasses(packages = "com.portfolio.invest", importOptions = ImportOption.DoNotIncludeTests.class)
class CodingConventionsTest {

    private static final String ROOT = "com.portfolio.invest";
    private static final String APPLICATION = ROOT + ".application..";
    private static final String DOMAIN = ROOT + ".domain..";
    private static final String PERSISTENCE = ROOT + ".infrastructure.persistence..";

    /** A2：事务是用例语义，@Transactional（方法级）只允许出现在 application 层。 */
    @ArchTest
    static final ArchRule transactionalMethodsOnlyInApplication =
            methods().that().areAnnotatedWith(Transactional.class)
                    .should().beDeclaredInClassesThat().resideInAPackage(APPLICATION);

    /** A2：事务是用例语义，@Transactional（类级）只允许出现在 application 层。 */
    @ArchTest
    static final ArchRule transactionalClassesOnlyInApplication =
            classes().that().areAnnotatedWith(Transactional.class)
                    .should().resideInAPackage(APPLICATION)
                    .allowEmptyShould(true);

    /** A3/E4：domain/application 的时间与环境必须可注入，禁止直调 System 时钟/环境变量。 */
    @ArchTest
    static final ArchRule domainAndApplicationDoNotCallSystemClockOrEnv =
            noClasses().that().resideInAnyPackage(DOMAIN, APPLICATION)
                    .should().callMethod(System.class, "currentTimeMillis")
                    .orShould().callMethod(System.class, "nanoTime")
                    .orShould().callMethod(System.class, "getenv")
                    .orShould().callMethod(System.class, "getenv", String.class);

    /** C2：JPA 实体注解只在 infrastructure.persistence，领域层保持纯 POJO。 */
    @ArchTest
    static final ArchRule jpaEntityAnnotationsOnlyInPersistence =
            classes().that().areAnnotatedWith(Entity.class)
                    .or().areAnnotatedWith(Table.class)
                    .should().resideInAPackage(PERSISTENCE);

    /** H7：AgentScope AG-UI 模型基于 Jackson 2 注解，项目代码禁止依赖 Jackson 3。 */
    @ArchTest
    static final ArchRule noJackson3Dependencies =
            noClasses().should().dependOnClassesThat().resideInAnyPackage("tools.jackson..");
}
