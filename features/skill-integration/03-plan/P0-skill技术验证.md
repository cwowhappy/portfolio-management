# Skill 技术验证 Implementation Plan（P0）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 验证 AgentScope 2.0.1 的 Skill 集成点（`ClasspathSkillRepository` 枚举、`SkillFilter` 语义、`HarnessAgent.Builder` 装配点、fat-jar/空列表行为），产出「验证记录」，为 P1/P2 后端实现解锁。

**Architecture:** 只读验证（读 jar 源码 / javap / 一次最小运行时 spike），不落业务代码；产物为 `03-plan/验证记录.md` 与对 `02-design/设计规格说明.md` 的修正（如有）。

**Tech Stack:** javap / 读 `-sources.jar` / JUnit 5 spike（测试资源 `test-skills/`）

**Spec:** `features/skill-integration/02-design/设计规格说明.md`（§十开放问题 5、§十一工具命名空间）、`docs/technology/research/agentscope/skill.md`

## Global Constraints

- 不改业务代码，仅验证与记录；验证结论须可复现（贴命令与输出/结论）。
- 涉及 harness/core 库时以「读源码/字节码」为准（harness 无 sources jar，用 `javap`）。
- 每个 Task 以「验证记录」中的一节为交付物，逐节 commit。
- 依赖版本：`io.agentscope:agentscope-core:2.0.1`、`io.agentscope:agentscope-harness:2.0.1`（`backend/build.gradle` 已引入）。

---

### Task 1: 确认 `ClasspathSkillRepository` 枚举与元数据读取

**Files:**
- 只读：`~/.gradle/caches/.../agentscope-core-2.0.1-sources.jar`（`io/agentscope/core/skill/repository/ClasspathSkillRepository.java`、`util/SkillFileSystemHelper.java`、`util/MarkdownSkillParser.java`）
- Create: `backend/src/test/resources/test-skills/tushare_data/SKILL.md`、`backend/src/test/resources/test-skills/wind_finance/SKILL.md`
- Create: `backend/src/test/java/com/portfolio/invest/agent/ClasspathSkillRepositoryTest.java`
- Modify: `features/skill-integration/03-plan/验证记录.md`（§1）

**Interfaces:**
- Produces: 验证记录 §1——`ClasspathSkillRepository(String)` 从 classpath 读 `*/SKILL.md`、`getAllSkillNames()`/`getSkill(name)`/`getAllSkills()`、`AgentSkill.getMetadata()` 含 frontmatter 自定义字段；dev classpath 与 jar 两种 URI 的分支。

- [ ] **Step 1: 读源码记录 jar/dir 两种分支**

Run:
```bash
CORE_SRC=$(find ~/.gradle/caches -name 'agentscope-core-2.0.1-sources.jar' | head -1)
unzip -p "$CORE_SRC" io/agentscope/core/skill/repository/ClasspathSkillRepository.java | sed -n '1,200p'
```
确认：构造器用 `classLoader.getResource(resourcePath)`，`"jar"` scheme 走 `FileSystems.newFileSystem`（静态 `SHARED_FILE_SYSTEMS` 引用计数），否则 `Path.of(uri)`；`getSkill/getAllSkills` 委托 `SkillFileSystemHelper`。结论写入验证记录 §1。

- [ ] **Step 2: 建测试 skill 资源（验证枚举，用 test-skills 隔离 main 的 skills）**

创建 `backend/src/test/resources/test-skills/tushare_data/SKILL.md`：
```markdown
---
name: tushare_data
description: 使用 Tushare 官方 MCP 获取内置工具与 Wind 未覆盖的品类数据。
category: data_source
default_enabled: false
depends_on_provider: tushare
---
# Tushare 全品类金融数据补充
```
创建 `backend/src/test/resources/test-skills/wind_finance/SKILL.md`（name: wind_finance，category: data_source，default_enabled: false，depends_on_provider: wind）。

- [ ] **Step 3: 写 spike 测试并运行**

`backend/src/test/java/com/portfolio/invest/agent/ClasspathSkillRepositoryTest.java`：
```java
package com.portfolio.invest.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ClasspathSkillRepositoryTest {

    @DisplayName("从 classpath 枚举 test-skills 并读取 frontmatter 元数据")
    @Test
    void givenTestSkills_whenEnumerate_thenNamesAndMetadataLoaded() throws Exception {
        try (ClasspathSkillRepository repo = new ClasspathSkillRepository("test-skills")) {
            List<String> names = repo.getAllSkillNames();
            assertThat(names).containsExactlyInAnyOrder("tushare_data", "wind_finance");

            AgentSkill skill = repo.getSkill("tushare_data");
            assertThat(skill.getDescription()).isNotBlank();
            assertThat(skill.getMetadata()).containsEntry("category", "data_source");
            assertThat(skill.getMetadata()).containsEntry("default_enabled", false);
            assertThat(skill.getMetadata()).containsEntry("depends_on_provider", "tushare");
        }
    }
}
```

Run: `cd backend && ./gradlew test --tests "com.portfolio.invest.agent.ClasspathSkillRepositoryTest" --console=plain`
Expected: PASS（4 条断言全绿）。

- [ ] **Step 4: 写验证记录 §1 并 commit**

```bash
git add features/skill-integration/03-plan/验证记录.md backend/src/test/resources/test-skills backend/src/test/java/com/portfolio/invest/agent/ClasspathSkillRepositoryTest.java
git commit -m "test(skill): P0 验证 ClasspathSkillRepository 枚举与元数据读取"
```

---

### Task 2: 确认 `SkillFilter` 语义

**Files:**
- 只读：`agentscope-core-2.0.1-sources.jar`（`io/agentscope/core/skill/SkillFilter.java`）
- Modify: `features/skill-integration/03-plan/验证记录.md`（§2）

**Interfaces:**
- Produces: 验证记录 §2——`all/none/only/except`（standalone）与 `enable/disable`（overlay）语义、默认 `all()`、`overlay` 合并规则。

- [ ] **Step 1: 读源码并记录**

Run:
```bash
CORE_SRC=$(find ~/.gradle/caches -name 'agentscope-core-2.0.1-sources.jar' | head -1)
unzip -p "$CORE_SRC" io/agentscope/core/skill/SkillFilter.java
```
记录：`only(...)`=WHITELIST、`except(...)`=BLACKLIST、`enable/disable`=OVERLAY（仅 `overlay()` 合并时用）、默认无 filter=全部启用。结论写入 §2。

- [ ] **Step 2: 写验证记录 §2 并 commit**

```bash
git add features/skill-integration/03-plan/验证记录.md
git commit -m "docs(skill): P0 验证 SkillFilter 语义"
```

---

### Task 3: 确认 `HarnessAgent.Builder` 的 skill 装配点

**Files:**
- 只读：`~/.gradle/caches/.../agentscope-harness-2.0.1.jar`（`io/agentscope/harness/agent/HarnessAgent$Builder`）
- Modify: `features/skill-integration/03-plan/验证记录.md`（§3）

**Interfaces:**
- Produces: 验证记录 §3——`skillRepository`/`skillRepositories`/`skillFilter`/`enableSkills`/`disableSkills`/`disableDefaultWorkspaceSkills`/`disableDynamicSkills`/`projectGlobalSkillsDir` 的精确签名；`HarnessAgent.getSkillRepositories()` 存在。

- [ ] **Step 1: javap 记录 Builder 签名**

Run:
```bash
HARNESS_JAR=$(find ~/.gradle/caches -name 'agentscope-harness-2.0.1.jar' | head -1)
CORE_JAR=$(find ~/.gradle/caches -name 'agentscope-core-2.0.1.jar' | head -1)
javap -classpath "$HARNESS_JAR:$CORE_JAR" 'io.agentscope.harness.agent.HarnessAgent$Builder' | grep -iE 'skill|workspace|projectGlobal'
```
记录：skill 相关方法签名与「默认会加 `WorkspaceSkillRepository`、需 `disableDefaultWorkspaceSkills()` 收敛」的推论。结论写入 §3。

- [ ] **Step 2: 写验证记录 §3 并 commit**

```bash
git add features/skill-integration/03-plan/验证记录.md
git commit -m "docs(skill): P0 验证 HarnessAgent.Builder skill 装配点"
```

---

### Task 4: 确认空 skill 列表与 fat-jar 行为

**Files:**
- 只读：`agentscope-core-2.0.1-sources.jar`（`DynamicSkillMiddleware.java`、`SkillRegistry.java`）
- Modify: `features/skill-integration/03-plan/验证记录.md`（§4）

**Interfaces:**
- Produces: 验证记录 §4——「用户未启用任何 skill」时 `SkillFilter.only()`（空白名单）的语义；fat-jar 下 `ClasspathSkillRepository` 的 jar-FS 挂载/关闭（`close()` 释放引用计数）。

- [ ] **Step 1: 读源码记录空列表 + jar 分支**

Run:
```bash
CORE_SRC=$(find ~/.gradle/caches -name 'agentscope-core-2.0.1-sources.jar' | head -1)
unzip -p "$CORE_SRC" io/agentscope/core/skill/SkillFilter.java | grep -n "WHITELIST\|contains"
unzip -p "$CORE_SRC" io/agentscope/core/skill/repository/ClasspathSkillRepository.java | grep -n "newFileSystem\|releaseFileSystem\|close"
```
记录：`only()`（空集合）= 空白名单 → 无 skill 注入（`isAllowed` 恒 false）；fat-jar 经 `acquireFileSystem`/`releaseFileSystem` 引用计数管理虚拟 FS，**必须单例、勿每次 build 时 new/close**。结论写入 §4。

- [ ] **Step 2: 写验证记录 §4 并 commit**

```bash
git add features/skill-integration/03-plan/验证记录.md
git commit -m "docs(skill): P0 验证空列表与 fat-jar 行为"
```

---

### Task 5: 汇总验证记录，确认/修正设计

**Files:**
- Modify: `features/skill-integration/03-plan/验证记录.md`（结论速览表）
- Modify: `features/skill-integration/02-design/设计规格说明.md`（§十开放问题 5 → 结论；§十一工具命名空间如受影响则修正）

**Interfaces:**
- Consumes: Task 1–4 结论。
- Produces: 验证记录终稿；设计规格 §十 开放问题 5 标注「已确认」。

- [ ] **Step 1: 在验证记录顶部写「结论速览」表**

四问（枚举/语义/装配点/fat-jar）+ 结论 + 对 P1/P2 的影响。

- [ ] **Step 2: 更新设计规格 §十**

把「P0 待证」改为已确认结论；若 `build()` 装配组合与设计假设不符，同步修正 §四运行时装配。

- [ ] **Step 3: Commit**

```bash
git add features/skill-integration/03-plan/验证记录.md features/skill-integration/02-design/设计规格说明.md
git commit -m "docs(skill): P0 验证记录定稿，设计开放问题落地"
```

---

## P0 完成验证

```bash
grep -n "tushare_data\|wind_finance\|SkillFilter\|disableDefaultWorkspaceSkills" features/skill-integration/03-plan/验证记录.md
```
确认：验证记录四节齐全、结论可复现、无真实密钥、设计 §十 无遗留「待证」项。
