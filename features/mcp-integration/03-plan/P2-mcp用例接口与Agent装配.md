# P2 mcp 用例接口与 Agent 装配 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 MCP 配置用例（目录查询/用户配置 CRUD/连接测试/工具清单）、REST 接口与全局异常映射，并完成 Agent 运行时按用户装配（内置 7 工具 + 用户启用的 MCP 工具）。

**Architecture:** application 层编排（`McpConfigApplicationService` + `McpServerTester`），web 层 REST（`McpConfigController`），agent 层装配（`UserToolkitFactory`/`McpClientPool`/`InvestAguiRuntimeContextResolver`/自定义 `AgentResolver`）。加解密经 `domain.mcp.TokenCipher` 端口；MCP SDK（AgentScope）为外部依赖，application/agent 可直接引用（ArchUnit 只约束项目内部包）。**Task 4 的 AgentResolver 覆盖点、McpTool 过滤 API、并发结论依赖 P0 验证记录 §1/§2/§3。**

**Tech Stack:** Java 21 · Spring Boot 4 · Spring MVC · AgentScope 2.0.1（McpClientBuilder/Toolkit/AguiAgentRegistry/RuntimeContext）

**Spec:** `features/mcp-integration/01-requirement/需求规格说明.md`（FR-2/3/5/6/8）、`features/mcp-integration/02-design/设计规格说明.md`（§四/§五/§六/§八）、`03-plan/验证记录.md`（P0 结论）

## Global Constraints

- 后端 DDD 洋葱分层；application/agent 禁直连 `infrastructure.*`（加解密走 `TokenCipher` 端口）。
- 所有权隔离沿用会话隔离惯例：个人配置非本人 404（`findByUserIdAndCatalogId`）。
- 出参永不包含 Token 明文/密文；目录 URL 出参直接用 `url()`（无 token 内嵌，无需脱敏）。
- 空 Token 语义：PUT 空 token = 保留旧值；清空用 DELETE 配置。
- 单次工具调用超时 30s（FR-6 降级）；连接超时 10s。
- 覆盖率门槛 ≥80%（JaCoCo）。

---

### Task 1: McpServerTester 端口 + AgentScope 实现

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/application/mcp/McpToolDescriptor.java`
- Create: `backend/src/main/java/com/portfolio/invest/application/mcp/McpServerTester.java`
- Create: `backend/src/main/java/com/portfolio/invest/application/mcp/AgentScopeMcpServerTester.java`

**Interfaces:**
- Produces: `McpToolDescriptor(String name, String description)`；`McpServerTester`（`List<McpToolDescriptor> testConnection(McpServerCatalog catalog, String token)`，失败抛 `McpException(CONNECTION_FAILED)`）；`AgentScopeMcpServerTester` 实现。

- [ ] **Step 1: 写描述符与端口**

```java
package com.portfolio.invest.application.mcp;

/** MCP 工具描述（连接时 live 发现）。 */
public record McpToolDescriptor(String name, String description) {}
```

```java
package com.portfolio.invest.application.mcp;

import com.portfolio.invest.domain.mcp.McpServerCatalog;
import java.util.List;

/** MCP 连接测试端口：真实握手并返回工具清单。 */
public interface McpServerTester {
    List<McpToolDescriptor> testConnection(McpServerCatalog catalog, String token);
}
```

- [ ] **Step 2: 实现（工具清单 API 依 P0 §2 回填）**

```java
package com.portfolio.invest.application.mcp;

import com.portfolio.invest.domain.mcp.AuthType;
import com.portfolio.invest.domain.mcp.McpErrorCode;
import com.portfolio.invest.domain.mcp.McpException;
import com.portfolio.invest.domain.mcp.McpServerCatalog;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class AgentScopeMcpServerTester implements McpServerTester {

    @Override
    public List<McpToolDescriptor> testConnection(McpServerCatalog catalog, String token) {
        try {
            McpClientBuilder builder = McpClientBuilder.create(catalog.code())
                    .streamableHttpTransport(catalog.url())
                    .timeout(Duration.ofSeconds(10));
            switch (catalog.authType()) {
                case HEADER -> builder.header(catalog.authHeader(), token);
                case BEARER -> builder.header("Authorization", "Bearer " + token);
                default -> { }
            }
            McpClientWrapper client = builder.buildSync();
            // P0 §2：client 取工具清单的准确 API（方法名/返回类型按验证记录回填）
            List<McpToolDescriptor> tools = listTools(client);
            return tools;
        } catch (Exception e) {
            throw new McpException(McpErrorCode.CONNECTION_FAILED, "连接失败：" + e.getMessage());
        }
    }

    private List<McpToolDescriptor> listTools(McpClientWrapper client) {
        // 依据 P0 §2：逐个读取工具 name/description
        throw new UnsupportedOperationException("P0 §2 回填");
    }
}
```

> P0 §2 定稿后，`listTools` 换成真实 API；`McpTool` 的 name/description 访问器同样以验证记录为准。

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/portfolio/invest/application/mcp/McpToolDescriptor.java \
        backend/src/main/java/com/portfolio/invest/application/mcp/McpServerTester.java \
        backend/src/main/java/com/portfolio/invest/application/mcp/AgentScopeMcpServerTester.java
git commit -m "feat(mcp): MCP 连接测试端口与 AgentScope 实现"
```

---

### Task 2: 视图 + McpConfigApplicationService（TDD）

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/application/mcp/McpCatalogView.java`
- Create: `backend/src/main/java/com/portfolio/invest/application/mcp/McpConfigView.java`
- Create: `backend/src/main/java/com/portfolio/invest/application/mcp/ToolView.java`
- Create: `backend/src/main/java/com/portfolio/invest/application/mcp/TestResult.java`
- Create: `backend/src/main/java/com/portfolio/invest/application/mcp/McpConfigApplicationService.java`
- Test: `backend/src/test/java/com/portfolio/invest/application/mcp/McpConfigApplicationServiceTest.java`

**Interfaces:**
- Consumes: `McpConfigRepository`/`TokenCipher`（P1）、`McpServerTester`（Task 1）。
- Produces: `McpConfigApplicationService`（`catalog()`/`myConfigs(userId)`/`save(...)`/`delete(...)`/`tools(...)`/`test(...)`）。

- [ ] **Step 1: 写视图**

```java
package com.portfolio.invest.application.mcp;

import com.portfolio.invest.domain.mcp.AuthType;
import com.portfolio.invest.domain.mcp.McpServerCatalog;

public record McpCatalogView(Long id, String code, String name, String url,
                             AuthType authType, String authHeader, String remark) {
    public static McpCatalogView from(McpServerCatalog c) {
        return new McpCatalogView(c.id(), c.code(), c.name(), c.url(),
                c.authType(), c.authHeader(), c.remark());
    }
}
```

```java
package com.portfolio.invest.application.mcp;

import com.portfolio.invest.domain.mcp.McpUserConfig;
import java.util.List;

public record McpConfigView(Long catalogId, boolean enabled, List<String> disabledTools, int configVersion) {
    public static McpConfigView from(McpUserConfig c) {
        return new McpConfigView(c.catalogId(), c.enabled(), c.disabledTools(), c.configVersion());
    }
}
```

```java
package com.portfolio.invest.application.mcp;

public record ToolView(String name, String description, boolean enabled) {}
```

```java
package com.portfolio.invest.application.mcp;

import java.util.List;

public record TestResult(boolean success, List<McpToolDescriptor> tools, long latencyMs, String errorMessage) {
    public static TestResult ok(List<McpToolDescriptor> tools, long latencyMs) {
        return new TestResult(true, tools, latencyMs, null);
    }
    public static TestResult fail(String errorMessage) {
        return new TestResult(false, List.of(), 0, errorMessage);
    }
}
```

- [ ] **Step 2: 写失败测试（mock 端口，聚焦用例逻辑）**

```java
package com.portfolio.invest.application.mcp;

import com.portfolio.invest.domain.mcp.AuthType;
import com.portfolio.invest.domain.mcp.McpErrorCode;
import com.portfolio.invest.domain.mcp.McpException;
import com.portfolio.invest.domain.mcp.McpServerCatalog;
import com.portfolio.invest.domain.mcp.McpConfigRepository;
import com.portfolio.invest.domain.mcp.McpUserConfig;
import com.portfolio.invest.domain.mcp.TokenCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpConfigApplicationServiceTest {

    private McpConfigRepository repository;
    private TokenCipher cipher;
    private McpServerTester tester;
    private McpConfigApplicationService service;

    private static final Instant NOW = Instant.parse("2026-09-06T08:00:00Z");

    private static McpServerCatalog tushare() {
        return McpServerCatalog.reconstitute(2L, "tushare", "Tushare",
                "https://api.tushare.pro/mcp/", AuthType.BEARER, null, true, null, NOW);
    }

    @BeforeEach
    void setUp() {
        repository = mock(McpConfigRepository.class);
        cipher = mock(TokenCipher.class);
        tester = mock(McpServerTester.class);
        service = new McpConfigApplicationService(repository, cipher, tester);
    }

    @Test
    void 需要token的数据源新建时缺token抛TOKEN_REQUIRED() {
        when(repository.findCatalogById(2L)).thenReturn(Optional.of(tushare()));
        when(repository.findByUserIdAndCatalogId(1L, 2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.save(1L, 2L, "", null, null))
                .isInstanceOfSatisfying(McpException.class,
                        e -> assertThat(e.code()).isEqualTo(McpErrorCode.TOKEN_REQUIRED));
    }

    @Test
    void 保存时加密token并落库() {
        when(repository.findCatalogById(2L)).thenReturn(Optional.of(tushare()));
        when(repository.findByUserIdAndCatalogId(1L, 2L)).thenReturn(Optional.empty());
        when(cipher.encrypt("plain")).thenReturn("cipher");
        when(repository.save(any(McpUserConfig.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var view = service.save(1L, 2L, "plain", true, List.of("get_kline"));

        assertThat(view.configVersion()).isEqualTo(1);
        assertThat(view.disabledTools()).containsExactly("get_kline");
    }

    @Test
    void 空token且已存在配置时保留旧密文() {
        McpUserConfig existing = McpUserConfig.create(1L, 2L, "old-cipher", List.of(), NOW);
        when(repository.findCatalogById(2L)).thenReturn(Optional.of(tushare()));
        when(repository.findByUserIdAndCatalogId(1L, 2L)).thenReturn(Optional.of(existing));
        when(repository.save(any(McpUserConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        var view = service.save(1L, 2L, "", false, null);

        assertThat(view.configVersion()).isEqualTo(2);
        assertThat(view.enabled()).isFalse();
    }

    @Test
    void 目录不存在抛CATALOG_NOT_FOUND() {
        when(repository.findCatalogById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.save(1L, 99L, "x", null, null))
                .isInstanceOfSatisfying(McpException.class,
                        e -> assertThat(e.code()).isEqualTo(McpErrorCode.CATALOG_NOT_FOUND));
    }

    @Test
    void 删除不存在的配置抛CONFIG_NOT_FOUND() {
        when(repository.findByUserIdAndCatalogId(1L, 2L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.delete(1L, 2L))
                .isInstanceOfSatisfying(McpException.class,
                        e -> assertThat(e.code()).isEqualTo(McpErrorCode.CONFIG_NOT_FOUND));
    }
}
```

- [ ] **Step 3: 跑测试确认失败**

Run: `cd backend && ./gradlew test --tests "com.portfolio.invest.application.mcp.McpConfigApplicationServiceTest" --console=plain`
Expected: 编译失败。

- [ ] **Step 4: 实现服务**

```java
package com.portfolio.invest.application.mcp;

import com.portfolio.invest.domain.mcp.AuthType;
import com.portfolio.invest.domain.mcp.McpConfigRepository;
import com.portfolio.invest.domain.mcp.McpErrorCode;
import com.portfolio.invest.domain.mcp.McpException;
import com.portfolio.invest.domain.mcp.McpServerCatalog;
import com.portfolio.invest.domain.mcp.McpUserConfig;
import com.portfolio.invest.domain.mcp.TokenCipher;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class McpConfigApplicationService {

    private final McpConfigRepository repository;
    private final TokenCipher cipher;
    private final McpServerTester tester;

    public McpConfigApplicationService(McpConfigRepository repository, TokenCipher cipher, McpServerTester tester) {
        this.repository = repository;
        this.cipher = cipher;
        this.tester = tester;
    }

    @Transactional(readOnly = true)
    public List<McpCatalogView> catalog() {
        return repository.findEnabledCatalogs().stream().map(McpCatalogView::from).toList();
    }

    @Transactional(readOnly = true)
    public List<McpConfigView> myConfigs(Long userId) {
        return repository.findByUserId(userId).stream().map(McpConfigView::from).toList();
    }

    @Transactional
    public McpConfigView save(Long userId, Long catalogId, String token, Boolean enabled, List<String> disabledTools) {
        McpServerCatalog catalog = requireEnabledCatalog(catalogId);
        boolean needsToken = catalog.authType() != AuthType.NONE;
        McpUserConfig existing = repository.findByUserIdAndCatalogId(userId, catalogId).orElse(null);

        if (needsToken && (token == null || token.isBlank()) && existing == null) {
            throw new McpException(McpErrorCode.TOKEN_REQUIRED, "该数据源需要配置 Token");
        }

        String newSecret = null;
        if (token != null && !token.isBlank()) {
            newSecret = cipher.encrypt(token);
        } else if (existing != null) {
            newSecret = existing.authSecretEnc();
        }

        boolean nextEnabled = enabled != null ? enabled : (existing == null || existing.enabled());
        List<String> nextDisabled = disabledTools != null ? disabledTools
                : (existing == null ? List.of() : existing.disabledTools());

        McpUserConfig toSave = existing == null
                ? McpUserConfig.create(userId, catalogId, newSecret, nextDisabled, Instant.now())
                : existing.update(nextEnabled, newSecret, nextDisabled, Instant.now());

        return McpConfigView.from(repository.save(toSave));
    }

    @Transactional
    public void delete(Long userId, Long catalogId) {
        if (repository.findByUserIdAndCatalogId(userId, catalogId).isEmpty()) {
            throw new McpException(McpErrorCode.CONFIG_NOT_FOUND, "配置不存在");
        }
        repository.deleteByUserIdAndCatalogId(userId, catalogId);
    }

    @Transactional(readOnly = true)
    public List<ToolView> tools(Long userId, Long catalogId) {
        McpUserConfig config = repository.findByUserIdAndCatalogId(userId, catalogId)
                .orElseThrow(() -> new McpException(McpErrorCode.CONFIG_NOT_FOUND, "配置不存在"));
        McpServerCatalog catalog = requireEnabledCatalog(catalogId);
        String token = decryptFor(catalog, config);
        List<McpToolDescriptor> tools = tester.testConnection(catalog, token);
        return tools.stream()
                .map(t -> new ToolView(t.name(), t.description(), !config.disabledTools().contains(t.name())))
                .toList();
    }

    @Transactional(readOnly = true)
    public TestResult test(Long catalogId, String token) {
        McpServerCatalog catalog = requireEnabledCatalog(catalogId);
        try {
            long start = System.currentTimeMillis();
            List<McpToolDescriptor> tools = tester.testConnection(catalog, token);
            return TestResult.ok(tools, System.currentTimeMillis() - start);
        } catch (McpException e) {
            return TestResult.fail(e.getMessage());
        }
    }

    private McpServerCatalog requireEnabledCatalog(Long catalogId) {
        McpServerCatalog catalog = repository.findCatalogById(catalogId)
                .orElseThrow(() -> new McpException(McpErrorCode.CATALOG_NOT_FOUND, "数据源不存在"));
        if (!catalog.enabled()) {
            throw new McpException(McpErrorCode.CATALOG_NOT_FOUND, "数据源不可用");
        }
        return catalog;
    }

    private String decryptFor(McpServerCatalog catalog, McpUserConfig config) {
        if (catalog.authType() == AuthType.NONE) {
            return null;
        }
        if (config.authSecretEnc() == null) {
            throw new McpException(McpErrorCode.TOKEN_REQUIRED, "该数据源需要配置 Token");
        }
        return cipher.decrypt(config.authSecretEnc());
    }
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: 同 Step 3。Expected: PASS。

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/portfolio/invest/application/mcp/ \
        backend/src/test/java/com/portfolio/invest/application/mcp/McpConfigApplicationServiceTest.java
git commit -m "feat(mcp): 配置用例服务（CRUD/测试/工具清单 + 加密编排）"
```

---

### Task 3: Web DTO + McpConfigController + 异常映射

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/web/dto/SaveConfigRequest.java`
- Create: `backend/src/main/java/com/portfolio/invest/web/dto/TestConnectionRequest.java`
- Create: `backend/src/main/java/com/portfolio/invest/web/McpConfigController.java`
- Modify: `backend/src/main/java/com/portfolio/invest/web/GlobalExceptionHandler.java`

**Interfaces:**
- Consumes: `McpConfigApplicationService`（Task 2）、`AuthenticatedUser`（infrastructure.security）。
- Produces: `McpConfigController`（`/api/mcp/**`）。

- [ ] **Step 1: 写 DTO**

```java
package com.portfolio.invest.web.dto;

import java.util.List;

public record SaveConfigRequest(String token, Boolean enabled, List<String> disabledTools) {}
```

```java
package com.portfolio.invest.web.dto;

public record TestConnectionRequest(Long catalogId, String token) {}
```

- [ ] **Step 2: 写控制器**

```java
package com.portfolio.invest.web;

import com.portfolio.invest.application.mcp.McpCatalogView;
import com.portfolio.invest.application.mcp.McpConfigApplicationService;
import com.portfolio.invest.application.mcp.McpConfigView;
import com.portfolio.invest.application.mcp.TestResult;
import com.portfolio.invest.application.mcp.ToolView;
import com.portfolio.invest.infrastructure.security.AuthenticatedUser;
import com.portfolio.invest.web.dto.SaveConfigRequest;
import com.portfolio.invest.web.dto.TestConnectionRequest;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** MCP 数据源接入层：目录只读，个人配置以当前登录用户为归属（非本人 404）。 */
@RestController
@RequestMapping("/api/mcp")
public class McpConfigController {

    private final McpConfigApplicationService service;

    public McpConfigController(McpConfigApplicationService service) {
        this.service = service;
    }

    private static Long currentUserId(Authentication auth) {
        return ((AuthenticatedUser) auth.getPrincipal()).user().id();
    }

    @GetMapping("/catalog")
    public List<McpCatalogView> catalog() {
        return service.catalog();
    }

    @GetMapping("/configs")
    public List<McpConfigView> configs(Authentication auth) {
        return service.myConfigs(currentUserId(auth));
    }

    @PutMapping("/configs/{catalogId}")
    public McpConfigView save(Authentication auth, @PathVariable Long catalogId,
                              @RequestBody SaveConfigRequest body) {
        return service.save(currentUserId(auth), catalogId, body.token(), body.enabled(), body.disabledTools());
    }

    @DeleteMapping("/configs/{catalogId}")
    public ResponseEntity<Void> delete(Authentication auth, @PathVariable Long catalogId) {
        service.delete(currentUserId(auth), catalogId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/configs/test")
    public TestResult test(@RequestBody TestConnectionRequest body) {
        return service.test(body.catalogId(), body.token());
    }

    @GetMapping("/configs/{catalogId}/tools")
    public List<ToolView> tools(Authentication auth, @PathVariable Long catalogId) {
        return service.tools(currentUserId(auth), catalogId);
    }
}
```

- [ ] **Step 3: 加异常映射**

在 `GlobalExceptionHandler` 增加（import `McpException`/`McpErrorCode`）：

```java
@ExceptionHandler(com.portfolio.invest.domain.mcp.McpException.class)
public ResponseEntity<ApiError> mcp(com.portfolio.invest.domain.mcp.McpException e) {
    HttpStatus status = switch (e.code()) {
        case com.portfolio.invest.domain.mcp.McpErrorCode.CATALOG_NOT_FOUND,
             com.portfolio.invest.domain.mcp.McpErrorCode.CONFIG_NOT_FOUND -> HttpStatus.NOT_FOUND;
        case com.portfolio.invest.domain.mcp.McpErrorCode.TOKEN_REQUIRED,
             com.portfolio.invest.domain.mcp.McpErrorCode.INVALID_INPUT -> HttpStatus.BAD_REQUEST;
        case com.portfolio.invest.domain.mcp.McpErrorCode.CONNECTION_FAILED -> HttpStatus.BAD_GATEWAY;
        default -> HttpStatus.BAD_REQUEST;
    };
    return ResponseEntity.status(status).body(new ApiError(e.code(), e.getMessage()));
}
```

- [ ] **Step 4: 跑测试**

Run: `cd backend && ./gradlew test --console=plain`（全量，含既有控制器切片测试 + ArchUnit）
Expected: PASS（`web` 访 `application.mcp` 已在 ArchUnit 白名单；`web` 访 `infrastructure.security` 已允许）。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/portfolio/invest/web/dto/SaveConfigRequest.java \
        backend/src/main/java/com/portfolio/invest/web/dto/TestConnectionRequest.java \
        backend/src/main/java/com/portfolio/invest/web/McpConfigController.java \
        backend/src/main/java/com/portfolio/invest/web/GlobalExceptionHandler.java
git commit -m "feat(mcp): 配置 REST 接口与异常映射"
```

---

### Task 4: Agent 运行时按用户装配（依赖 P0 §1/§2/§3）

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/agent/InvestAguiRuntimeContextResolver.java`
- Create: `backend/src/main/java/com/portfolio/invest/agent/McpClientPool.java`
- Create: `backend/src/main/java/com/portfolio/invest/agent/UserToolkitFactory.java`
- Modify: `backend/src/main/java/com/portfolio/invest/agent/AgentConfig.java`
- Modify: `backend/src/main/java/com/portfolio/invest/agent/InvestSystemPrompt.java`

**Interfaces:**
- Consumes: `McpConfigRepository`/`TokenCipher`（domain.mcp，agent 可访）、`InvestTools`（agent）、`McpClientPool`（本任务）。
- Produces: 自定义 resolver/AgentResolver；`UserToolkitFactory.build(userId)` 返回 `Toolkit`。

- [ ] **Step 1: 写 RuntimeContextResolver（签名已验）**

```java
package com.portfolio.invest.agent;

import com.portfolio.invest.infrastructure.security.AuthenticatedUser;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.spring.boot.agui.common.AguiRuntimeContextRequest;
import io.agentscope.spring.boot.agui.common.AguiRuntimeContextResolver;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** 从 Spring Security 取当前用户，写入 AG-UI RuntimeContext.userId。 */
@Component
public class InvestAguiRuntimeContextResolver implements AguiRuntimeContextResolver {

    @Override
    public RuntimeContext resolve(AguiRuntimeContextRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String userId = (auth != null && auth.getPrincipal() instanceof AuthenticatedUser u)
                ? u.user().id().toString() : null;
        return RuntimeContext.builder().userId(userId).build();
    }
}
```

- [ ] **Step 2: 写 McpClientPool（按 (catalogId, userId, configVersion) 池化）**

```java
package com.portfolio.invest.agent;

import com.portfolio.invest.domain.mcp.AuthType;
import com.portfolio.invest.domain.mcp.McpServerCatalog;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** MCP client 池：key=(catalogId, userId, configVersion)，miss 时 buildSync，失败由调用方跳过。 */
@Component
public class McpClientPool {

    private final Map<String, McpClientWrapper> clients = new ConcurrentHashMap<>();

    public McpClientWrapper acquire(McpServerCatalog catalog, Long userId, int configVersion, String token) {
        String key = catalog.id() + ":" + userId + ":" + configVersion;
        return clients.computeIfAbsent(key, k -> build(catalog, token));
    }

    public void evict(Long catalogId, Long userId, int configVersion) {
        String key = catalogId + ":" + userId + ":" + configVersion;
        McpClientWrapper c = clients.remove(key);
        if (c != null) { /* P0 §2：确认 client 的关闭 API（close 或 Mono） */ }
    }

    private McpClientWrapper build(McpServerCatalog catalog, String token) {
        McpClientBuilder builder = McpClientBuilder.create(catalog.code())
                .streamableHttpTransport(catalog.url()).timeout(Duration.ofSeconds(10));
        if (catalog.authType() == AuthType.HEADER) {
            builder.header(catalog.authHeader(), token);
        } else if (catalog.authType() == AuthType.BEARER) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder.buildSync();
    }
}
```

- [ ] **Step 3: 写 UserToolkitFactory（装配 + 去重 + 过滤）**

```java
package com.portfolio.invest.agent;

import com.portfolio.invest.domain.mcp.AuthType;
import com.portfolio.invest.domain.mcp.McpConfigRepository;
import com.portfolio.invest.domain.mcp.McpServerCatalog;
import com.portfolio.invest.domain.mcp.McpUserConfig;
import com.portfolio.invest.domain.mcp.TokenCipher;
import io.agentscope.core.tool.Toolkit;
import java.util.HashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 按用户装配工具集：内置 7 工具 + 本人启用且已配 Token 的目录数据源工具（去重 + disabled_tools 过滤）。 */
@Component
public class UserToolkitFactory {

    private static final Logger log = LoggerFactory.getLogger(UserToolkitFactory.class);

    private final InvestTools investTools;
    private final McpConfigRepository repository;
    private final TokenCipher cipher;
    private final McpClientPool clientPool;

    public UserToolkitFactory(InvestTools investTools, McpConfigRepository repository,
                              TokenCipher cipher, McpClientPool clientPool) {
        this.investTools = investTools;
        this.repository = repository;
        this.cipher = cipher;
        this.clientPool = clientPool;
    }

    public Toolkit build(Long userId) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(investTools);
        Set<String> names = new HashSet<>(); // 已注册工具名（内置 + 前置服务器）

        for (McpServerCatalog catalog : repository.findEnabledCatalogs()) {
            McpUserConfig config = repository.findByUserIdAndCatalogId(userId, catalog.id()).orElse(null);
            if (config == null || !config.enabled()) {
                continue;
            }
            if (catalog.authType() != AuthType.NONE && config.authSecretEnc() == null) {
                continue;
            }
            try {
                String token = catalog.authType() == AuthType.NONE ? null : cipher.decrypt(config.authSecretEnc());
                McpClientWrapper client = clientPool.acquire(catalog, userId, config.configVersion(), token);
                // 去重（内置 > 目录顺序）+ disabled_tools 过滤 → 汇总为 disable 清单，走官方过滤 API
                List<String> toDisable = new ArrayList<>();
                for (McpSchema.Tool t : client.listTools().block()) {
                    if (config.disabledTools().contains(t.name()) || names.contains(t.name())) {
                        toDisable.add(t.name());
                    } else {
                        names.add(t.name());
                    }
                }
                toolkit.registration().mcpClient(client).disableTools(toDisable).apply();
            } catch (Exception e) {
                log.warn("MCP 数据源 {} 装配失败，跳过：{}", catalog.code(), e.getMessage());
            }
        }
        return toolkit;
    }
}
```

> P0 §2/§3 定稿后：`registerFiltered` 换成真实 `McpTool` 逐个注册；`names` 去重「内置 > 目录顺序」；并发结论决定缓存键 userId vs (userId, threadId)。

- [ ] **Step 4: 改 AgentConfig 为 registerFactory（依 P0 §1）**

删除单例 `investAgent` bean，新增 `AguiAgentRegistryCustomizer` bean 在 registry 上 `registerFactory("invest", ...)`；工厂内从 `SecurityContextHolder` 取 userId 现建 Agent（P0 §1 结论：`DefaultAgentResolver` 非 bean 不可覆盖，但 `registerFactory` 足够，无需自定义 `InvestAgentResolver`）：

```java
@Bean
public AguiAgentRegistryCustomizer investAgentRegistration(UserToolkitFactory toolkitFactory, Model model) {
    return registry -> registry.registerFactory("invest", () ->
            ReActAgent.builder()
                    .name("invest")
                    .sysPrompt(InvestSystemPrompt.TEXT)
                    .model(model)
                    .toolkit(toolkitFactory.build(currentUserId()))
                    .maxIters(10)
                    .build());
}

private static Long currentUserId() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    return (auth != null && auth.getPrincipal() instanceof AuthenticatedUser u) ? u.user().id() : null;
}
```

> 每请求现建 Agent（无缓存）；`McpClientPool` 缓存昂贵的 MCP 握手，故首 token 延迟主要来自首次 client 构建（10s 超时内，失败跳过）。

- [ ] **Step 5: 追加 InvestSystemPrompt**

在 `InvestSystemPrompt.TEXT` 的「工具使用规范」之后追加 §八的静态规约（4 数据源 + 分工规约 + tool poisoning 缓解，文案见设计规格 §八）。

- [ ] **Step 6: 跑测试 + Commit**

```bash
cd backend && ./gradlew test --console=plain
# 冒烟：真实妙想/Tushare 手动验证（脚本留档）——确认装配与首 token 不阻塞
git add backend/src/main/java/com/portfolio/invest/agent/
git commit -m "feat(mcp): Agent 按用户装配（Resolver/AgentResolver/UserToolkitFactory/池化）"
```

---

## P2 完成验证

```bash
cd backend
./gradlew test              # 单测 + 切片 + ArchUnit（agent 访 domain.mcp/application.mcp 在白名单内）
./gradlew integrationTest   # 仓库集成测试仍绿
```

确认：目录/配置/测试/工具清单接口可用；Token 明文永不进日志与出参；同名工具按「内置 > 目录顺序」去重；连接失败不影响内置工具。
