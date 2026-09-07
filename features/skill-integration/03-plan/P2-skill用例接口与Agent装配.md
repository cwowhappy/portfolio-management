# Skill 用例接口与 Agent 装配 Implementation Plan（P2）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落地 `application.skill`（目录/配置用例 + 单例 `ClasspathSkillRepository`）、`web`（REST 接口）、内置 SKILL.md 资源、`agent` 装配（`SkillFilter`）+ 提示词规约，全部经单元/切片测试验证。

**Architecture:** 沿用 MCP 范式。`ClasspathSkillRepository` 是 AgentScope 框架类型，落位 `application.skill`（`application` 层允许用框架类型，同 `AgentScopeMcpServerTester`）；`agent` 依赖 `application.skill` 拿启用集合与仓库单例。

**Tech Stack:** AgentScope 2.0.1（`ClasspathSkillRepository`/`SkillFilter`/`HarnessAgent`）/ Spring Web MVC / JUnit 5 + AssertJ + Mockito / `@WebMvcTest` 切片

**Spec:** `features/skill-integration/02-design/设计规格说明.md`（§三模块落位、§四运行时装配、§五 API、§六提示词、§十一技能草稿）、`01-requirement/需求规格说明.md`

## Global Constraints

- 覆盖门槛 ≥80%；ArchUnit：`application` 只依赖 `APPLICATION/DOMAIN/CONFIG` + 项目外（框架类型允许）；`agent` 依赖 `AGENT/APPLICATION/DOMAIN/CONFIG`。
- 控制器 `currentUserId(Authentication)` = `((AuthenticatedUser) auth.getPrincipal()).user().id()`；切片测试用 `UsernamePasswordAuthenticationToken(new AuthenticatedUser(user), null, principal.getAuthorities())` + `.with(authentication(auth()))`（`@WithMockUser` 的 principal 非 `AuthenticatedUser`，不可用）。
- `ClasspathSkillRepository` 是 `AutoCloseable`，内部按 URI 引用计数挂载 JAR 虚拟 FS——**单例 `@Bean`，勿每次 build 时 new/close**。
- Skill 目录在 classpath `resources/skills/**/SKILL.md`（main 资源），frontmatter 字段 `name`/`description`/`category`/`default_enabled`/`depends_on_provider`。
- 包名前缀 `com.portfolio.invest`。

---

### Task 1: 内置 SKILL.md 资源 + 目录契约测试

**Files:**
- Create: `backend/src/main/resources/skills/tushare_data/SKILL.md`
- Create: `backend/src/main/resources/skills/wind_finance/SKILL.md`
- Test: `backend/src/test/java/com/portfolio/invest/application/skill/BuiltInSkillCatalogTest.java`

**Interfaces:**
- Produces: main classpath 下 2 个内置 skill，`ClasspathSkillRepository("skills")` 可枚举且 frontmatter 元数据齐全（`category`/`default_enabled`/`depends_on_provider`）。

- [ ] **Step 1: 写两个 SKILL.md 资源**

`tushare_data/SKILL.md`：
```markdown
---
name: tushare_data
description: 使用 Tushare 官方 MCP 获取内置工具与 Wind 未覆盖的品类数据：期货期权、港美股、债券、宏观指标序列、财务三表明细、指数成分/权重，覆盖 220+ 接口。
category: data_source
default_enabled: false
depends_on_provider: tushare
---

# Tushare 全品类金融数据补充

## 定位
内置工具、妙想、Wind 均无法覆盖时，作为广度兜底数据源取数。

## 触发条件
- 用：需求落在期货期权 / 港美股 / 债券 / 宏观指标序列 / 财务三表明细 / 指数成分等内置工具不覆盖的品类。
- 不用：A股行情/K线/财务/新闻/大盘/估值等内置工具已覆盖（优先内置工具）；公告/新闻/招股书/EDB 宏观/跨标的聚合等 Wind 独有（走 `wind_finance`）。

## 覆盖域
| 品类 | 说明 |
|---|---|
| 期货期权 | 期货日线、主力连续 |
| 港美股 | 港股/美股日线与基本信息 |
| 债券 | 可转债、债券基本信息、收益率 |
| 宏观序列 | GDP、CPI、PMI、利率、货币供应 |
| 财务三表 | 利润表/资产负债表/现金流量表明细 |
| 指数成分 | 指数成分股与权重 |
| 基金 | 基金净值、基本信息 |

## 流程
1. 从工具清单定位 `mcp__tushare__*` 对应工具。
2. 取数：日期 YYYYMMDD；股票代码 ts_code（如 600519.SH / 000001.SZ）。
3. 失败如实告知（积分/权限不足常见），不重复重试、不伪造。

## 来源声明
引用本技能数据时标注「数据来源于 Tushare」。
```

`wind_finance/SKILL.md`：
```markdown
---
name: wind_finance
description: 使用 Wind AIFin 官方 MCP 获取机构级金融数据与文档：A股/港美股/基金/债券/指数行情与财务、公告年报季报招股书、财经新闻、宏观 EDB 指标、跨标的聚合排名与复合指标。
category: data_source
default_enabled: false
depends_on_provider: wind
---

# Wind 机构级金融数据与文档

## 定位
机构级权威数据 + 文档语义 + 跨标的聚合分析。

## 触发条件
- 用：公告/年报/季报/招股书/财经新闻、宏观/行业/汇率 EDB 指标、跨标的聚合/加权/排名。
- 不用：内置工具已覆盖的 A股行情/K线/财务/新闻/大盘/估值（优先内置工具）；期货/港美股/债券/三表/指数成分等 Tushare 独有（走 `tushare_data`）。

## 覆盖域（7 个 server_type）
| server_type | 覆盖 | 代表工具 |
|---|---|---|
| stock_data | 股票筛选/行情/K线/财务/股东/事件/技术/风险 | get_stock_price_indicators、search_* |
| fund_data | 基金/ETF/LOF 筛选/行情/持仓/业绩 | get_fund_price_indicators |
| index_data | 指数/板块行情/基本面/技术 | get_index_price_indicators |
| bond_data | 债券档案/发债主体/行情估值 | — |
| financial_docs | 公告/年报/季报/招股书/财经新闻 | get_company_announcements、get_financial_news |
| economic_data | 宏观/行业/汇率 EDB 指标 | search_economic_indicator、query_economic_indicator_data |
| analytics_data | 跨标的聚合/加权/排名/复合指标 | — |

## 流程
1. 定路由：按标的类型选 server_type（见上表）。
2. 取数：行情/财务走对应领域专项工具；文档走 financial_docs；宏观先 search_economic_indicator 确认指标代码再 query_economic_indicator_data；聚合/排名走 analytics_data。
3. 读回执：成功读数据；失败按错误码说明，不得用 analytics_data 伪装支持。

## 来源声明
引用本技能数据时标注「数据来源于万得 Wind 金融数据服务」。
```

- [ ] **Step 2: 写目录契约测试**

`BuiltInSkillCatalogTest.java`：
```java
package com.portfolio.invest.application.skill;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BuiltInSkillCatalogTest {

    @DisplayName("main classpath 内置 2 个 skill 且 frontmatter 元数据齐全")
    @Test
    void givenMainResources_whenEnumerate_thenTwoSkillsWithMetadata() throws Exception {
        try (ClasspathSkillRepository repo = new ClasspathSkillRepository("skills")) {
            assertThat(repo.getAllSkillNames()).containsExactlyInAnyOrder("tushare_data", "wind_finance");

            var tushare = repo.getSkill("tushare_data");
            assertThat(tushare.getMetadata()).containsEntry("category", "data_source");
            assertThat(tushare.getMetadata()).containsEntry("default_enabled", false);
            assertThat(tushare.getMetadata()).containsEntry("depends_on_provider", "tushare");

            var wind = repo.getSkill("wind_finance");
            assertThat(wind.getMetadata()).containsEntry("depends_on_provider", "wind");
        }
    }
}
```

Run: `cd backend && ./gradlew test --tests "com.portfolio.invest.application.skill.BuiltInSkillCatalogTest" --console=plain`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/resources/skills backend/src/test/java/com/portfolio/invest/application/skill/BuiltInSkillCatalogTest.java
git commit -m "feat(skill): 内置 tushare_data / wind_finance 两个 SKILL.md 资源"
```

---

### Task 2: 单例仓库 Bean + 用例服务 + 视图

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/application/skill/SkillRepositoryConfig.java`
- Create: `backend/src/main/java/com/portfolio/invest/application/skill/SkillView.java`
- Create: `backend/src/main/java/com/portfolio/invest/application/skill/SkillApplicationService.java`
- Test: `backend/src/test/java/com/portfolio/invest/application/skill/SkillApplicationServiceTest.java`

**Interfaces:**
- Consumes: P1 的 `SkillConfigRepository` / `SkillUserConfig`；`io.agentscope.core.skill.AgentSkill` / `ClasspathSkillRepository`。
- Produces:
  - `SkillApplicationService.catalog(Long userId)` → `List<SkillView>`
  - `SkillApplicationService.enabledSkillCodes(Long userId)` → `List<String>`
  - `SkillApplicationService.save(Long userId, List<String> enabledCodes)` → `List<SkillView>`
  - `SkillView(String skillCode, String description, String category, boolean defaultEnabled, String dependsOnProvider, boolean enabled)` + `SkillView.from(AgentSkill, boolean)`
  - `@Bean ClasspathSkillRepository builtInSkillRepository()`（单例）

- [ ] **Step 1: 写失败测试（TDD 红）**

`SkillApplicationServiceTest.java`：
```java
package com.portfolio.invest.application.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.invest.domain.skill.SkillConfigRepository;
import com.portfolio.invest.domain.skill.SkillUserConfig;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SkillApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-07T08:00:00Z");

    private final SkillConfigRepository repo = mock(SkillConfigRepository.class);
    private final ClasspathSkillRepository builtInSkills = mock(ClasspathSkillRepository.class);
    private SkillApplicationService service;

    @BeforeEach
    void setUp() {
        service = new SkillApplicationService(repo, builtInSkills);
    }

    private static AgentSkill skill(String name, boolean defaultEnabled) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("name", name);
        meta.put("description", name + " 描述");
        meta.put("category", "data_source");
        meta.put("default_enabled", defaultEnabled);
        meta.put("depends_on_provider", name.startsWith("tushare") ? "tushare" : "wind");
        return new AgentSkill(meta, "# " + name, Map.of(), "test");
    }

    @DisplayName("无用户配置时按 default_enabled 回落")
    @Test
    void givenNoUserConfig_whenCatalog_thenFallbackToDefaultEnabled() {
        when(builtInSkills.getAllSkills()).thenReturn(List.of(skill("tushare_data", false), skill("wind_finance", false)));
        when(repo.findByUserId(1L)).thenReturn(List.of());

        var catalog = service.catalog(1L);

        assertThat(catalog).extracting(SkillView::skillCode).containsExactlyInAnyOrder("tushare_data", "wind_finance");
        assertThat(catalog).allSatisfy(v -> assertThat(v.enabled()).isFalse());
        assertThat(catalog).allSatisfy(v -> assertThat(v.defaultEnabled()).isFalse());
    }

    @DisplayName("用户显式启用覆盖 default_enabled")
    @Test
    void givenUserConfig_whenCatalog_thenOverrideDefault() {
        when(builtInSkills.getAllSkills()).thenReturn(List.of(skill("tushare_data", false), skill("wind_finance", false)));
        when(repo.findByUserId(1L)).thenReturn(List.of(
                SkillUserConfig.reconstitute(9L, 1L, "tushare_data", true, NOW)));

        var catalog = service.catalog(1L);
        var tushare = catalog.stream().filter(v -> v.skillCode().equals("tushare_data")).findFirst().orElseThrow();

        assertThat(tushare.enabled()).isTrue();
        assertThat(tushare.dependsOnProvider()).isEqualTo("tushare");
    }

    @DisplayName("enabledSkillCodes 只返回生效启用的 code")
    @Test
    void givenUserConfig_whenEnabledSkillCodes_thenOnlyEnabledReturned() {
        when(builtInSkills.getAllSkills()).thenReturn(List.of(skill("tushare_data", false), skill("wind_finance", false)));
        when(repo.findByUserId(1L)).thenReturn(List.of(
                SkillUserConfig.reconstitute(9L, 1L, "tushare_data", true, NOW)));

        assertThat(service.enabledSkillCodes(1L)).containsExactly("tushare_data");
    }

    @DisplayName("save 对每个内置 skill 做启停 upsert")
    @Test
    void givenSave_whenEnabledSet_thenUpsertEachSkill() {
        when(builtInSkills.getAllSkills()).thenReturn(List.of(skill("tushare_data", false), skill("wind_finance", false)));
        when(repo.findByUserIdAndSkillCode(any(), any())).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repo.findByUserId(1L)).thenReturn(List.of());

        service.save(1L, List.of("tushare_data"));

        verify(repo).save(argThat(c -> c.skillCode().equals("tushare_data") && c.enabled()));
        verify(repo).save(argThat(c -> c.skillCode().equals("wind_finance") && !c.enabled()));
    }
}
```

Run: `cd backend && ./gradlew test --tests "com.portfolio.invest.application.skill.SkillApplicationServiceTest" --console=plain`
Expected: FAIL（`SkillApplicationService`/`SkillView` 未定义）

- [ ] **Step 2: 实现（TDD 绿）**

`SkillRepositoryConfig.java`：
```java
package com.portfolio.invest.application.skill;

import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 单例 ClasspathSkillRepository：内部按 URI 引用计数挂载 JAR 虚拟 FS，随 Spring 生命周期管理，勿每次 build 时 new/close。 */
@Configuration
public class SkillRepositoryConfig {

    @Bean
    public ClasspathSkillRepository builtInSkillRepository() throws IOException {
        return new ClasspathSkillRepository("skills");
    }
}
```

`SkillView.java`：
```java
package com.portfolio.invest.application.skill;

import io.agentscope.core.skill.AgentSkill;
import java.util.Map;

public record SkillView(String skillCode, String description, String category,
                        boolean defaultEnabled, String dependsOnProvider, boolean enabled) {
    public static SkillView from(AgentSkill skill, boolean enabled) {
        Map<String, Object> m = skill.getMetadata();
        return new SkillView(
                skill.getName(),
                skill.getDescription(),
                str(m, "category"),
                bool(m, "default_enabled"),
                str(m, "depends_on_provider"),
                enabled);
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    private static boolean bool(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Boolean b && b;
    }
}
```

`SkillApplicationService.java`：
```java
package com.portfolio.invest.application.skill;

import com.portfolio.invest.domain.skill.SkillConfigRepository;
import com.portfolio.invest.domain.skill.SkillUserConfig;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Skill 目录与用户配置用例：目录来自 classpath，用户选择落 skill_user_config。 */
@Service
public class SkillApplicationService {
    private final SkillConfigRepository repository;
    private final ClasspathSkillRepository builtInSkills;

    public SkillApplicationService(SkillConfigRepository repository, ClasspathSkillRepository builtInSkills) {
        this.repository = repository;
        this.builtInSkills = builtInSkills;
    }

    @Transactional(readOnly = true)
    public List<SkillView> catalog(Long userId) {
        Map<String, Boolean> choices = choices(userId);
        return builtInSkills.getAllSkills().stream()
                .map(s -> SkillView.from(s, effective(s, choices)))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<String> enabledSkillCodes(Long userId) {
        Map<String, Boolean> choices = choices(userId);
        List<String> result = new ArrayList<>();
        for (AgentSkill s : builtInSkills.getAllSkills()) {
            if (effective(s, choices)) result.add(s.getName());
        }
        return result;
    }

    @Transactional
    public List<SkillView> save(Long userId, List<String> enabledCodes) {
        Set<String> enabled = enabledCodes == null ? Set.of() : new HashSet<>(enabledCodes);
        Instant now = Instant.now();
        for (AgentSkill s : builtInSkills.getAllSkills()) {
            String code = s.getName();
            boolean want = enabled.contains(code);
            SkillUserConfig existing = repository.findByUserIdAndSkillCode(userId, code).orElse(null);
            repository.save(existing == null
                    ? SkillUserConfig.create(userId, code, want, now)
                    : existing.update(want, now));
        }
        return catalog(userId);
    }

    private Map<String, Boolean> choices(Long userId) {
        return repository.findByUserId(userId).stream()
                .collect(Collectors.toMap(SkillUserConfig::skillCode, SkillUserConfig::enabled));
    }

    private boolean effective(AgentSkill s, Map<String, Boolean> choices) {
        Boolean choice = choices.get(s.getName());
        return choice != null ? choice : defaultEnabled(s);
    }

    private static boolean defaultEnabled(AgentSkill s) {
        Object v = s.getMetadata().get("default_enabled");
        return v instanceof Boolean b && b;
    }
}
```

Run: `cd backend && ./gradlew test --tests "com.portfolio.invest.application.skill.SkillApplicationServiceTest" --console=plain`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/portfolio/invest/application/skill backend/src/test/java/com/portfolio/invest/application/skill/SkillApplicationServiceTest.java
git commit -m "feat(skill): Skill 目录/配置用例服务与单例仓库 Bean"
```

---

### Task 3: REST 接口（web 层）

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/web/dto/SaveSkillConfigRequest.java`
- Create: `backend/src/main/java/com/portfolio/invest/web/SkillConfigController.java`
- Test: `backend/src/test/java/com/portfolio/invest/web/SkillConfigControllerTest.java`

**Interfaces:**
- Consumes: Task 2 的 `SkillApplicationService` / `SkillView`。
- Produces: `GET /api/skills` → `List<SkillView>`；`PUT /api/skills/config`（body `SaveSkillConfigRequest(List<String> enabled)`）→ `List<SkillView>`。

- [ ] **Step 1: 写失败测试（TDD 红）**

`SkillConfigControllerTest.java`：
```java
package com.portfolio.invest.web;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.invest.application.skill.SkillApplicationService;
import com.portfolio.invest.application.skill.SkillView;
import com.portfolio.invest.domain.user.User;
import com.portfolio.invest.domain.user.UserRole;
import com.portfolio.invest.domain.user.UserStatus;
import com.portfolio.invest.infrastructure.security.AuthenticatedUser;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SkillConfigController.class)
class SkillConfigControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private SkillApplicationService service;

    private Authentication auth() {
        var user = User.reconstitute(1L, "u", "p", UserRole.USER, UserStatus.APPROVED, true,
                Instant.now(), Instant.now());
        var principal = new AuthenticatedUser(user);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    @DisplayName("GET 目录返回合并后的目录并透传 userId")
    @Test
    void givenGet_whenCatalog_thenReturnsCatalog() throws Exception {
        when(service.catalog(1L)).thenReturn(List.of(
                new SkillView("tushare_data", "desc", "data_source", false, "tushare", true)));

        mvc.perform(get("/api/skills").with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].skillCode").value("tushare_data"))
                .andExpect(jsonPath("$[0].enabled").value(true))
                .andExpect(jsonPath("$[0].dependsOnProvider").value("tushare"));
        verify(service).catalog(1L);
    }

    @DisplayName("PUT 保存启停集合")
    @Test
    void givenPut_whenSave_thenPassesEnabledCodes() throws Exception {
        when(service.save(eq(1L), anyList())).thenReturn(List.of());

        mvc.perform(put("/api/skills/config").with(authentication(auth())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":[\"tushare_data\"]}"))
                .andExpect(status().isOk());
        verify(service).save(eq(1L), eq(List.of("tushare_data")));
    }
}
```

Run: `cd backend && ./gradlew test --tests "com.portfolio.invest.web.SkillConfigControllerTest" --console=plain`
Expected: FAIL（`SkillConfigController` 未定义）

- [ ] **Step 2: 实现（TDD 绿）**

`SaveSkillConfigRequest.java`：
```java
package com.portfolio.invest.web.dto;

import java.util.List;

public record SaveSkillConfigRequest(List<String> enabled) {}
```

`SkillConfigController.java`：
```java
package com.portfolio.invest.web;

import com.portfolio.invest.application.skill.SkillApplicationService;
import com.portfolio.invest.application.skill.SkillView;
import com.portfolio.invest.infrastructure.security.AuthenticatedUser;
import com.portfolio.invest.web.dto.SaveSkillConfigRequest;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Skill 接入层：目录只读，个人配置以当前登录用户为归属。 */
@RestController
@RequestMapping("/api/skills")
public class SkillConfigController {

    private final SkillApplicationService service;

    public SkillConfigController(SkillApplicationService service) {
        this.service = service;
    }

    private static Long currentUserId(Authentication auth) {
        return ((AuthenticatedUser) auth.getPrincipal()).user().id();
    }

    @GetMapping
    public List<SkillView> catalog(Authentication auth) {
        return service.catalog(currentUserId(auth));
    }

    @PutMapping("/config")
    public List<SkillView> save(Authentication auth, @RequestBody SaveSkillConfigRequest body) {
        return service.save(currentUserId(auth), body.enabled());
    }
}
```

Run: `cd backend && ./gradlew test --tests "com.portfolio.invest.web.SkillConfigControllerTest" --console=plain`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/portfolio/invest/web/dto/SaveSkillConfigRequest.java backend/src/main/java/com/portfolio/invest/web/SkillConfigController.java backend/src/test/java/com/portfolio/invest/web/SkillConfigControllerTest.java
git commit -m "feat(skill): Skill 目录/配置 REST 接口"
```

---

### Task 4: Agent 装配（`SkillFilter`）+ 提示词规约

**Files:**
- Modify: `backend/src/main/java/com/portfolio/invest/agent/HarnessAgentFactory.java`
- Modify: `backend/src/main/java/com/portfolio/invest/agent/InvestSystemPrompt.java`
- Test: `backend/src/test/java/com/portfolio/invest/agent/HarnessAgentFactoryTest.java`

**Interfaces:**
- Consumes: Task 2 的 `SkillApplicationService` + `ClasspathSkillRepository` bean。
- Produces:
  - `HarnessAgentFactory.build(userId)` 增 `.skillRepository(...).skillFilter(...).disableDefaultWorkspaceSkills().disableDynamicSkills()`
  - package-private static `SkillFilter skillFilter(List<String> enabled)`（供单测）
  - `InvestSystemPrompt.TEXT` 增「Skill 数据源技能」规约

- [ ] **Step 1: 写失败测试（TDD 红，测 `skillFilter` 辅助方法）**

`HarnessAgentFactoryTest.java`：
```java
package com.portfolio.invest.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HarnessAgentFactoryTest {

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
}
```

Run: `cd backend && ./gradlew test --tests "com.portfolio.invest.agent.HarnessAgentFactoryTest" --console=plain`
Expected: FAIL（`skillFilter` 方法未定义）

- [ ] **Step 2: 改 `HarnessAgentFactory`（TDD 绿）**

新增 import：
```java
import com.portfolio.invest.application.skill.SkillApplicationService;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import java.util.List;
```

构造函数与 `build`：
```java
public class HarnessAgentFactory {
    private final UserToolkitFactory toolkitFactory;
    private final SkillApplicationService skillApplicationService;
    private final ClasspathSkillRepository builtInSkillRepository;
    private final Model model;
    private final InvestProperties.Mcp.Harness config;

    public HarnessAgentFactory(UserToolkitFactory toolkitFactory,
                               SkillApplicationService skillApplicationService,
                               ClasspathSkillRepository builtInSkillRepository,
                               Model model,
                               InvestProperties props) {
        this.toolkitFactory = toolkitFactory;
        this.skillApplicationService = skillApplicationService;
        this.builtInSkillRepository = builtInSkillRepository;
        this.model = model;
        this.config = props.getMcp().getHarness();
    }

    public HarnessAgent build(Long userId) {
        List<String> enabled = skillApplicationService.enabledSkillCodes(userId);
        return HarnessAgent.builder()
                .name("invest")
                .sysPrompt(InvestSystemPrompt.TEXT)
                .model(model)
                .toolkit(toolkitFactory.build(userId))
                .skillRepository(builtInSkillRepository)
                .skillFilter(skillFilter(enabled))
                .disableDefaultWorkspaceSkills()
                .disableDynamicSkills()
                .workspace(Paths.get(config.getWorkspace()))
                .stateStore(new JsonFileAgentStateStore(Paths.get(config.getStateRoot())))
                .compaction(CompactionConfig.builder()
                        .triggerMessages(config.getCompaction().getTriggerMessages())
                        .keepMessages(config.getCompaction().getKeepMessages())
                        .flushBeforeCompact(config.getCompaction().isFlushBeforeCompact())
                        .build())
                .memory(MemoryConfig.builder()
                        .flushTrigger(MemoryConfig.FlushTrigger.throttled(config.getMemory().getFlushMinGap()))
                        .build())
                .build();
    }

    static SkillFilter skillFilter(List<String> enabled) {
        return SkillFilter.only(enabled.toArray(new String[0]));
    }
}
```

> 注：`SkillFilter` 位于 `io.agentscope.core.skill`（非 harness）；`ClasspathSkillRepository` 位于 `io.agentscope.core.skill.repository`。两者均已在 `agentscope-core:2.0.1` 依赖内。

Run: `cd backend && ./gradlew test --tests "com.portfolio.invest.agent.HarnessAgentFactoryTest" --console=plain`
Expected: PASS

- [ ] **Step 3: 改 `InvestSystemPrompt`**

在 `InvestSystemPrompt.TEXT` 的 `## MCP 扩展数据源` 段之后、`## 免责声明` 之前插入：
```java
            ## Skill 数据源技能
            系统内置两个数据源类 skill，仅当用户已启用时可用（启用状态以「Skill 设置」为准）：
            1. 仅当内置工具与已启用 MCP 的专项工具无法覆盖时，才加载数据源类 skill（tushare_data / wind_finance）
            2. 数据源分工：公告/年报/季报/招股书/财经新闻、宏观 EDB 指标、跨标的聚合/排名 → wind_finance；
               期货/港美股/债券/财务三表明细/指数成分 → tushare_data；
               两者重叠的 A股行情/财务/指数/基金 → 内置工具优先，MCP 兜底时默认 wind_finance（机构级口径）
            3. 启用某数据源 skill 时，该数据源的具体取数流程以该 skill 为准
            4. 引用 Tushare/Wind 数据时标注来源
```

Run: `cd backend && ./gradlew compileJava --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/portfolio/invest/agent/HarnessAgentFactory.java backend/src/main/java/com/portfolio/invest/agent/InvestSystemPrompt.java backend/src/test/java/com/portfolio/invest/agent/HarnessAgentFactoryTest.java
git commit -m "feat(skill): HarnessAgent 装配 SkillFilter + 提示词规约"
```

---

## P2 完成验证

```bash
cd backend && ./gradlew test --console=plain
```
确认：`SkillApplicationServiceTest`、`SkillConfigControllerTest`、`HarnessAgentFactoryTest`、`BuiltInSkillCatalogTest` 全绿；ArchUnit `PackageConventionsTest` 绿（`application.skill`/`domain.skill` 落白名单，`agent` 依赖 `application.skill` 方向合法）。
