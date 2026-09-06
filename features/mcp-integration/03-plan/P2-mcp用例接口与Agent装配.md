# P2 mcp 用例接口与 Agent 装配 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 MCP 配置用例（provider 目录/用户配置 CRUD/连接测试/工具清单）、REST 接口与全局异常映射，并完成按用户装配（内置 7 + 用户启用的 provider 工具）与 `HarnessAgent` 集成。

**Architecture:** application 层编排，web 层 REST，agent 层装配。加解密走 `TokenCipher` 端口（二期用）。装配机制为 P0 已验证的 Option B：resolver 从 session 读 userId → ThreadLocal → factory。`HarnessAgent` 取代 `ReActAgent`（AG-UI adapter 已特殊处理，drop-in）。

**Tech Stack:** Java 21 · Spring Boot 4 · AgentScope 2.0.1 + `agentscope-harness:2.0.1`

**Spec:** `01-requirement/需求规格说明.md`（FR-2/3/5/6）、`02-design/设计规格说明.md`（§四/§六）、`03-plan/验证记录.md`

## Global Constraints

- application/agent 禁直连 `infrastructure.*`（加解密走 `TokenCipher` 端口）。
- 所有权隔离：个人配置非本人 404。
- Token 出参永不回显；一期 provider Token 明文 seed、装配直接用，二期 admin UI 加密后再解密。
- 单工具调用超时 30s、连接超时 10s（FR-6 降级）。
- `server-side-memory: true`——前端只发最新一条消息。
- 覆盖率 ≥80%。

---

### Task 1: McpServerTester 端口 + AgentScope 实现

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/application/mcp/McpToolDescriptor.java`
- Create: `backend/src/main/java/com/portfolio/invest/application/mcp/McpServerTester.java`
- Create: `backend/src/main/java/com/portfolio/invest/application/mcp/AgentScopeMcpServerTester.java`

**Interfaces:**
- Produces: `McpToolDescriptor(String name, String description)`；`McpServerTester`（`List<McpToolDescriptor> testConnection(McpProvider provider, String token)`，失败抛 `McpException(CONNECTION_FAILED)`）；`AgentScopeMcpServerTester` 实现（`McpClientBuilder.create(code).streamableHttpTransport(url).header(...).timeout(10s).buildSync()`，工具清单经 P0 已验证的 `client.listTools().block()` 读 `McpSchema.Tool.name()/description()`）。

- [ ] **Step 1-3: 写描述符 + 端口 + 实现 → Commit**（工具清单 API 已由 P0 §2 确认，无需占位；HEADER 用 `builder.header(provider.authHeader(), token)`、BEARER 用 `Authorization: Bearer token`）

---

### Task 2: 视图 + McpConfigApplicationService（TDD）

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/application/mcp/McpProviderView.java`（含 `List<String> domains`）
- Create: `backend/src/main/java/com/portfolio/invest/application/mcp/McpConfigView.java`
- Create: `backend/src/main/java/com/portfolio/invest/application/mcp/ToolView.java`
- Create: `backend/src/main/java/com/portfolio/invest/application/mcp/TestResult.java`
- Create: `backend/src/main/java/com/portfolio/invest/application/mcp/McpConfigApplicationService.java`
- Test: `backend/src/test/java/com/portfolio/invest/application/mcp/McpConfigApplicationServiceTest.java`

**Interfaces:**
- Consumes: `McpConfigRepository`（P1）、`McpServerTester`（Task 1）。
- Produces: `McpConfigApplicationService`（`providers()`/`myConfigs(userId)`/`save(userId, providerId, enabled, disabledTools)`/`delete(...)`/`tools(...)`/`test(providerId)`）。

- [ ] **Step 1: 写视图**（`McpProviderView` 含 `domains` 列表、`McpConfigView(Long providerId, boolean enabled, List<String> disabledTools, int configVersion)`、`ToolView`、`TestResult`）

- [ ] **Step 2: 写失败测试（mock 端口）**

关键用例：
- `save` 新建：configVersion=1、disabledTools 正确落库
- `save` 更新：configVersion 自增、原实例不变
- `save` provider 不存在 → `PROVIDER_NOT_FOUND`
- `delete` 不存在 → `CONFIG_NOT_FOUND`
- `test(providerId)` 用 provider 的系统 Token 调 `tester.testConnection(provider, provider.authSecretEnc())`
- `tools` 标记 enabled = `!disabledTools.contains(name)`

- [ ] **Step 3: 跑测试确认失败**

- [ ] **Step 4: 实现服务**

```java
@Service
public class McpConfigApplicationService {
    private final McpConfigRepository repository;
    private final McpServerTester tester;

    public McpConfigApplicationService(McpConfigRepository repository, McpServerTester tester) {
        this.repository = repository;
        this.tester = tester;
    }

    @Transactional(readOnly = true)
    public List<McpProviderView> providers() {
        return repository.findEnabledProviders().stream().map(p -> {
            List<String> domains = repository.findEnabledEndpointsByProviderId(p.id()).stream()
                    .map(McpEndpoint::domain).filter(Objects::nonNull).toList();
            return McpProviderView.from(p, domains);
        }).toList();
    }

    @Transactional(readOnly = true)
    public List<McpConfigView> myConfigs(Long userId) {
        return repository.findByUserId(userId).stream().map(McpConfigView::from).toList();
    }

    @Transactional
    public McpConfigView save(Long userId, Long providerId, Boolean enabled, List<String> disabledTools) {
        McpProvider provider = requireProvider(providerId);
        McpUserConfig existing = repository.findByUserIdAndProviderId(userId, providerId).orElse(null);
        boolean nextEnabled = enabled != null ? enabled : (existing == null || existing.enabled());
        List<String> nextDisabled = disabledTools != null ? disabledTools
                : (existing == null ? List.of() : existing.disabledTools());
        McpUserConfig toSave = existing == null
                ? McpUserConfig.create(userId, providerId, nextDisabled, Instant.now())
                : existing.update(nextEnabled, nextDisabled, Instant.now());
        return McpConfigView.from(repository.save(toSave));
    }

    @Transactional
    public void delete(Long userId, Long providerId) {
        if (repository.findByUserIdAndProviderId(userId, providerId).isEmpty()) {
            throw new McpException(McpErrorCode.CONFIG_NOT_FOUND, "配置不存在");
        }
        repository.deleteByUserIdAndProviderId(userId, providerId);
    }

    @Transactional(readOnly = true)
    public List<ToolView> tools(Long userId, Long providerId) {
        McpUserConfig config = repository.findByUserIdAndProviderId(userId, providerId)
                .orElseThrow(() -> new McpException(McpErrorCode.CONFIG_NOT_FOUND, "配置不存在"));
        McpProvider provider = requireProvider(providerId);
        List<McpToolDescriptor> tools = tester.testConnection(provider, provider.authSecretEnc());
        return tools.stream()
                .map(t -> new ToolView(t.name(), t.description(), !config.disabledTools().contains(t.name())))
                .toList();
    }

    @Transactional(readOnly = true)
    public TestResult test(Long providerId) {
        McpProvider provider = requireProvider(providerId);
        try {
            long start = System.currentTimeMillis();
            List<McpToolDescriptor> tools = tester.testConnection(provider, provider.authSecretEnc());
            return TestResult.ok(tools, System.currentTimeMillis() - start);
        } catch (McpException e) {
            return TestResult.fail(e.getMessage());
        }
    }

    private McpProvider requireProvider(Long providerId) {
        McpProvider p = repository.findProviderById(providerId)
                .orElseThrow(() -> new McpException(McpErrorCode.PROVIDER_NOT_FOUND, "数据源不存在"));
        if (!p.enabled()) throw new McpException(McpErrorCode.PROVIDER_NOT_FOUND, "数据源不可用");
        return p;
    }
}
```

> 一期 `provider.authSecretEnc()` 为明文直接用；二期 admin UI 加密后此处经 `TokenCipher.decrypt()` 解密（加 `TokenCipher` 依赖 + 注入）。

- [ ] **Step 5: 跑测试确认通过**
- [ ] **Step 6: Commit** `feat(mcp): 配置用例服务（provider 级，无 Token 配置）`

---

### Task 3: Web DTO + McpConfigController + 异常映射

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/web/dto/SaveConfigRequest.java`（`enabled`, `disabledTools`，**无 token**）
- Create: `backend/src/main/java/com/portfolio/invest/web/dto/TestConnectionRequest.java`（`providerId`）
- Create: `backend/src/main/java/com/portfolio/invest/web/McpConfigController.java`
- Modify: `backend/src/main/java/com/portfolio/invest/web/GlobalExceptionHandler.java`

**Interfaces:**
- Consumes: `McpConfigApplicationService`（Task 2）、`AuthenticatedUser`。

- [ ] **Step 1-2: 写 DTO + 控制器**

```java
@RestController
@RequestMapping("/api/mcp")
public class McpConfigController {
    private final McpConfigApplicationService service;
    public McpConfigController(McpConfigApplicationService service) { this.service = service; }
    private static Long currentUserId(Authentication auth) {
        return ((AuthenticatedUser) auth.getPrincipal()).user().id();
    }

    @GetMapping("/providers") public List<McpProviderView> providers() { return service.providers(); }
    @GetMapping("/configs") public List<McpConfigView> configs(Authentication auth) { return service.myConfigs(currentUserId(auth)); }
    @PutMapping("/configs/{providerId}") public McpConfigView save(Authentication auth, @PathVariable Long providerId, @RequestBody SaveConfigRequest body) {
        return service.save(currentUserId(auth), providerId, body.enabled(), body.disabledTools());
    }
    @DeleteMapping("/configs/{providerId}") public ResponseEntity<Void> delete(Authentication auth, @PathVariable Long providerId) {
        service.delete(currentUserId(auth), providerId); return ResponseEntity.noContent().build();
    }
    @PostMapping("/providers/test") public TestResult test(@RequestBody TestConnectionRequest body) { return service.test(body.providerId()); }
    @GetMapping("/configs/{providerId}/tools") public List<ToolView> tools(Authentication auth, @PathVariable Long providerId) {
        return service.tools(currentUserId(auth), providerId);
    }
}
```

- [ ] **Step 3: 加异常映射**（`McpException` → `PROVIDER_NOT_FOUND/CONFIG_NOT_FOUND` 404、`INVALID_INPUT` 400、`CONNECTION_FAILED` 502）

- [ ] **Step 4: 跑测试 + Commit** `feat(mcp): 配置 REST 接口与异常映射`

---

### Task 4: Agent 运行时装配 + HarnessAgent 集成

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/agent/CurrentUserHolder.java`
- Create: `backend/src/main/java/com/portfolio/invest/agent/InvestAguiRuntimeContextResolver.java`
- Create: `backend/src/main/java/com/portfolio/invest/agent/McpClientPool.java`
- Create: `backend/src/main/java/com/portfolio/invest/agent/UserToolkitFactory.java`
- Create: `backend/src/main/java/com/portfolio/invest/agent/HarnessAgentFactory.java`
- Modify: `backend/src/main/java/com/portfolio/invest/agent/AgentConfig.java`
- Modify: `backend/src/main/java/com/portfolio/invest/agent/InvestSystemPrompt.java`

**Interfaces:**
- Consumes: `McpConfigRepository`/`TokenCipher`（P1）、`InvestTools`、`Model`。
- Produces: `HarnessAgentFactory.build(userId)` 返回 `HarnessAgent`。

- [ ] **Step 1: 写 CurrentUserHolder + resolver（P0 二次 spike 已验证）**

```java
// CurrentUserHolder.java
final class CurrentUserHolder {
    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();
    private CurrentUserHolder() {}
    static void set(Long userId) { CURRENT.set(userId); }
    static Long get() { return CURRENT.get(); }
    static void remove() { CURRENT.remove(); }
}
```

```java
@Component
public class InvestAguiRuntimeContextResolver implements AguiRuntimeContextResolver {
    @Override
    public RuntimeContext resolve(AguiRuntimeContextRequest request) {
        Long userId = readUserId(request);
        CurrentUserHolder.set(userId);
        return RuntimeContext.builder().userId(userId == null ? null : userId.toString()).build();
    }
    private Long readUserId(AguiRuntimeContextRequest request) {
        HttpServletRequest nativeReq = request.getNativeRequest(HttpServletRequest.class);
        HttpSession session = nativeReq == null ? null : nativeReq.getSession(false);
        Object ctx = session == null ? null
                : session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        if (ctx instanceof SecurityContext sc && sc.getAuthentication() != null
                && sc.getAuthentication().getPrincipal() instanceof AuthenticatedUser u) {
            return u.user().id();
        }
        return null;
    }
}
```

- [ ] **Step 2: 写 McpClientPool + UserToolkitFactory（provider→endpoint）**

```java
@Component
public class UserToolkitFactory {
    private final InvestTools investTools;
    private final McpConfigRepository repository;
    private final McpClientPool clientPool;

    public Toolkit build(Long userId) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(investTools);
        Set<String> names = new HashSet<>();
        for (McpProvider provider : repository.findEnabledProviders()) {
            McpUserConfig config = repository.findByUserIdAndProviderId(userId, provider.id()).orElse(null);
            if (config == null || !config.enabled()) continue;
            if (provider.authType() != AuthType.NONE && (provider.authSecretEnc() == null || provider.authSecretEnc().isBlank())) continue;
            String token = provider.authSecretEnc(); // 一期明文；二期 cipher.decrypt(...)
            for (McpEndpoint endpoint : repository.findEnabledEndpointsByProviderId(provider.id())) {
                try {
                    McpClientWrapper client = clientPool.acquire(endpoint, provider, token); // 键=endpointId，token 全局不 per-user
                    List<String> toDisable = new ArrayList<>();
                    for (McpSchema.Tool t : client.listTools().block()) {
                        if (config.disabledTools().contains(t.name()) || names.contains(t.name())) toDisable.add(t.name());
                        else names.add(t.name());
                    }
                    toolkit.registration().mcpClient(client).disableTools(toDisable).apply();
                } catch (Exception e) {
                    log.warn("MCP 端点 {} 装配失败，跳过：{}", endpoint.name(), e.getMessage());
                }
            }
        }
        return toolkit;
    }
}
```

- [ ] **Step 3: 写 HarnessAgentFactory + 改 AgentConfig（registerFactory）**

先加配置（沿用 `InvestProperties` 嵌套类模式）：

`InvestProperties` 新增 `Mcp` 嵌套类（`getMcp()` 访问器 + 内部 `Harness`）：

```java
public static class Mcp {
    private Duration connectTimeout = Duration.ofSeconds(10);
    private Duration toolTimeout = Duration.ofSeconds(30);
    private int poolMaxSize = 20;
    private Harness harness = new Harness();
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getToolTimeout() { return toolTimeout; }
    public void setToolTimeout(Duration toolTimeout) { this.toolTimeout = toolTimeout; }
    public int getPoolMaxSize() { return poolMaxSize; }
    public void setPoolMaxSize(int poolMaxSize) { this.poolMaxSize = poolMaxSize; }
    public Harness getHarness() { return harness; }
    public void setHarness(Harness harness) { this.harness = harness; }

    public static class Harness {
        private String workspace = ".agentscope/workspace";
        private String stateRoot = ".agentscope/state";
        private Compaction compaction = new Compaction();
        private Memory memory = new Memory();
        public String getWorkspace() { return workspace; }
        public void setWorkspace(String workspace) { this.workspace = workspace; }
        public String getStateRoot() { return stateRoot; }
        public void setStateRoot(String stateRoot) { this.stateRoot = stateRoot; }
        public Compaction getCompaction() { return compaction; }
        public void setCompaction(Compaction compaction) { this.compaction = compaction; }
        public Memory getMemory() { return memory; }
        public void setMemory(Memory memory) { this.memory = memory; }

        public static class Compaction {
            private int triggerMessages = 30;
            private int keepMessages = 10;
            private boolean flushBeforeCompact = true;
            public int getTriggerMessages() { return triggerMessages; }
            public void setTriggerMessages(int triggerMessages) { this.triggerMessages = triggerMessages; }
            public int getKeepMessages() { return keepMessages; }
            public void setKeepMessages(int keepMessages) { this.keepMessages = keepMessages; }
            public boolean isFlushBeforeCompact() { return flushBeforeCompact; }
            public void setFlushBeforeCompact(boolean flushBeforeCompact) { this.flushBeforeCompact = flushBeforeCompact; }
        }

        public static class Memory {
            private Duration flushMinGap = Duration.ofMinutes(30);
            public Duration getFlushMinGap() { return flushMinGap; }
            public void setFlushMinGap(Duration flushMinGap) { this.flushMinGap = flushMinGap; }
        }
    }
}
```

`application.yml` 新增（`invest:` 段下）：

```yaml
  mcp:
    connect-timeout: 10s
    tool-timeout: 30s
    pool-max-size: 20
    harness:
      workspace: .agentscope/workspace
      state-root: .agentscope/state
      compaction:
        trigger-messages: 30
        keep-messages: 10
        flush-before-compact: true
      memory:
        flush-min-gap: 30m
```

> 运行参数消费点：`connectTimeout` → `McpServerTester`（Task 1）与 `McpClientPool`（Step 2）的 `McpClientBuilder.timeout(...)`（替换硬编码 10s，注入 `InvestProperties`）；`toolTimeout` → 单工具调用超时；`poolMaxSize` → `McpClientPool` 用 Caffeine `maximumSize(...)` 限池（替换无界 ConcurrentHashMap）。

`HarnessAgentFactory`（读配置，builder 签名已对照 `agentscope-harness:2.0.1` jar 实测）：

```java
package com.portfolio.invest.agent;

import com.portfolio.invest.config.InvestProperties;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import java.nio.file.Paths;
import org.springframework.stereotype.Component;

@Component
public class HarnessAgentFactory {
    private final UserToolkitFactory toolkitFactory;
    private final Model model;
    private final InvestProperties.Mcp.Harness config;

    public HarnessAgentFactory(UserToolkitFactory toolkitFactory, Model model, InvestProperties props) {
        this.toolkitFactory = toolkitFactory;
        this.model = model;
        this.config = props.getMcp().getHarness();
    }

    public HarnessAgent build(Long userId) {
        return HarnessAgent.builder()
                .name("invest")
                .sysPrompt(InvestSystemPrompt.TEXT)
                .model(model)
                .toolkit(toolkitFactory.build(userId))
                .workspace(Paths.get(config.getWorkspace()))   // 基路径，harness 按 RuntimeContext.userId 隔离
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
}
```

> 关键类型（已实测）：`HarnessAgent implements Agent, AutoCloseable`；builder 方法 `.workspace(Path|String)`、`.stateStore(AgentStateStore)`、`.compaction(CompactionConfig)`、`.memory(MemoryConfig)`、`.toolkit(Toolkit)`、`.model(Model|String)`、`.name/.sysPrompt/.maxIters`；`CompactionConfig.builder().triggerMessages/keepMessages/flushBeforeCompact/triggerTokens/keepTokens/...`；`MemoryConfig.builder().flushTrigger(FlushTrigger)/consolidationMaxTokens/consolidationMinGap/...`（`FlushTrigger` 是 `MemoryConfig` 内部 `public static final class`，静态工厂 `always()/never()/throttled(Duration)`）；`JsonFileAgentStateStore` 构造器 `()`（默认 `~/.agentscope/state`）或 `(Path rootDirectory)`。文件系统一期用默认本地实现，不显式 `.filesystem(...)`。

`AgentConfig`：删单例 `investAgent` bean，加 `AguiAgentRegistryCustomizer`：

```java
@Bean
public AguiAgentRegistryCustomizer investAgentRegistration(HarnessAgentFactory factory) {
    return registry -> registry.registerFactory("invest", () -> {
        Long userId = CurrentUserHolder.get();
        if (userId == null) throw new IllegalStateException("未认证用户无法访问 /agui");
        try { return factory.build(userId); }
        finally { CurrentUserHolder.remove(); }
    });
}
```

- [ ] **Step 4: 追加 InvestSystemPrompt（§八 静态规约）**

- [ ] **Step 5: 跑测试 + Commit**（`SecurityContextThreadLocalSpikeTest` 已证 Option B；落地后把 spike 测试替换为测真实 `HarnessAgentFactory` 的集成测试）`feat(mcp): 按用户装配 + HarnessAgent 集成`

---

## P2 完成验证

```bash
cd backend && ./gradlew test integrationTest   # 单测 + 切片 + ArchUnit（agent 访 domain.mcp 白名单内）
```

确认：provider 目录/配置/测试/工具清单接口可用；Token 无回显；同名工具按「内置 > provider > domain」去重；连接失败不影响内置工具；`HarnessAgent` 按 (userId, sessionId) 服务端持久化会话。
