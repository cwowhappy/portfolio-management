# P1 mcp 域模型与数据迁移 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立 `domain/mcp` 纯领域层（AuthType/McpServerCatalog/McpUserConfig/TokenCipher 端口/异常/仓库端口）、Flyway V10 双表迁移、JPA 实体与仓库实现、AES-GCM 加密实现，并通过 Testcontainers 集成测试验证持久化。

**Architecture:** 沿用 journal（M11）/ asset-allocation（M07）的模式——domain 纯 POJO（零 Spring/JPA 注解），infrastructure 用 JPA 实体 + 仓库实现回填。**加密走领域端口 `TokenCipher`**（ArchUnit：`application`/`agent` 禁访 `infrastructure`，故加解密只能通过 domain 端口，infrastructure 提供实现）。内置目录 `mcp_server_catalog` 只读（seed 来自迁移），用户配置 `mcp_user_config` 是聚合根。

**Tech Stack:** Java 21 · Spring Boot 4 · JPA (Hibernate) · PostgreSQL 16 (Flyway) · JUnit 5 + AssertJ · Testcontainers

**Spec:** `features/mcp-integration/01-requirement/需求规格说明.md`（FR-1/4/7/8）、`features/mcp-integration/02-design/设计规格说明.md`（§二/§三/§五）

## Global Constraints

- 后端 DDD 洋葱分层，`domain/mcp` 纯 POJO，禁 Spring/JPA 注解（ArchUnit `domainHasNoSpringAnnotations` 强制）。
- `domain/mcp` 零项目内依赖；加密/解密只能经 `domain.mcp.TokenCipher` 端口（application/agent 禁直连 infrastructure）。
- schema 由 Flyway 管理（`ddl-auto: none`），迁移 `V10__mcp.sql`，表名 snake_case 单数。
- `auth_secret_enc` 为密文 TEXT（NONE 时为 NULL）；`disabled_tools` 为 JSONB 数组；时间戳用 `Instant`。
- 领域不可变对象用静态工厂 `create` / `reconstitute` + 包级私有构造器，变更 `update` 返回新实例（config_version 自增）。
- 后端覆盖率门槛 ≥80%（JaCoCo）；测试分层：`test`（单元+切片）/ `integrationTest`（Testcontainers 真实 PG）。
- **iFinD / Wind 的端点 URL 与鉴权头名依赖 P0 验证记录 §4**，V10 seed 执行时据此回填。

---

### Task 1: AuthType 枚举 + 领域异常 + 加密端口

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/domain/mcp/AuthType.java`
- Create: `backend/src/main/java/com/portfolio/invest/domain/mcp/McpException.java`
- Create: `backend/src/main/java/com/portfolio/invest/domain/mcp/McpErrorCode.java`
- Create: `backend/src/main/java/com/portfolio/invest/domain/mcp/TokenCipher.java`

**Interfaces:**
- Produces: `AuthType`（`NONE`/`BEARER`/`HEADER`）；`McpException(String code, String message)` + `.code()`；`McpErrorCode` 常量；`TokenCipher`（`String encrypt(String)` / `String decrypt(String)`）。

- [ ] **Step 1: 写失败测试**

Create `backend/src/test/java/com/portfolio/invest/domain/mcp/AuthTypeTest.java`:

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

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend && ./gradlew test --tests "com.portfolio.invest.domain.mcp.AuthTypeTest" --console=plain`
Expected: 编译失败（类型不存在）。

- [ ] **Step 3: 实现**

`AuthType.java`:
```java
package com.portfolio.invest.domain.mcp;

/** MCP 服务器鉴权方式。 */
public enum AuthType {
    NONE, BEARER, HEADER
}
```

`McpException.java`:
```java
package com.portfolio.invest.domain.mcp;

public class McpException extends RuntimeException {
    private final String code;

    public McpException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() { return code; }
}
```

`McpErrorCode.java`:
```java
package com.portfolio.invest.domain.mcp;

public final class McpErrorCode {
    private McpErrorCode() {}

    public static final String CATALOG_NOT_FOUND = "CATALOG_NOT_FOUND";
    public static final String CONFIG_NOT_FOUND = "CONFIG_NOT_FOUND";
    public static final String INVALID_INPUT = "INVALID_INPUT";
    public static final String TOKEN_REQUIRED = "TOKEN_REQUIRED";
    public static final String CONNECTION_FAILED = "CONNECTION_FAILED";
}
```

`TokenCipher.java`（领域端口，infrastructure 实现）:
```java
package com.portfolio.invest.domain.mcp;

/** 用户 Token 加解密端口：application/agent 经此端口加解密，infrastructure.security 提供 AES-GCM 实现。 */
public interface TokenCipher {
    String encrypt(String plaintext);
    String decrypt(String ciphertext);
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: 同 Step 2。Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/portfolio/invest/domain/mcp/AuthType.java \
        backend/src/main/java/com/portfolio/invest/domain/mcp/McpException.java \
        backend/src/main/java/com/portfolio/invest/domain/mcp/McpErrorCode.java \
        backend/src/main/java/com/portfolio/invest/domain/mcp/TokenCipher.java \
        backend/src/test/java/com/portfolio/invest/domain/mcp/AuthTypeTest.java
git commit -m "feat(mcp): 鉴权类型/领域异常/加密端口"
```

---

### Task 2: McpServerCatalog 实体

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/domain/mcp/McpServerCatalog.java`
- Test: `backend/src/test/java/com/portfolio/invest/domain/mcp/McpServerCatalogTest.java`

**Interfaces:**
- Produces: `McpServerCatalog` 只读值对象，工厂 `reconstitute(...)`。访问器 `id()/code()/name()/url()/authType()/authHeader()/enabled()/remark()/createdAt()`。

- [ ] **Step 1: 写失败测试**

```java
package com.portfolio.invest.domain.mcp;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

class McpServerCatalogTest {

    private static final Instant NOW = Instant.parse("2026-09-06T08:00:00Z");

    private static McpServerCatalog tushare() {
        return McpServerCatalog.reconstitute(2L, "tushare", "Tushare 官方",
                "https://api.tushare.pro/mcp/", AuthType.BEARER, null, true, "A股行情", NOW);
    }

    @Test
    void 重建保留全部字段() {
        var c = tushare();
        assertThat(c.id()).isEqualTo(2L);
        assertThat(c.code()).isEqualTo("tushare");
        assertThat(c.authType()).isEqualTo(AuthType.BEARER);
        assertThat(c.url()).isEqualTo("https://api.tushare.pro/mcp/");
    }

    @Test
    void 妙想为HEADER且带鉴权头名() {
        var c = McpServerCatalog.reconstitute(1L, "mx-ds", "妙想",
                "https://mxapi.eastmoney.com/mxds/mcp", AuthType.HEADER, "em_api_key", true, null, NOW);
        assertThat(c.authType()).isEqualTo(AuthType.HEADER);
        assertThat(c.authHeader()).isEqualTo("em_api_key");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend && ./gradlew test --tests "com.portfolio.invest.domain.mcp.McpServerCatalogTest" --console=plain`
Expected: 编译失败。

- [ ] **Step 3: 实现**

```java
package com.portfolio.invest.domain.mcp;

import java.time.Instant;

/** 内置数据源目录条目：只读，seed 来自 Flyway 迁移。 */
public final class McpServerCatalog {

    private final Long id;
    private final String code;
    private final String name;
    private final String url;
    private final AuthType authType;
    private final String authHeader;
    private final boolean enabled;
    private final String remark;
    private final Instant createdAt;

    private McpServerCatalog(Long id, String code, String name, String url, AuthType authType,
                             String authHeader, boolean enabled, String remark, Instant createdAt) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.url = url;
        this.authType = authType;
        this.authHeader = authHeader;
        this.enabled = enabled;
        this.remark = remark;
        this.createdAt = createdAt;
    }

    public static McpServerCatalog reconstitute(Long id, String code, String name, String url,
                                                AuthType authType, String authHeader, boolean enabled,
                                                String remark, Instant createdAt) {
        return new McpServerCatalog(id, code, name, url, authType, authHeader, enabled, remark, createdAt);
    }

    public Long id() { return id; }
    public String code() { return code; }
    public String name() { return name; }
    public String url() { return url; }
    public AuthType authType() { return authType; }
    public String authHeader() { return authHeader; }
    public boolean enabled() { return enabled; }
    public String remark() { return remark; }
    public Instant createdAt() { return createdAt; }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: 同 Step 2。Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/portfolio/invest/domain/mcp/McpServerCatalog.java \
        backend/src/test/java/com/portfolio/invest/domain/mcp/McpServerCatalogTest.java
git commit -m "feat(mcp): 目录条目实体"
```

---

### Task 3: McpUserConfig 聚合

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/domain/mcp/McpUserConfig.java`
- Test: `backend/src/test/java/com/portfolio/invest/domain/mcp/McpUserConfigTest.java`

**Interfaces:**
- Produces: `McpUserConfig` 聚合根；工厂 `create(userId, catalogId, authSecretEnc, disabledTools, now)` 与 `reconstitute(...)`；`update(enabled, authSecretEnc, disabledTools, now)` 返回新实例且 `config_version` 自增。访问器 `id()/userId()/catalogId()/enabled()/authSecretEnc()/disabledTools()/configVersion()/createdAt()/updatedAt()`。

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
    void 创建默认启用且版本为1() {
        var c = McpUserConfig.create(1L, 2L, "enc-token", List.of("get_kline"), NOW);
        assertThat(c.enabled()).isTrue();
        assertThat(c.configVersion()).isEqualTo(1);
        assertThat(c.disabledTools()).containsExactly("get_kline");
        assertThat(c.authSecretEnc()).isEqualTo("enc-token");
    }

    @Test
    void 禁用工具清单为空是合法的() {
        var c = McpUserConfig.create(1L, 2L, null, List.of(), NOW);
        assertThat(c.disabledTools()).isEmpty();
        assertThat(c.authSecretEnc()).isNull();
    }

    @Test
    void 禁用工具清单为null抛INVALID_INPUT() {
        assertThatThrownBy(() -> McpUserConfig.create(1L, 2L, null, null, NOW))
                .isInstanceOfSatisfying(McpException.class,
                        e -> assertThat(e.code()).isEqualTo(McpErrorCode.INVALID_INPUT));
    }

    @Test
    void 更新返回新实例且版本自增() {
        var c = McpUserConfig.create(1L, 2L, "old", List.of(), NOW);
        var updated = c.update(false, "new", List.of("get_quote"), NOW.plusSeconds(10));

        assertThat(updated.enabled()).isFalse();
        assertThat(updated.authSecretEnc()).isEqualTo("new");
        assertThat(updated.disabledTools()).containsExactly("get_quote");
        assertThat(updated.configVersion()).isEqualTo(2);
        assertThat(updated.updatedAt()).isEqualTo(NOW.plusSeconds(10));
        // 原实例不变
        assertThat(c.enabled()).isTrue();
        assertThat(c.configVersion()).isEqualTo(1);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend && ./gradlew test --tests "com.portfolio.invest.domain.mcp.McpUserConfigTest" --console=plain`
Expected: 编译失败。

- [ ] **Step 3: 实现**

```java
package com.portfolio.invest.domain.mcp;

import java.time.Instant;
import java.util.List;

/** 用户对某内置数据源的配置：不可变，变更 update 返回新实例（config_version 自增驱动缓存失效）。 */
public final class McpUserConfig {

    private final Long id;
    private final Long userId;
    private final Long catalogId;
    private final boolean enabled;
    private final String authSecretEnc;
    private final List<String> disabledTools;
    private final int configVersion;
    private final Instant createdAt;
    private final Instant updatedAt;

    private McpUserConfig(Long id, Long userId, Long catalogId, boolean enabled, String authSecretEnc,
                          List<String> disabledTools, int configVersion, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.userId = userId;
        this.catalogId = catalogId;
        this.enabled = enabled;
        this.authSecretEnc = authSecretEnc;
        this.disabledTools = disabledTools;
        this.configVersion = configVersion;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static McpUserConfig create(Long userId, Long catalogId, String authSecretEnc,
                                       List<String> disabledTools, Instant now) {
        if (userId == null || catalogId == null) {
            throw new McpException(McpErrorCode.INVALID_INPUT, "用户与数据源不能为空");
        }
        if (disabledTools == null) {
            throw new McpException(McpErrorCode.INVALID_INPUT, "禁用工具清单不能为空");
        }
        return new McpUserConfig(null, userId, catalogId, true, authSecretEnc,
                List.copyOf(disabledTools), 1, now, now);
    }

    public static McpUserConfig reconstitute(Long id, Long userId, Long catalogId, boolean enabled,
                                             String authSecretEnc, List<String> disabledTools,
                                             int configVersion, Instant createdAt, Instant updatedAt) {
        return new McpUserConfig(id, userId, catalogId, enabled, authSecretEnc,
                disabledTools == null ? List.of() : List.copyOf(disabledTools),
                configVersion, createdAt, updatedAt);
    }

    /** 更新可变字段（userId/catalogId 不可变），config_version 自增，返回新实例。 */
    public McpUserConfig update(boolean enabled, String authSecretEnc, List<String> disabledTools, Instant now) {
        if (disabledTools == null) {
            throw new McpException(McpErrorCode.INVALID_INPUT, "禁用工具清单不能为空");
        }
        return new McpUserConfig(id, userId, catalogId, enabled, authSecretEnc,
                List.copyOf(disabledTools), configVersion + 1, createdAt, now);
    }

    public Long id() { return id; }
    public Long userId() { return userId; }
    public Long catalogId() { return catalogId; }
    public boolean enabled() { return enabled; }
    public String authSecretEnc() { return authSecretEnc; }
    public List<String> disabledTools() { return disabledTools; }
    public int configVersion() { return configVersion; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: 同 Step 2。Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/portfolio/invest/domain/mcp/McpUserConfig.java \
        backend/src/test/java/com/portfolio/invest/domain/mcp/McpUserConfigTest.java
git commit -m "feat(mcp): 用户配置聚合（不可变/版本自增）"
```

---

### Task 4: 仓库端口 + Flyway V10 迁移

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/domain/mcp/McpConfigRepository.java`
- Create: `backend/src/main/resources/db/migration/V10__mcp.sql`

**Interfaces:**
- Produces: 仓库端口 `McpConfigRepository`（方法见下）；迁移建 `mcp_server_catalog` / `mcp_user_config` 两表并 seed 4 条目录。

- [ ] **Step 1: 写仓库端口**

```java
package com.portfolio.invest.domain.mcp;

import java.util.List;
import java.util.Optional;

/** MCP 配置仓库端口：目录只读，用户配置按 userId 归属过滤。 */
public interface McpConfigRepository {
    List<McpServerCatalog> findEnabledCatalogs();
    Optional<McpServerCatalog> findCatalogById(Long catalogId);

    List<McpUserConfig> findByUserId(Long userId);
    Optional<McpUserConfig> findByUserIdAndCatalogId(Long userId, Long catalogId);
    McpUserConfig save(McpUserConfig config);
    void deleteByUserIdAndCatalogId(Long userId, Long catalogId);
}
```

- [ ] **Step 2: 写迁移**

```sql
-- MCP 数据源接入：内置目录（只读）+ 用户配置
-- iFinD / Wind 的端点 URL 与鉴权头名按 P0 验证记录 §4 回填。

CREATE TABLE mcp_server_catalog (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(32) NOT NULL UNIQUE,
    name        VARCHAR(64) NOT NULL,
    url         VARCHAR(512) NOT NULL,          -- 仅 https
    auth_type   VARCHAR(16) NOT NULL,           -- NONE / BEARER / HEADER
    auth_header VARCHAR(64),                    -- HEADER 时的头名（如 em_api_key）
    enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    remark      VARCHAR(255),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE mcp_user_config (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES app_user(id),
    catalog_id      BIGINT NOT NULL REFERENCES mcp_server_catalog(id),
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    auth_secret_enc TEXT,                       -- 用户 Token（AES-GCM 密文），NONE 时为 NULL
    disabled_tools  JSONB NOT NULL DEFAULT '[]',
    config_version  INT NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, catalog_id)
);
CREATE INDEX idx_mcp_user_config_user ON mcp_user_config(user_id);

INSERT INTO mcp_server_catalog (code, name, url, auth_type, auth_header, remark) VALUES
    ('mx-ds',   '东方财富妙想', 'https://mxapi.eastmoney.com/mxds/mcp', 'HEADER', 'em_api_key', 'A股/港股/美股行情、财务、估值、宏观、公告'),
    ('tushare', 'Tushare 官方', 'https://api.tushare.pro/mcp/', 'BEARER', NULL, 'A股行情、财务、宏观、债券、基金、指数'),
    ('ifind',   '同花顺 iFinD', '<P0§4 端点>', 'HEADER', '<P0§4 头名>', 'A股分析、基金、宏观行业、公告检索'),
    ('wind',    'Wind AIFin',  '<P0§4 端点>', 'HEADER', '<P0§4 头名>', 'A股/港美股行情、财报、宏观、债券、基金、指数');
```

> 执行前：用 P0 验证记录 §4 的真实端点/头名替换 `ifind`/`wind` 两行的 `<P0§4 …>` 占位。

- [ ] **Step 3: Commit（仓库端口 + 迁移由 Task 6 集成测试验证）**

```bash
git add backend/src/main/java/com/portfolio/invest/domain/mcp/McpConfigRepository.java \
        backend/src/main/resources/db/migration/V10__mcp.sql
git commit -m "feat(mcp): 仓库端口与 Flyway V10 迁移（目录+用户配置+seed）"
```

---

### Task 5: JPA 实体与仓库实现

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/infrastructure/persistence/McpServerCatalogJpaEntity.java`
- Create: `backend/src/main/java/com/portfolio/invest/infrastructure/persistence/McpUserConfigJpaEntity.java`
- Create: `backend/src/main/java/com/portfolio/invest/infrastructure/persistence/McpServerCatalogJpaRepository.java`
- Create: `backend/src/main/java/com/portfolio/invest/infrastructure/persistence/McpUserConfigJpaRepository.java`
- Create: `backend/src/main/java/com/portfolio/invest/infrastructure/persistence/McpConfigRepositoryImpl.java`

**Interfaces:**
- Consumes: `McpConfigRepository`（Task 4）、`McpServerCatalog`/`McpUserConfig`（Task 2/3）。
- Produces: `McpConfigRepositoryImpl implements McpConfigRepository`（Spring `@Repository`）。

- [ ] **Step 1: 写目录 JPA 实体**

```java
package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.mcp.AuthType;
import com.portfolio.invest.domain.mcp.McpServerCatalog;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "mcp_server_catalog")
public class McpServerCatalogJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String code;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(nullable = false, length = 512)
    private String url;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_type", nullable = false, length = 16)
    private AuthType authType;

    @Column(name = "auth_header", length = 64)
    private String authHeader;

    @Column(nullable = false)
    private boolean enabled;

    @Column(length = 255)
    private String remark;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected McpServerCatalogJpaEntity() {}

    public static McpServerCatalogJpaEntity fromDomain(McpServerCatalog c) {
        McpServerCatalogJpaEntity e = new McpServerCatalogJpaEntity();
        e.id = c.id();
        e.code = c.code();
        e.name = c.name();
        e.url = c.url();
        e.authType = c.authType();
        e.authHeader = c.authHeader();
        e.enabled = c.enabled();
        e.remark = c.remark();
        e.createdAt = c.createdAt();
        return e;
    }

    public McpServerCatalog toDomain() {
        return McpServerCatalog.reconstitute(id, code, name, url, authType, authHeader, enabled, remark, createdAt);
    }
}
```

- [ ] **Step 2: 写用户配置 JPA 实体**

```java
package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.mcp.McpUserConfig;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "mcp_user_config")
public class McpUserConfigJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "catalog_id", nullable = false)
    private Long catalogId;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "auth_secret_enc", columnDefinition = "text")
    private String authSecretEnc;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "disabled_tools", nullable = false, columnDefinition = "jsonb")
    private List<String> disabledTools;

    @Column(name = "config_version", nullable = false)
    private int configVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected McpUserConfigJpaEntity() {}

    public static McpUserConfigJpaEntity fromDomain(McpUserConfig c) {
        McpUserConfigJpaEntity e = new McpUserConfigJpaEntity();
        e.id = c.id();
        e.userId = c.userId();
        e.catalogId = c.catalogId();
        e.enabled = c.enabled();
        e.authSecretEnc = c.authSecretEnc();
        e.disabledTools = c.disabledTools();
        e.configVersion = c.configVersion();
        e.createdAt = c.createdAt();
        e.updatedAt = c.updatedAt();
        return e;
    }

    public McpUserConfig toDomain() {
        return McpUserConfig.reconstitute(id, userId, catalogId, enabled, authSecretEnc,
                disabledTools, configVersion, createdAt, updatedAt);
    }
}
```

- [ ] **Step 3: 写 Spring Data 仓库**

```java
package com.portfolio.invest.infrastructure.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface McpServerCatalogJpaRepository extends JpaRepository<McpServerCatalogJpaEntity, Long> {
    List<McpServerCatalogJpaEntity> findByEnabledTrueOrderByIdAsc();
}
```

```java
package com.portfolio.invest.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface McpUserConfigJpaRepository extends JpaRepository<McpUserConfigJpaEntity, Long> {
    List<McpUserConfigJpaEntity> findByUserId(Long userId);
    Optional<McpUserConfigJpaEntity> findByUserIdAndCatalogId(Long userId, Long catalogId);
    void deleteByUserIdAndCatalogId(Long userId, Long catalogId);
}
```

- [ ] **Step 4: 写仓库实现**

```java
package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.mcp.McpConfigRepository;
import com.portfolio.invest.domain.mcp.McpServerCatalog;
import com.portfolio.invest.domain.mcp.McpUserConfig;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

// 事务边界在 application 层（P2）；本类不挂 @Transactional。
@Repository
public class McpConfigRepositoryImpl implements McpConfigRepository {

    private final McpServerCatalogJpaRepository catalogJpa;
    private final McpUserConfigJpaRepository configJpa;

    public McpConfigRepositoryImpl(McpServerCatalogJpaRepository catalogJpa,
                                   McpUserConfigJpaRepository configJpa) {
        this.catalogJpa = catalogJpa;
        this.configJpa = configJpa;
    }

    @Override
    public List<McpServerCatalog> findEnabledCatalogs() {
        return catalogJpa.findByEnabledTrueOrderByIdAsc().stream()
                .map(McpServerCatalogJpaEntity::toDomain).toList();
    }

    @Override
    public Optional<McpServerCatalog> findCatalogById(Long catalogId) {
        return catalogJpa.findById(catalogId).map(McpServerCatalogJpaEntity::toDomain);
    }

    @Override
    public List<McpUserConfig> findByUserId(Long userId) {
        return configJpa.findByUserId(userId).stream().map(McpUserConfigJpaEntity::toDomain).toList();
    }

    @Override
    public Optional<McpUserConfig> findByUserIdAndCatalogId(Long userId, Long catalogId) {
        return configJpa.findByUserIdAndCatalogId(userId, catalogId).map(McpUserConfigJpaEntity::toDomain);
    }

    @Override
    public McpUserConfig save(McpUserConfig config) {
        return configJpa.save(McpUserConfigJpaEntity.fromDomain(config)).toDomain();
    }

    @Override
    public void deleteByUserIdAndCatalogId(Long userId, Long catalogId) {
        configJpa.deleteByUserIdAndCatalogId(userId, catalogId);
    }
}
```

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/portfolio/invest/infrastructure/persistence/McpServerCatalogJpaEntity.java \
        backend/src/main/java/com/portfolio/invest/infrastructure/persistence/McpUserConfigJpaEntity.java \
        backend/src/main/java/com/portfolio/invest/infrastructure/persistence/McpServerCatalogJpaRepository.java \
        backend/src/main/java/com/portfolio/invest/infrastructure/persistence/McpUserConfigJpaRepository.java \
        backend/src/main/java/com/portfolio/invest/infrastructure/persistence/McpConfigRepositoryImpl.java
git commit -m "feat(mcp): JPA 实体与仓库实现"
```

---

### Task 6: 仓库集成测试（Testcontainers 真实 PG）

**Files:**
- Test: `backend/src/integrationTest/java/com/portfolio/invest/infrastructure/persistence/McpConfigRepositoryImplTest.java`

**Interfaces:**
- Consumes: `McpConfigRepositoryImpl`（Task 5）、Flyway V10（Task 4）。
- 参考既有 `JournalEntryRepositoryImplTest` 的 `PostgresTestSupport` 基座（`testFixtures` 源集，共享 Testcontainers 容器，禁用 Ryuk）。

- [ ] **Step 1: 写集成测试**

```java
package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.mcp.AuthType;
import com.portfolio.invest.domain.mcp.McpServerCatalog;
import com.portfolio.invest.domain.mcp.McpUserConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class McpConfigRepositoryImplTest extends PostgresTestSupport {

    @Autowired
    private McpConfigRepositoryImpl repository;

    @Test
    void 迁移seed了4条目录且启用的可查到() {
        List<McpServerCatalog> catalogs = repository.findEnabledCatalogs();
        assertThat(catalogs).hasSize(4);
        assertThat(catalogs).extracting(McpServerCatalog::code)
                .containsExactlyInAnyOrder("mx-ds", "tushare", "ifind", "wind");
    }

    @Test
    void tushare为BEARER() {
        var c = repository.findCatalogById(2L).orElseThrow();
        assertThat(c.authType()).isEqualTo(AuthType.BEARER);
        assertThat(c.url()).isEqualTo("https://api.tushare.pro/mcp/");
    }

    @Test
    void 保存并回读用户配置() {
        McpUserConfig saved = repository.save(
                McpUserConfig.create(1L, 2L, "enc-token", List.of("get_kline"), Instant.now()));

        assertThat(saved.id()).isNotNull();
        var found = repository.findByUserIdAndCatalogId(1L, 2L).orElseThrow();
        assertThat(found.authSecretEnc()).isEqualTo("enc-token");
        assertThat(found.disabledTools()).containsExactly("get_kline");
        assertThat(found.configVersion()).isEqualTo(1);
    }

    @Test
    void 更新后configVersion自增() {
        var saved = repository.save(McpUserConfig.create(1L, 2L, "a", List.of(), Instant.now()));
        repository.save(saved.update(false, "b", List.of("get_quote"), Instant.now()));

        var found = repository.findByUserIdAndCatalogId(1L, 2L).orElseThrow();
        assertThat(found.configVersion()).isEqualTo(2);
        assertThat(found.enabled()).isFalse();
        assertThat(found.disabledTools()).containsExactly("get_quote");
    }

    @Test
    void 用户隔离与删除() {
        repository.save(McpUserConfig.create(1L, 2L, "a", List.of(), Instant.now()));
        assertThat(repository.findByUserId(2L)).isEmpty();
        assertThat(repository.findByUserIdAndCatalogId(2L, 2L)).isEmpty();

        repository.deleteByUserIdAndCatalogId(1L, 2L);
        assertThat(repository.findByUserId(1L)).isEmpty();
    }
}
```

- [ ] **Step 2: 跑测试确认通过**

Run: `cd backend && ./gradlew integrationTest --tests "com.portfolio.invest.infrastructure.persistence.McpConfigRepositoryImplTest" --console=plain`
Expected: PASS（真实 PG，Flyway V10 迁移 + seed 生效）。

- [ ] **Step 3: Commit**

```bash
git add backend/src/integrationTest/java/com/portfolio/invest/infrastructure/persistence/McpConfigRepositoryImplTest.java
git commit -m "test(mcp): 仓库集成测试（真实 PG + V10 迁移）"
```

---

### Task 7: AesGcmTokenCipher（AES-GCM 实现）

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/infrastructure/security/AesGcmTokenCipher.java`
- Modify: `backend/src/main/resources/application.yml`（新增 `invest.mcp.secret-key: ${MCP_SECRET_KEY:}`）
- Modify: `.env.example`（新增 `MCP_SECRET_KEY=`）
- Test: `backend/src/test/java/com/portfolio/invest/infrastructure/security/AesGcmTokenCipherTest.java`

**Interfaces:**
- Consumes: `TokenCipher`（Task 1）。
- Produces: `AesGcmTokenCipher implements TokenCipher`（`@Component`）。主密钥来自 `invest.mcp.secret-key`（base64 32 字节）；**启动不阻断、缺失时打 warning，encrypt/decrypt 时报错**。

- [ ] **Step 1: 写失败测试**

```java
package com.portfolio.invest.infrastructure.security;

import org.junit.jupiter.api.Test;
import java.util.Base64;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesGcmTokenCipherTest {

    private static final String KEY = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());

    @Test
    void 加解密往返() {
        var cipher = new AesGcmTokenCipher(KEY);
        String ct = cipher.encrypt("my-secret-token");
        assertThat(ct).isNotBlank().doesNotContain("my-secret-token");
        assertThat(cipher.decrypt(ct)).isEqualTo("my-secret-token");
    }

    @Test
    void 相同明文两次加密结果不同随机IV() {
        var cipher = new AesGcmTokenCipher(KEY);
        assertThat(cipher.encrypt("x")).isNotEqualTo(cipher.encrypt("x"));
    }

    @Test
    void 密钥缺失时加密报错而非启动即抛() {
        var cipher = new AesGcmTokenCipher("");
        assertThatThrownBy(() -> cipher.encrypt("x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MCP_SECRET_KEY");
    }

    @Test
    void 密钥长度非法时构造报错() {
        assertThatThrownBy(() -> new AesGcmTokenCipher("c2hvcnQ="))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend && ./gradlew test --tests "com.portfolio.invest.infrastructure.security.AesGcmTokenCipherTest" --console=plain`
Expected: 编译失败。

- [ ] **Step 3: 实现**

```java
package com.portfolio.invest.infrastructure.security;

import com.portfolio.invest.domain.mcp.TokenCipher;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** AES-256-GCM Token 加解密。主密钥 invest.mcp.secret-key（base64 32 字节）；启动不阻断，缺失时首次加解密报错。 */
@Component
public class AesGcmTokenCipher implements TokenCipher {

    private static final Logger log = LoggerFactory.getLogger(AesGcmTokenCipher.class);
    private static final int IV_LEN = 12;
    private static final int KEY_LEN = 32;

    private final SecretKey key;

    public AesGcmTokenCipher(@Value("${invest.mcp.secret-key:}") String secretKey) {
        this.key = buildKey(secretKey);
    }

    private static SecretKey buildKey(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            log.warn("MCP_SECRET_KEY 未配置：MCP 数据源 Token 加解密不可用（首次保存 Token 时将报错）");
            return null;
        }
        byte[] raw = Base64.getDecoder().decode(base64Key);
        if (raw.length != KEY_LEN) {
            throw new IllegalArgumentException("MCP_SECRET_KEY 必须为 32 字节的 base64 编码");
        }
        return new SecretKeySpec(raw, "AES");
    }

    @Override
    public String encrypt(String plaintext) {
        SecretKey k = requireKey();
        try {
            byte[] iv = new byte[IV_LEN];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, k, new GCMParameterSpec(128, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[IV_LEN + ct.length];
            System.arraycopy(iv, 0, out, 0, IV_LEN);
            System.arraycopy(ct, 0, out, IV_LEN, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Token 加密失败", e);
        }
    }

    @Override
    public String decrypt(String ciphertext) {
        SecretKey k = requireKey();
        try {
            byte[] all = Base64.getDecoder().decode(ciphertext);
            byte[] iv = Arrays.copyOfRange(all, 0, IV_LEN);
            byte[] ct = Arrays.copyOfRange(all, IV_LEN, all.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Token 解密失败", e);
        }
    }

    private SecretKey requireKey() {
        if (key == null) {
            throw new IllegalStateException("MCP_SECRET_KEY 未配置，无法加解密 Token");
        }
        return key;
    }
}
```

- [ ] **Step 4: 加配置与文档**

`application.yml` 的 `invest:` 段新增一行：`mcp:` 子段下 `secret-key: ${MCP_SECRET_KEY:}`（与 `security.remember-me-key` 并列）；`.env.example` 新增 `MCP_SECRET_KEY=`（注释说明「base64 32 字节，缺失时 MCP Token 加解密不可用」）。

- [ ] **Step 5: 跑测试确认通过**

Run: 同 Step 2。Expected: PASS。

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/portfolio/invest/infrastructure/security/AesGcmTokenCipher.java \
        backend/src/test/java/com/portfolio/invest/infrastructure/security/AesGcmTokenCipherTest.java \
        backend/src/main/resources/application.yml .env.example
git commit -m "feat(mcp): AES-GCM Token 加解密（MCP_SECRET_KEY）"
```

---

## P1 完成验证

```bash
cd backend
./gradlew test              # 领域单测 + 架构测试（PackageConventionsTest：domain.mcp 零 Spring 注解、零跨域依赖自动校验）
./gradlew integrationTest   # 含 McpConfigRepositoryImplTest
```

确认：`domain/mcp` 零 Spring 注解、零项目内跨域依赖；V10 迁移在真实 PG 生效且 seed 4 条；`AesGcmTokenCipher` 满足「启动不阻断 + 缺失警告 + 写时/解时报错」。
