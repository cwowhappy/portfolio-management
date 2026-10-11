package com.portfolio.invest.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * domain 层时钟规范守护（详见 docs/technology/conventions/02-后端架构与代码规范.md）。
 *
 * <p>领域层为纯 POJO，时间必须由外部传入（实体工厂/行为方法加 {@code Instant now} 末参，
 * 调用方传 {@code clock.instant()}），禁止直调系统时钟——保证领域行为可测且时间来源单一。
 * 与 {@link CodingConventionsTest} 的 A3/E4（禁 System 时钟）互补，本类钉死 java.time 直调。
 */
@AnalyzeClasses(packages = "com.portfolio.invest", importOptions = ImportOption.DoNotIncludeTests.class)
class DomainClockConventionTest {

    private static final String DOMAIN = "com.portfolio.invest.domain..";

    /** domain 禁调 Instant.now()——时间全入参，由调用方注入 Clock 提供。 */
    @ArchTest
    static final ArchRule domainShouldNotCallInstantNow =
            noClasses().that().resideInAPackage(DOMAIN)
                    .should().callMethod(Instant.class, "now");

    /** domain 禁调 LocalDate.now()——日期一律由入参时钟推导或显式传入。 */
    @ArchTest
    static final ArchRule domainShouldNotCallLocalDateNow =
            noClasses().that().resideInAPackage(DOMAIN)
                    .should().callMethod(LocalDate.class, "now");

    /** domain 禁调 LocalDateTime.now()——时间全入参，由调用方注入 Clock 提供。 */
    @ArchTest
    static final ArchRule domainShouldNotCallLocalDateTimeNow =
            noClasses().that().resideInAPackage(DOMAIN)
                    .should().callMethod(LocalDateTime.class, "now");
}
