# Skill 域模型与迁移 Implementation Plan（P1）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落地 `domain.skill`（不可变 `SkillUserConfig` + `SkillConfigRepository` 接口 + 错误类型）+ Flyway V11 迁移 + `infrastructure.persistence` 实现，全部经单元/集成测试验证。

**Architecture:** 遵循 DDD 洋葱分层（domain 纯 POJO、无 Spring 注解；infrastructure 实现 domain 接口）。Skill 目录在 classpath（不在 DB），DB 只存 `skill_user_config` 用户选择。

**Tech Stack:** Spring Boot 4 / Spring Data JPA / PostgreSQL 16 / Flyway / JUnit 5 + AssertJ + Mockito / Testcontainers（`PostgresTestSupport` 基座）

**Spec:** `features/skill-integration/02-design/设计规格说明.md`（§二 数据模型）、`features/skill-integration/01-requirement/需求规格说明.md`

## Global Constraints

- 覆盖门槛 ≥80%（JaCoCo 指令/分支，`make test` 卡 check）。
- domain 纯 POJO：`PackageConventionsTest.domainHasNoSpringAnnotations` 禁 `@Service/@Component/@Configuration/@RestController`；`domainHasNoInternalDependencies` 只依赖 domain + 项目外。
- schema 由 Flyway 管（`ddl-auto: none`），迁移放 `backend/src/main/resources/db/migration/`（当前最大 V10，本特性新增 **V11__skill.sql**）。
- 集成测试切片：`@DataJpaTest` + `@AutoConfigureTestDatabase(replace=NONE)` + `@ImportAutoConfiguration(FlywayAutoConfiguration.class)` + `@Import(XxxRepositoryImpl.class)` + 继承 `com.portfolio.invest.support.PostgresTestSupport`；`@BeforeEach` 用 JdbcTemplate seed `app_user`（高位哨兵 id 避开全量运行时注册测试的 1..n）。
- 领域实体不可变：`create(...)`/`reconstitute(...)` 静态工厂，`update(...)` 返回新实例（不就地改）。
- 包名前缀 `com.portfolio.invest`。

---

### Task 1: V11 迁移 + `SkillUserConfig` 领域模型

**Files:**
- Create: `backend/src/main/resources/db/migration/V11__skill.sql`
- Create: `backend/src/main/java/com/portfolio/invest/domain/skill/SkillErrorCode.java`
- Create: `backend/src/main/java/com/portfolio/invest/domain/skill/SkillException.java`
- Create: `backend/src/main/java/com/portfolio/invest/domain/skill/SkillUserConfig.java`
- Test: `backend/src/test/java/com/portfolio/invest/domain/skill/SkillUserConfigTest.java`

**Interfaces:**
- Produces:
  - `SkillUserConfig.create(Long userId, String skillCode, boolean enabled, Instant now)` → 新实例（id=null, updatedAt=now）
  - `SkillUserConfig.reconstitute(Long id, Long userId, String skillCode, boolean enabled, Instant updatedAt)`
  - `SkillUserConfig.update(boolean enabled, Instant now)` → 新实例
  - 访问器 `id()/userId()/skillCode()/enabled()/updatedAt()`
  - `SkillException(String code, String message)` + `code()`；`SkillErrorCode.INVALID_INPUT = "INVALID_INPUT"`

- [ ] **Step 1: 写迁移（无单测，由 Task 3 集成测试验证）**

`V11__skill.sql`：
```sql
-- 系统内置 Skill：skill 目录在 classpath（resources/skills/**/SKILL.md），DB 只存用户选择
CREATE TABLE skill_user_config (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES app_user(id),
    skill_code  VARCHAR(64) NOT NULL,
    enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, skill_code)
);
CREATE INDEX idx_skill_user_config_user ON skill_user_config(user_id);
```

- [ ] **Step 2: 写失败测试（TDD 红）**

`SkillUserConfigTest.java`：
```java
package com.portfolio.invest.domain.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SkillUserConfigTest {
    private static final Instant NOW = Instant.parse("2026-09-07T08:00:00Z");

    @DisplayName("创建记录启用状态与 skill_code 落位")
    @Test
    void givenCreate_whenValid_thenFieldsSet() {
        var c = SkillUserConfig.create(1L, "tushare_data", true, NOW);
        assertThat(c.userId()).isEqualTo(1L);
        assertThat(c.skillCode()).isEqualTo("tushare_data");
        assertThat(c.enabled()).isTrue();
        assertThat(c.updatedAt()).isEqualTo(NOW);
    }

    @DisplayName("userId 为 null 抛 INVALID_INPUT")
    @Test
    void givenNullUserId_whenCreate_thenThrowInvalidInput() {
        assertThatThrownBy(() -> SkillUserConfig.create(null, "tushare_data", true, NOW))
                .isInstanceOfSatisfying(SkillException.class,
                        e -> assertThat(e.code()).isEqualTo(SkillErrorCode.INVALID_INPUT));
    }

    @DisplayName("skillCode 空白抛 INVALID_INPUT")
    @Test
    void givenBlankSkillCode_whenCreate_thenThrowInvalidInput() {
        assertThatThrownBy(() -> SkillUserConfig.create(1L, "  ", true, NOW))
                .isInstanceOfSatisfying(SkillException.class,
                        e -> assertThat(e.code()).isEqualTo(SkillErrorCode.INVALID_INPUT));
    }

    @DisplayName("更新返回新实例且原实例不变")
    @Test
    void givenUpdate_whenToggle_thenNewInstanceOriginalUnchanged() {
        var c = SkillUserConfig.create(1L, "tushare_data", true, NOW);
        var u = c.update(false, NOW.plusSeconds(10));
        assertThat(u.enabled()).isFalse();
        assertThat(u.updatedAt()).isEqualTo(NOW.plusSeconds(10));
        assertThat(c.enabled()).isTrue();
        assertThat(c.updatedAt()).isEqualTo(NOW);
    }
}
```

Run: `cd backend && ./gradlew test --tests "com.portfolio.invest.domain.skill.SkillUserConfigTest" --console=plain`
Expected: FAIL（`SkillUserConfig` 未定义）

- [ ] **Step 3: 实现三个类（TDD 绿）**

`SkillErrorCode.java`：
```java
package com.portfolio.invest.domain.skill;

public final class SkillErrorCode {
    private SkillErrorCode() {}
    public static final String INVALID_INPUT = "INVALID_INPUT";
}
```

`SkillException.java`：
```java
package com.portfolio.invest.domain.skill;

public class SkillException extends RuntimeException {
    private final String code;
    public SkillException(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
}
```

`SkillUserConfig.java`：
```java
package com.portfolio.invest.domain.skill;

import java.time.Instant;

/** 用户对某内置 skill 的启用选择：不可变，变更 update 返回新实例。无目录字段（目录在 classpath）。 */
public final class SkillUserConfig {
    private final Long id;
    private final Long userId;
    private final String skillCode;
    private final boolean enabled;
    private final Instant updatedAt;

    private SkillUserConfig(Long id, Long userId, String skillCode, boolean enabled, Instant updatedAt) {
        this.id = id; this.userId = userId; this.skillCode = skillCode; this.enabled = enabled; this.updatedAt = updatedAt;
    }

    public static SkillUserConfig create(Long userId, String skillCode, boolean enabled, Instant now) {
        if (userId == null) {
            throw new SkillException(SkillErrorCode.INVALID_INPUT, "用户不能为空");
        }
        if (skillCode == null || skillCode.isBlank()) {
            throw new SkillException(SkillErrorCode.INVALID_INPUT, "技能标识不能为空");
        }
        return new SkillUserConfig(null, userId, skillCode, enabled, now);
    }

    public static SkillUserConfig reconstitute(Long id, Long userId, String skillCode, boolean enabled, Instant updatedAt) {
        return new SkillUserConfig(id, userId, skillCode, enabled, updatedAt);
    }

    public SkillUserConfig update(boolean enabled, Instant now) {
        return new SkillUserConfig(id, userId, skillCode, enabled, now);
    }

    public Long id() { return id; }
    public Long userId() { return userId; }
    public String skillCode() { return skillCode; }
    public boolean enabled() { return enabled; }
    public Instant updatedAt() { return updatedAt; }
}
```

Run: `cd backend && ./gradlew test --tests "com.portfolio.invest.domain.skill.SkillUserConfigTest" --console=plain`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/resources/db/migration/V11__skill.sql backend/src/main/java/com/portfolio/invest/domain/skill backend/src/test/java/com/portfolio/invest/domain/skill
git commit -m "feat(skill): V11 迁移与 SkillUserConfig 领域模型"
```

---

### Task 2: `SkillConfigRepository` 接口 + 持久化实现

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/domain/skill/SkillConfigRepository.java`
- Create: `backend/src/main/java/com/portfolio/invest/infrastructure/persistence/SkillUserConfigJpaEntity.java`
- Create: `backend/src/main/java/com/portfolio/invest/infrastructure/persistence/SkillUserConfigJpaRepository.java`
- Create: `backend/src/main/java/com/portfolio/invest/infrastructure/persistence/SkillConfigRepositoryImpl.java`

**Interfaces:**
- Consumes: Task 1 的 `SkillUserConfig`。
- Produces:
  - `SkillConfigRepository`：`List<SkillUserConfig> findByUserId(Long)`、`Optional<SkillUserConfig> findByUserIdAndSkillCode(Long, String)`、`SkillUserConfig save(SkillUserConfig)`
  - `SkillConfigRepositoryImpl`（`@Repository`，注入 `SkillUserConfigJpaRepository`）

- [ ] **Step 1: 写接口**

`SkillConfigRepository.java`：
```java
package com.portfolio.invest.domain.skill;

import java.util.List;
import java.util.Optional;

public interface SkillConfigRepository {
    List<SkillUserConfig> findByUserId(Long userId);
    Optional<SkillUserConfig> findByUserIdAndSkillCode(Long userId, String skillCode);
    SkillUserConfig save(SkillUserConfig config);
}
```

- [ ] **Step 2: 写 JPA 实体**

`SkillUserConfigJpaEntity.java`：
```java
package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.skill.SkillUserConfig;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "skill_user_config")
public class SkillUserConfigJpaEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(name = "skill_code", nullable = false)
    private String skillCode;
    @Column(nullable = false)
    private boolean enabled;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SkillUserConfigJpaEntity() {}

    public static SkillUserConfigJpaEntity fromDomain(SkillUserConfig c) {
        SkillUserConfigJpaEntity e = new SkillUserConfigJpaEntity();
        e.id = c.id(); e.userId = c.userId(); e.skillCode = c.skillCode();
        e.enabled = c.enabled(); e.updatedAt = c.updatedAt();
        return e;
    }

    public SkillUserConfig toDomain() {
        return SkillUserConfig.reconstitute(id, userId, skillCode, enabled, updatedAt);
    }
}
```

- [ ] **Step 3: 写 JPA 仓库接口与适配器**

`SkillUserConfigJpaRepository.java`：
```java
package com.portfolio.invest.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SkillUserConfigJpaRepository extends JpaRepository<SkillUserConfigJpaEntity, Long> {
    List<SkillUserConfigJpaEntity> findByUserId(Long userId);
    Optional<SkillUserConfigJpaEntity> findByUserIdAndSkillCode(Long userId, String skillCode);
}
```

`SkillConfigRepositoryImpl.java`：
```java
package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.skill.SkillConfigRepository;
import com.portfolio.invest.domain.skill.SkillUserConfig;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class SkillConfigRepositoryImpl implements SkillConfigRepository {
    private final SkillUserConfigJpaRepository jpa;

    public SkillConfigRepositoryImpl(SkillUserConfigJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override public List<SkillUserConfig> findByUserId(Long userId) {
        return jpa.findByUserId(userId).stream().map(SkillUserConfigJpaEntity::toDomain).toList();
    }
    @Override public Optional<SkillUserConfig> findByUserIdAndSkillCode(Long userId, String skillCode) {
        return jpa.findByUserIdAndSkillCode(userId, skillCode).map(SkillUserConfigJpaEntity::toDomain);
    }
    @Override public SkillUserConfig save(SkillUserConfig config) {
        return jpa.save(SkillUserConfigJpaEntity.fromDomain(config)).toDomain();
    }
}
```

- [ ] **Step 4: 编译验证（无独立单测，由 Task 3 集成测试覆盖）**

Run: `cd backend && ./gradlew compileJava --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/portfolio/invest/domain/skill/SkillConfigRepository.java backend/src/main/java/com/portfolio/invest/infrastructure/persistence/SkillUserConfigJpaEntity.java backend/src/main/java/com/portfolio/invest/infrastructure/persistence/SkillUserConfigJpaRepository.java backend/src/main/java/com/portfolio/invest/infrastructure/persistence/SkillConfigRepositoryImpl.java
git commit -m "feat(skill): SkillConfigRepository 接口与持久化实现"
```

---

### Task 3: 集成测试（迁移 + 仓库实现）

**Files:**
- Create: `backend/src/integrationTest/java/com/portfolio/invest/infrastructure/persistence/SkillConfigRepositoryImplTest.java`

**Interfaces:**
- Consumes: Task 1 迁移 + Task 2 仓库实现。
- Produces: 验证 V11 迁移建表 + `SkillConfigRepositoryImpl` 的 findByUserId / findByUserIdAndSkillCode / save 往返。

- [ ] **Step 1: 写集成测试（TDD 红——先写测试，跑失败确认迁移/实现缺失）**

`SkillConfigRepositoryImplTest.java`：
```java
package com.portfolio.invest.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.domain.skill.SkillUserConfig;
import com.portfolio.invest.support.PostgresTestSupport;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(SkillConfigRepositoryImpl.class)
class SkillConfigRepositoryImplTest extends PostgresTestSupport {

    @Autowired
    private SkillConfigRepositoryImpl repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** skill_user_config.user_id 外键引用 app_user(id)，需先植入用户行；高位哨兵 id 避开全量运行时注册测试。 */
    private static final long USER_71 = 71L;
    private static final long USER_72 = 72L;

    @BeforeEach
    void seedUsers() {
        jdbcTemplate.update(
                "INSERT INTO app_user(id, username, password_hash, role, status) VALUES (?, ?, ?, ?, ?)",
                USER_71, "skill-t7-71", "h", "USER", "PENDING");
        jdbcTemplate.update(
                "INSERT INTO app_user(id, username, password_hash, role, status) VALUES (?, ?, ?, ?, ?)",
                USER_72, "skill-t7-72", "h", "USER", "PENDING");
    }

    @DisplayName("保存后可按用户+技能码查回")
    @Test
    void givenSave_whenFindByUserIdAndSkillCode_thenRoundTrip() {
        Instant now = Instant.parse("2026-09-07T08:00:00Z");
        repository.save(SkillUserConfig.create(USER_71, "tushare_data", true, now));

        var found = repository.findByUserIdAndSkillCode(USER_71, "tushare_data").orElseThrow();
        assertThat(found.enabled()).isTrue();
        assertThat(found.userId()).isEqualTo(USER_71);
    }

    @DisplayName("按用户查全部选择")
    @Test
    void givenTwoConfigs_whenFindByUserId_thenBothReturned() {
        Instant now = Instant.parse("2026-09-07T08:00:00Z");
        repository.save(SkillUserConfig.create(USER_71, "tushare_data", true, now));
        repository.save(SkillUserConfig.create(USER_71, "wind_finance", false, now));
        repository.save(SkillUserConfig.create(USER_72, "tushare_data", false, now));

        var mine = repository.findByUserId(USER_71);
        assertThat(mine).extracting(SkillUserConfig::skillCode)
                .containsExactlyInAnyOrder("tushare_data", "wind_finance");
    }

    @DisplayName("同一用户+技能码保存覆盖更新")
    @Test
    void givenExisting_whenSaveAgain_thenUpdated() {
        Instant now = Instant.parse("2026-09-07T08:00:00Z");
        SkillUserConfig first = repository.save(SkillUserConfig.create(USER_71, "tushare_data", true, now));
        SkillUserConfig updated = repository.save(first.update(false, now.plusSeconds(10)));

        assertThat(updated.enabled()).isFalse();
        var persisted = repository.findByUserIdAndSkillCode(USER_71, "tushare_data").orElseThrow();
        assertThat(persisted.enabled()).isFalse();
    }
}
```

Run: `cd backend && ./gradlew integrationTest --tests "com.portfolio.invest.infrastructure.persistence.SkillConfigRepositoryImplTest" --console=plain`
Expected: FAIL（若迁移/实现尚未就位则报错；Task 1/2 已就位时直接 PASS——此时把 Step 2 的「实现」视为已完成，直接进入 Step 3 复跑确认绿）

- [ ] **Step 2: 复跑确认绿（Task 1/2 已实现，此处验证迁移+往返）**

Run: `cd backend && ./gradlew integrationTest --tests "com.portfolio.invest.infrastructure.persistence.SkillConfigRepositoryImplTest" --console=plain`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add backend/src/integrationTest/java/com/portfolio/invest/infrastructure/persistence/SkillConfigRepositoryImplTest.java
git commit -m "test(skill): SkillConfigRepository 集成测试（迁移+往返）"
```

---

## P1 完成验证

```bash
cd backend && ./gradlew test integrationTest --console=plain
```
确认：`SkillUserConfigTest` 绿、`SkillConfigRepositoryImplTest` 绿；`V11__skill.sql` 已建表并 seed 无（本特性无目录 seed）。
