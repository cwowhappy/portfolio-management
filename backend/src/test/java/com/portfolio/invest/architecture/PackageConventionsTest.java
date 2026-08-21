package com.portfolio.invest.architecture;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 后端分包规范守护（详见 docs/backend-package-conventions.md）。
 *
 * <p>规则分三层：
 * <ul>
 *   <li>依赖方向白名单：web → agent/domain/market → config，禁止反向，禁止成环</li>
 *   <li>落位规则：@RestController/@RestControllerAdvice 只在 web；@ConfigurationProperties 只在 config；根包仅启动类</li>
 *   <li>稳定性规则：domain 零项目内依赖（纯业务）；web 不被任何包依赖</li>
 * </ul>
 *
 * <p>包标识统一以 {@code ..} 结尾以覆盖子包（ArchUnit 精确匹配不含子包）。
 * {@code onlyAccess} 采用「每条依赖满足 白名单包 或 项目外」的谓词组合，而非
 * {@code onlyDependOnClassesThat(A).orShould().onlyDependOnClassesThat(B)}
 * （后者要求全部依赖同时落在某一侧，类同时引用项目内外依赖时会误报）。
 * 新增能力域包（如二期的 portfolio/user）时，需在此登记其依赖白名单，并同步更新规范文档。
 */
@AnalyzeClasses(packages = "com.portfolio.invest", importOptions = ImportOption.DoNotIncludeTests.class)
class PackageConventionsTest {

    private static final String ROOT = "com.portfolio.invest";
    private static final String WEB = ROOT + ".web..";
    private static final String APPLICATION = ROOT + ".application..";
    private static final String DOMAIN = ROOT + ".domain..";
    private static final String INFRASTRUCTURE = ROOT + ".infrastructure..";
    private static final String AGENT = ROOT + ".agent..";
    private static final String CONFIG = ROOT + ".config..";
    private static final String MARKET = ROOT + ".market.."; // TRANSITIONAL: P1 迁移期间仍存在

    @ArchTest
    static final ArchRule rootPackageOnlyBootApplication =
            classes().that().resideInAPackage(ROOT)
                    .and().resideOutsideOfPackages(WEB, APPLICATION, DOMAIN, INFRASTRUCTURE, AGENT, CONFIG, MARKET)
                    .should().beAnnotatedWith(SpringBootApplication.class);

    @ArchTest
    static final ArchRule webOnlyAccessWhitelistedPackages =
            onlyAccess(WEB, WEB, APPLICATION, AGENT, DOMAIN, CONFIG, MARKET); // TRANSITIONAL: MARKET

    @ArchTest
    static final ArchRule applicationOnlyAccessWhitelistedPackages =
            onlyAccess(APPLICATION, APPLICATION, DOMAIN, CONFIG).allowEmptyShould(true);

    @ArchTest
    static final ArchRule domainHasNoInternalDependencies =
            classes().that().resideInAPackage(DOMAIN)
                    .should().onlyDependOnClassesThat(
                            resideInAPackage(DOMAIN).or(resideOutsideOfPackage(ROOT + "..")));

    @ArchTest
    static final ArchRule infrastructureOnlyAccessWhitelistedPackages =
            onlyAccess(INFRASTRUCTURE, INFRASTRUCTURE, DOMAIN, APPLICATION, CONFIG, MARKET).allowEmptyShould(true); // TRANSITIONAL: MARKET

    @ArchTest
    static final ArchRule agentOnlyAccessWhitelistedPackages =
            onlyAccess(AGENT, AGENT, APPLICATION, DOMAIN, CONFIG, MARKET); // TRANSITIONAL: MARKET

    @ArchTest
    static final ArchRule configOnlyAccessItself =
            onlyAccess(CONFIG, CONFIG);

    @ArchTest
    static final ArchRule noPackageMayDependOnWeb =
            classes().that().resideInAPackage(ROOT)
                    .and().resideOutsideOfPackage(WEB)
                    .should().onlyDependOnClassesThat().resideOutsideOfPackage(WEB);

    @ArchTest
    static final ArchRule domainPackagesAreFreeOfCycles =
            slices().matching(ROOT + ".(*)..").should().beFreeOfCycles();

    @ArchTest
    static final ArchRule restControllersOnlyInWeb =
            classes().that().areAnnotatedWith(RestController.class)
                    .or().areAnnotatedWith(RestControllerAdvice.class)
                    .should().resideInAPackage(WEB);

    @ArchTest
    static final ArchRule configurationPropertiesOnlyInConfig =
            classes().that().areAnnotatedWith(ConfigurationProperties.class)
                    .should().resideInAPackage(CONFIG);

    @ArchTest
    static final ArchRule controllersEndWithController =
            classes().that().areAnnotatedWith(RestController.class)
                    .should().haveSimpleNameEndingWith("Controller");

    /** domain 层必须纯业务：禁止 Spring 注解。 */
    @ArchTest
    static final ArchRule domainHasNoSpringAnnotations =
            classes().that().resideInAPackage(DOMAIN)
                    .should().notBeAnnotatedWith(Service.class)
                    .andShould().notBeAnnotatedWith(Component.class)
                    .andShould().notBeAnnotatedWith(org.springframework.context.annotation.Configuration.class)
                    .andShould().notBeAnnotatedWith(RestController.class);

    private static ArchRule onlyAccess(String pkg, String... allowed) {
        return classes().that().resideInAPackage(pkg)
                .should().onlyDependOnClassesThat(
                        resideInAnyPackage(allowed).or(resideOutsideOfPackage(ROOT + "..")));
    }
}
