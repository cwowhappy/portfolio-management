# P1 mcp 域模型与数据迁移 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立 `domain/mcp` 纯领域层（`McpProvider`/`McpEndpoint`/`McpUserConfig`/`AuthType`/`TokenCipher` 端口/异常/仓库端口）、Flyway V10 三表迁移、JPA 实体与仓库实现、AES-GCM 加密实现，并通过 Testcontainers 集成测试验证持久化。

**Architecture:** 沿用 journal/asset-allocation 的 DDD 模式——domain 纯 POJO，infrastructure JPA 回填。加密走 `domain.mcp.TokenCipher` 端口（application/agent 禁直连 infrastructure）。目录两级：`mcp_provider`（含系统 Token）+ `mcp_endpoint`（domain 维度）。

**Tech Stack:** Java 21 · Spring Boot 4 · JPA (Hibernate) · PostgreSQL 16 (Flyway) · JUnit 5 + AssertJ · Testcontainers

**Spec:** `01-requirement/需求规格说明.md`（FR-1/4/7/8）、`02-design/设计规格说明.md`（§二/§三/§五）

## Global Constraints

- `domain/mcp` 纯 POJO，禁 Spring/JPA 注解；加密走 `TokenCipher` 端口。
- schema 由 Flyway 管理，迁移 `V10__mcp.sql`，表名 snake_case 单数；`app_user` 不改。
- `mcp_provider.auth_secret_enc` 一期 seed 明文、二期 admin UI 加密（`AesGcmTokenCipher`）。
- `disabled_tools` 为 JSONB 数组；时间戳 `Instant`。
- 领域不可变对象静态工厂 `create`/`reconstitute`；变更 `update` 返回新实例（config_version 自增）。
- 覆盖率 ≥80%；测试分层 `test`/`integrationTest`。

---

### Task 1: AuthType 枚举 + 领域异常 + 加密端口

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/domain/mcp/AuthType.java`
- Create: `backend/src/main/java/com/portfolio/invest/domain/mcp/McpException.java`
- Create: `backend/src/main/java/com/portfolio/invest/domain/mcp/McpErrorCode.java`
- Create: `backend/src/main/java/com/portfolio/invest/domain/mcp/TokenCipher.java`

**Interfaces:**
- Produces: `AuthType`（`NONE`/`BEARER`/`HEADER`）；`McpException(String code, String message)` + `.code()`；`McpErrorCode`（`PROVIDER_NOT_FOUND`/`CONFIG_NOT_FOUND`/`INVALID_INPUT`/`CONNECTION_FAILED`）；`TokenCipher`（`encrypt`/`decrypt`）。

- [ ] **Step 1: 写失败测试**

`backend/src/test/java/com/portfolio/invest/domain/mcp/AuthTypeTest.java`：

```java
package com.portfolio.invest.domain.mcp;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AuthTypeTest {
    @Test
    void 三种鉴权类型() {
        assertThat(AuthType.values())
                .containsExactly(AuthType.NONE, AuthType.BEARER, AuthType.HEADER);
    }
}
```

- [ ] **Step 2: 跑测试确认失败** — `cd backend && ./gradlew test --tests "com.portfolio.invest.domain.mcp.AuthTypeTest" --console=plain`（编译失败）

- [ ] **Step 3: 实现**

```java
package com.portfolio.invest.domain.mcp;

public enum AuthType { NONE, BEARER, HEADER }
```

```java
package com.portfolio.invest.domain.mcp;

public class McpException extends RuntimeException {
    private final String code;
    public McpException(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
}
```

```java
package com.portfolio.invest.domain.mcp;

public final class McpErrorCode {
    private McpErrorCode() {}
    public static final String PROVIDER_NOT_FOUND = "PROVIDER_NOT_FOUND";
    public static final String CONFIG_NOT_FOUND = "CONFIG_NOT_FOUND";
    public static final String INVALID_INPUT = "INVALID_INPUT";
    public static final String CONNECTION_FAILED = "CONNECTION_FAILED";
}
```

```java
package com.portfolio.invest.domain.mcp;

/** Token 加解密端口：application/agent 经此端口加解密，infrastructure.security 提供 AES-GCM 实现。 */
public interface TokenCipher {
    String encrypt(String plaintext);
    String decrypt(String ciphertext);
}
```

- [ ] **Step 4: 跑测试确认通过**
- [ ] **Step 5: Commit** `feat(mcp): 鉴权类型/领域异常/加密端口`

---

### Task 2: McpProvider / McpEndpoint 实体

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/domain/mcp/McpProvider.java`
- Create: `backend/src/main/java/com/portfolio/invest/domain/mcp/McpEndpoint.java`
- Test: `backend/src/test/java/com/portfolio/invest/domain/mcp/McpProviderEndpointTest.java`

**Interfaces:**
- Produces: `McpProvider`（`reconstitute(id, code, name, authType, authHeader, authSecretEnc, enabled, remark, createdAt)` + 访问器）；`McpEndpoint`（`reconstitute(id, providerId, domain, name, url, enabled, createdAt)` + 访问器）。

- [ ] **Step 1: 写失败测试**

```java
package com.portfolio.invest.domain.mcp;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

class McpProviderEndpointTest {
    private static final Instant NOW = Instant.parse("2026-09-06T08:00:00Z");

    private static McpProvider wind() {
        return McpProvider.reconstitute(3L, "wind", "Wind AIFin", AuthType.BEARER, null, "ak-secret", true, null, NOW);
    }

    @Test
    void provider重建保留字段() {
        var p = wind();
        assertThat(p.code()).isEqualTo("wind");
        assertThat(p.authType()).isEqualTo(AuthType.BEARER);
        assertThat(p.authSecretEnc()).isEqualTo("ak-secret");
    }

    @Test
    void endpoint重建保留字段() {
        var e = McpEndpoint.reconstitute(5L, 3L, "stock", "Wind 股票",
                "https://mcp.wind.com.cn/vserver_stock_data/mcp/", true, NOW);
        assertThat(e.providerId()).isEqualTo(3L);
        assertThat(e.domain()).isEqualTo("stock");
        assertThat(e.url()).isEqualTo("https://mcp.wind.com.cn/vserver_stock_data/mcp/");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

- [ ] **Step 3: 实现**

```java
package com.portfolio.invest.domain.mcp;

import java.time.Instant;

/** 数据源 provider：鉴权/Token 维度，系统全局 Token（一期 seed 明文）。 */
public final class McpProvider {
    private final Long id;
    private final String code;
    private final String name;
    private final AuthType authType;
    private final String authHeader;
    private final String authSecretEnc;
    private final boolean enabled;
    private final String remark;
    private final Instant createdAt;

    private McpProvider(Long id, String code, String name, AuthType authType, String authHeader,
                        String authSecretEnc, boolean enabled, String remark, Instant createdAt) {
        this.id = id; this.code = code; this.name = name; this.authType = authType;
        this.authHeader = authHeader; this.authSecretEnc = authSecretEnc;
        this.enabled = enabled; this.remark = remark; this.createdAt = createdAt;
    }

    public static McpProvider reconstitute(Long id, String code, String name, AuthType authType,
                                           String authHeader, String authSecretEnc, boolean enabled,
                                           String remark, Instant createdAt) {
        return new McpProvider(id, code, name, authType, authHeader, authSecretEnc, enabled, remark, createdAt);
    }

    public Long id() { return id; }
    public String code() { return code; }
    public String name() { return name; }
    public AuthType authType() { return authType; }
    public String authHeader() { return authHeader; }
    public String authSecretEnc() { return authSecretEnc; }
    public boolean enabled() { return enabled; }
    public String remark() { return remark; }
    public Instant createdAt() { return createdAt; }
}
```

```java
package com.portfolio.invest.domain.mcp;

import java.time.Instant;

/** provider 端点：一个 provider 下 1..N 个 domain（Wind 6 域；单端点 provider domain=NULL）。 */
public final class McpEndpoint {
    private final Long id;
    private final Long providerId;
    private final String domain;
    private final String name;
    private final String url;
    private final boolean enabled;
    private final Instant createdAt;

    private McpEndpoint(Long id, Long providerId, String domain, String name, String url,
                        boolean enabled, Instant createdAt) {
        this.id = id; this.providerId = providerId; this.domain = domain; this.name = name;
        this.url = url; this.enabled = enabled; this.createdAt = createdAt;
    }

    public static McpEndpoint reconstitute(Long id, Long providerId, String domain, String name,
                                           String url, boolean enabled, Instant createdAt) {
        return new McpEndpoint(id, providerId, domain, name, url, enabled, createdAt);
    }

    public Long id() { return id; }
    public Long providerId() { return providerId; }
    public String domain() { return domain; }
    public String name() { return name; }
    public String url() { return url; }
    public boolean enabled() { return enabled; }
    public Instant createdAt() { return createdAt; }
}
```

- [ ] **Step 4: 跑测试确认通过**
- [ ] **Step 5: Commit** `feat(mcp): provider/endpoint 实体`

---

### Task 3: McpUserConfig 聚合

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/domain/mcp/McpUserConfig.java`
- Test: `backend/src/test/java/com/portfolio/invest/domain/mcp/McpUserConfigTest.java`

**Interfaces:**
- Produces: `McpUserConfig`（`create(userId, providerId, disabledTools, now)` / `reconstitute(...)` / `update(enabled, disabledTools, now)` 返回新实例 config_version 自增）。**无 Token 字段。**

- [ ] **Step 1: 写失败测试**

```java
package com.portfolio.invest.domain.mcp;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpUserConfigTest {
    private static final Instant NOW = Instant.parse("2026-09-06T08:00:00Z");

    @Test
    void 创建默认启用版本为1() {
        var c = McpUserConfig.create(1L, 3L, List.of("get_stock_quote"), NOW);
        assertThat(c.enabled()).isTrue();
        assertThat(c.configVersion()).isEqualTo(1);
        assertThat(c.disabledTools()).containsExactly("get_stock_quote");
    }

    @Test
    void 禁用工具清单为null抛INVALID_INPUT() {
        assertThatThrownBy(() -> McpUserConfig.create(1L, 3L, null, NOW))
                .isInstanceOfSatisfying(McpException.class,
                        e -> assertThat(e.code()).isEqualTo(McpErrorCode.INVALID_INPUT));
    }

    @Test
    void 更新返回新实例且版本自增() {
        var c = McpUserConfig.create(1L, 3L, List.of(), NOW);
        var u = c.update(false, List.of("get_stock_kline"), NOW.plusSeconds(10));
        assertThat(u.enabled()).isFalse();
        assertThat(u.configVersion()).isEqualTo(2);
        assertThat(c.configVersion()).isEqualTo(1); // 原实例不变
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

- [ ] **Step 3: 实现**（结构同 Task 3 旧版，去掉 `authSecretEnc` 字段与方法，`create(userId, providerId, disabledTools, now)`、`update(enabled, disabledTools, now)`）

- [ ] **Step 4: 跑测试确认通过**
- [ ] **Step 5: Commit** `feat(mcp): 用户配置聚合（无 Token）`

---

### Task 4: 仓库端口 + Flyway V10 三表迁移

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/domain/mcp/McpConfigRepository.java`
- Create: `backend/src/main/resources/db/migration/V10__mcp.sql`

**Interfaces:**
- Produces: 仓库端口（`findEnabledProviders`/`findProviderById`/`findEndpointsByProviderId`/`findByUserId`/`findByUserIdAndProviderId`/`save`/`deleteByUserIdAndProviderId`）；V10 迁移（三表 + seed，SQL 见设计 §二）。

- [ ] **Step 1: 写仓库端口**

```java
package com.portfolio.invest.domain.mcp;

import java.util.List;
import java.util.Optional;

public interface McpConfigRepository {
    List<McpProvider> findEnabledProviders();
    Optional<McpProvider> findProviderById(Long providerId);
    List<McpEndpoint> findEnabledEndpointsByProviderId(Long providerId);

    List<McpUserConfig> findByUserId(Long userId);
    Optional<McpUserConfig> findByUserIdAndProviderId(Long userId, Long providerId);
    McpUserConfig save(McpUserConfig config);
    void deleteByUserIdAndProviderId(Long userId, Long providerId);
}
```

- [ ] **Step 2: 写迁移**（SQL 与 seed 照抄设计 §二；`<妙想 em_api_key>`/`<Tushare token>`/`<Wind ak token>` 用真实值替换——一期明文 seed）

- [ ] **Step 3: Commit** `feat(mcp): 仓库端口与 Flyway V10 三表迁移`

---

### Task 5: JPA 实体与仓库实现

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/infrastructure/persistence/McpProviderJpaEntity.java`
- Create: `backend/src/main/java/com/portfolio/invest/infrastructure/persistence/McpEndpointJpaEntity.java`
- Create: `backend/src/main/java/com/portfolio/invest/infrastructure/persistence/McpUserConfigJpaEntity.java`
- Create: `backend/src/main/java/com/portfolio/invest/infrastructure/persistence/McpProviderJpaRepository.java`
- Create: `backend/src/main/java/com/portfolio/invest/infrastructure/persistence/McpEndpointJpaRepository.java`
- Create: `backend/src/main/java/com/portfolio/invest/infrastructure/persistence/McpUserConfigJpaRepository.java`
- Create: `backend/src/main/java/com/portfolio/invest/infrastructure/persistence/McpConfigRepositoryImpl.java`

**Interfaces:**
- Consumes: 端口与实体（Task 2/3/4）。
- Produces: `McpConfigRepositoryImpl implements McpConfigRepository`（`@Repository`）。

- [ ] **Step 1-3: 写 JPA 实体 + Spring Data 仓库 + 实现**（结构同旧版 Task 5，拆成 3 个实体；`McpUserConfigJpaEntity.disabledTools` 用 `@JdbcTypeCode(SqlTypes.JSON)` + `columnDefinition="jsonb"` 存 `List<String>`；`McpProviderJpaEntity.authSecretEnc` 为 `@Column(columnDefinition="text")`）

- [ ] **Step 4: Commit** `feat(mcp): JPA 实体与仓库实现（三表）`

---

### Task 6: 仓库集成测试（Testcontainers 真实 PG）

**Files:**
- Test: `backend/src/integrationTest/java/com/portfolio/invest/infrastructure/persistence/McpConfigRepositoryImplTest.java`

**Interfaces:**
- Consumes: `McpConfigRepositoryImpl`（Task 5）、V10（Task 4）。参考 `PostgresTestSupport`。

- [ ] **Step 1: 写集成测试**（断言：seed 3 provider + 8 endpoint；`findEnabledEndpointsByProviderId(3)` 返回 wind 6 域；用户配置保存/更新 configVersion 自增/删除；用户隔离）

- [ ] **Step 2: 跑测试确认通过** — `cd backend && ./gradlew integrationTest --tests "com.portfolio.invest.infrastructure.persistence.McpConfigRepositoryImplTest" --console=plain`

- [ ] **Step 3: Commit** `test(mcp): 仓库集成测试（三表 + seed）`

---

### Task 7: AesGcmTokenCipher（AES-GCM 实现，二期 admin UI 用）

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/infrastructure/security/AesGcmTokenCipher.java`
- Modify: `backend/src/main/resources/application.yml`（`invest.mcp.secret-key: ${MCP_SECRET_KEY:}`）
- Modify: `.env.example`（`MCP_SECRET_KEY=`）
- Test: `backend/src/test/java/com/portfolio/invest/infrastructure/security/AesGcmTokenCipherTest.java`

**Interfaces:**
- Consumes: `TokenCipher`（Task 1）。
- Produces: `AesGcmTokenCipher implements TokenCipher`（AES-256-GCM，主密钥 `MCP_SECRET_KEY` base64 32 字节；启动不阻断、缺失 warning、加解密时报错）。

- [ ] **Step 1-6: 写测试 → 实现 → 加配置 → 提交**（代码同旧版 Task 7 完整版，无改动）

---

## P1 完成验证

```bash
cd backend
./gradlew test              # 领域单测 + 架构测试（domain.mcp 零 Spring 注解自动校验）
./gradlew integrationTest   # 含 McpConfigRepositoryImplTest
```

确认：`domain/mcp` 零 Spring 注解、零跨域依赖；V10 三表在真实 PG 生效、seed 3 provider + 8 endpoint；`AesGcmTokenCipher` 满足「启动不阻断 + 缺失警告 + 用时报错」。
