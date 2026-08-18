package com.portfolio.invest.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 后端分包规范守护（详见 docs/backend-package-conventions.md）。
 *
 * <p>规则分三层：
 * <ul>
 *   <li>依赖方向白名单：web → agent → market → config，禁止反向，禁止成环</li>
 *   <li>落位规则：@RestController/@RestControllerAdvice 只在 web；@ConfigurationProperties 只在 config；根包仅启动类</li>
 *   <li>稳定性规则：market.dto 零项目内依赖；web 不被任何包依赖</li>
 * </ul>
 *
 * <p>新增能力域包（如二期的 portfolio/user）时，需在此登记其依赖白名单，并同步更新规范文档。
 */
@AnalyzeClasses(packages = "com.portfolio.invest", importOptions = ImportOption.DoNotIncludeTests.class)
class PackageConventionsTest {

    private static final String ROOT = "com.portfolio.invest";
    private static final String WEB = ROOT + ".web";
    private static final String AGENT = ROOT + ".agent";
    private static final String MARKET = ROOT + ".market";
    private static final String DTO = MARKET + ".dto";
    private static final String CONFIG = ROOT + ".config";

    // ---------- 1. 根包：仅启动类 ----------

    /** 根包只允许 @SpringBootApplication 启动类，业务类必须进对应域包。 */
    @ArchTest
    static final ArchRule rootPackageOnlyBootApplication =
            classes().that().resideInAPackage(ROOT)
                    .and().resideOutsideOfPackages(WEB, AGENT, MARKET, CONFIG)
                    .should().beAnnotatedWith(SpringBootApplication.class);

    // ---------- 2. 依赖方向白名单（只约束项目内部包，外部库不受限） ----------

    /** web（接入层）：可依赖 agent、market（含 dto）、config。 */
    @ArchTest
    static final ArchRule webOnlyAccessWhitelistedPackages =
            onlyAccess(WEB, WEB, AGENT, MARKET, CONFIG);

    /** agent（Agent 能力域）：可依赖 market（含 dto）、config。 */
    @ArchTest
    static final ArchRule agentOnlyAccessWhitelistedPackages =
            onlyAccess(AGENT, AGENT, MARKET, CONFIG);

    /** market（行情数据能力域）：可依赖 config，域内自由。 */
    @ArchTest
    static final ArchRule marketOnlyAccessWhitelistedPackages =
            onlyAccess(MARKET, MARKET, CONFIG);

    /** config（全局配置）：不得依赖任何业务包。 */
    @ArchTest
    static final ArchRule configOnlyAccessItself =
            onlyAccess(CONFIG, CONFIG);

    /** market.dto（数据契约）：零项目内依赖，只能依赖自身与 JDK/外部库。 */
    @ArchTest
    static final ArchRule dtoHasNoInternalDependencies =
            classes().that().resideInAPackage(DTO)
                    .should().onlyDependOnClassesThat().resideInAPackage(DTO)
                    .orShould().onlyDependOnClassesThat().resideOutsideOfPackage(ROOT);

    /** web 是顶层接入层：任何其他包不得依赖 web。 */
    @ArchTest
    static final ArchRule noPackageMayDependOnWeb =
            classes().that().resideInAPackage(ROOT)
                    .and().resideOutsideOfPackage(WEB)
                    .should().onlyDependOnClassesThat().resideOutsideOfPackage(WEB);

    // ---------- 3. 无循环 ----------

    /** 二级能力域包（web/agent/market/config）之间不得形成依赖环。 */
    @ArchTest
    static final ArchRule domainPackagesAreFreeOfCycles =
            slices().matching(ROOT + ".(*)..").should().beFreeOfCycles();

    // ---------- 4. 落位规则 ----------

    /** HTTP 边界收敛在接入层：@RestController/@RestControllerAdvice 只能在 web 包。 */
    @ArchTest
    static final ArchRule restControllersOnlyInWeb =
            classes().that().areAnnotatedWith(RestController.class)
                    .or().areAnnotatedWith(RestControllerAdvice.class)
                    .should().resideInAPackage(WEB);

    /** 配置属性统一在全局配置包：@ConfigurationProperties 只能在 config 包。 */
    @ArchTest
    static final ArchRule configurationPropertiesOnlyInConfig =
            classes().that().areAnnotatedWith(ConfigurationProperties.class)
                    .should().resideInAPackage(CONFIG);

    /** 命名惯例：@RestController 类以 Controller 结尾。 */
    @ArchTest
    static final ArchRule controllersEndWithController =
            classes().that().areAnnotatedWith(RestController.class)
                    .should().haveSimpleNameEndingWith("Controller");

    /** 包内依赖白名单：pkg 内的类只能依赖 allowed 列表中的项目内包（或项目外部的类）。 */
    private static ArchRule onlyAccess(String pkg, String... allowed) {
        return classes().that().resideInAPackage(pkg)
                .should().onlyDependOnClassesThat().resideInAnyPackage(allowed)
                .orShould().onlyDependOnClassesThat().resideOutsideOfPackage(ROOT);
    }
}
