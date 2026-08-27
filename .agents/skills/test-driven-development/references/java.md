# Java 后端 TDD 参考（本项目：Spring Boot 4 + Java 21 + Gradle，根包 com.portfolio.invest）

## 工具链

| 层 | 工具 | 说明 |
|---|---|---|
| 测试框架 | JUnit 5（Jupiter） | org.junit.jupiter.api.Test |
| 断言 | AssertJ | assertThat(...).isEqualTo(...) / assertThatThrownBy(...) |
| Mock | Mockito | 纯 mock(...) + 构造注入（不用 @Mock / @InjectMocks） |
| 上下文 | @SpringBootTest | 仅启动类上下文冒烟；业务层全部纯单元测试 |
| 架构守护 | ArchUnit（archunit-junit5） | 分包规范由 PackageConventionsTest 强制 |
| 覆盖率 | JaCoCo | 指令/分支 ≥ 80% 门槛，test 自动触发 |

## 运行命令

```bash
./gradlew test                                       # 全量 + 覆盖率门槛校验
./gradlew test --tests "com.portfolio.invest.market.MarketDataServiceTest"   # 单类
./gradlew test --tests "*MarketDataServiceTest*"                             # 通配
./gradlew test --tests "com.portfolio.invest.market.MarketDataServiceTest#quote主源成功并缓存"  # 单方法
./gradlew test -t                                     # watch 模式（文件变化自动重跑）
```

> RED 阶段用 --tests 只跑当前测试确认红；GREEN 后每完成一条跑一次全量确认无回归（会顺带触发 JaCoCo 门槛）。

## 本项目测试风格（先读这个，保持一致）

- **纯单元测试为主**：mock 依赖 + 构造注入，不拉 Spring 上下文（快、稳、无上下文污染）。
- **测试命名**：中文直述行为的描述性方法名（如 `search空关键词抛INVALID_QUERY`、`quote主源失败降级新浪`），不用 @DisplayName。读名即知行为即可；若偏好英文可用 should<行为> 风格，关键是自解释。
- **分层**：service 用 mock 客户端 + fixture；controller 用 `mock(MarketDataService.class)` + `new MarketController(market)`；仅 `InvestAgentApplicationTest` 用 @SpringBootTest 做上下文冒烟。
- **fixture**：JSON 样例放 `src/test/resources/fixtures/`，用 ObjectMapper 读取。
- **不用** Spring 测试切片（@WebMvcTest / @DataJpaTest / @MockitoBean）：本项目未采用，新测试沿用现有纯单元风格即可。

## Red-Green-Refactor 示例（纯单元，无 Spring 上下文）

目标行为：PriceFormatter.format(1415.0) 返回两位小数字符串。

**RED** —— 先写失败测试：

```java
package com.portfolio.invest.market;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PriceFormatterTest {

    @Test
    void 保留两位小数() {
        PriceFormatter formatter = new PriceFormatter();

        assertThat(formatter.format(1415.0)).isEqualTo("1415.00");
    }
}
```

运行确认 **红**：编译失败（PriceFormatter 类不存在）—— 这正是 RED 想要的失败。

**GREEN** —— 最小实现：

```java
package com.portfolio.invest.market;

public class PriceFormatter {
    public String format(double price) {
        return String.format("%.2f", price);
    }
}
```

运行确认 **绿**。

**REFACTOR / 下一步** —— 用 Triangulation 逼出边界：负数、NaN、浮点精度（如 0.1 + 0.2），让实现更健壮；通过后再考虑提取常量/复用现有格式化工具。

## Mockito（本项目风格：mock + 构造注入）

```java
import com.portfolio.invest.market.MarketDataService;
import com.portfolio.invest.web.MarketController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketControllerTest {

    private MarketDataService market;
    private MarketController controller;

    @BeforeEach
    void setUp() {
        market = mock(MarketDataService.class);
        controller = new MarketController(market);
    }

    @Test
    void search透传查询词() {
        when(market.search("茅台")).thenReturn(List.of());

        assertThat(controller.search("茅台")).isEmpty();
        verify(market).search("茅台");
    }
}
```

> 常用匹配：`when(x.m(...)).thenReturn(...)`、`thenThrow(...)`、`verify(x, times(1))`、`eq(...)`、`anyInt()`、`any()`。断言异常用 `assertThatThrownBy(() -> ...).isInstanceOf(X.class).hasMessageContaining(...)`。

## ArchUnit（架构守护，本项目强制）

本项目用 `PackageConventionsTest`（`com.portfolio.invest.architecture` 包）强制分包规范：依赖方向白名单 web → agent → market → config、无环、落位规则（@RestController 只在 web、@ConfigurationProperties 只在 config、根包仅启动类）。TDD 同样适用于架构规则：先写断言「某包不得依赖某包」，再让新代码满足它。

```java
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

@AnalyzeClasses(packages = "com.portfolio.invest", importOptions = ImportOption.DoNotIncludeTests.class)
class PackageConventionsTest {

    private static final String ROOT = "com.portfolio.invest";

    // 依赖方向白名单：pkg 只能依赖 allowed 里的包（或项目外部类）
    private static ArchRule onlyAccess(String pkg, String... allowed) {
        return classes().that().resideInAPackage(pkg)
                .should().onlyDependOnClassesThat().resideInAnyPackage(allowed)
                .orShould().onlyDependOnClassesThat().resideOutsideOfPackage(ROOT);
    }

    @ArchTest
    static final ArchRule webOnlyAccessWhitelistedPackages =
            onlyAccess(ROOT + ".web", ROOT + ".web", ROOT + ".agent", ROOT + ".market", ROOT + ".config");

    @ArchTest
    static final ArchRule marketOnlyAccessWhitelistedPackages =
            onlyAccess(ROOT + ".market", ROOT + ".market", ROOT + ".config");

    @ArchTest
    static final ArchRule configOnlyAccessItself =
            onlyAccess(ROOT + ".config", ROOT + ".config");

    @ArchTest
    static final ArchRule domainPackagesAreFreeOfCycles =
            slices().matching(ROOT + ".(*)..").should().beFreeOfCycles();

    @ArchTest
    static final ArchRule restControllersOnlyInWeb =
            classes().that().areAnnotatedWith(RestController.class)
                    .should().resideInAPackage(ROOT + ".web");
}
```

> 新增能力域包时：新建根包下同名域包 → 在 PackageConventionsTest 登记白名单 → 同步更新 docs/technology/conventions/01-后端DDD分包规范.md。完整规则以该文件与规范文档为准。

## JaCoCo 覆盖率门槛

- test 任务自动 finalizedBy jacocoTestReport + jacocoTestCoverageVerification
- 门槛：INSTRUCTION ≥ 80% 且 BRANCH ≥ 80%，低于门槛 ./gradlew test 会失败
- 报告位置：backend/build/reports/jacoco/test/html/index.html
- TDD 下分支覆盖率不足通常是「边界用例没写」，回测试清单补测试，而非硬凑实现

## 常见反模式（避免）

- 一个测试方法里塞多个互不相关的断言 → 拆成多个测试
- 为了覆盖率写无意义的 getter/setter 测试 → 测试行为而非访问器
- 测试依赖执行顺序（共享可变状态）→ 每个测试自给自足（FIRST 的 Independent）
- 为测 Controller 拉起整个 Spring 上下文 → 本项目用 mock + 构造注入即可，保持纯单元

详细分包规范见项目 docs/technology/conventions/01-后端DDD分包规范.md。
