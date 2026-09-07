# Skill（技能系统）

> 定位：AgentScope 2.0.1 的 Skill 机制调研笔记，聚焦 **agentscope-harness** 对 Skill 的支持，作为「系统内置 Skill + 用户选择配置」特性（`features/skill-integration/`）的设计依据。
> 方法：本仓库 Gradle 依赖 `io.agentscope:agentscope-core:2.0.1`（含 `-sources.jar`，已读源码）与 `io.agentscope:agentscope-harness:2.0.1`（无 sources，用 `javap` 核对签名）。以官方最新文档为准。

## 一、核心概念

**Skill** = 一段 Markdown 指令集（`SKILL.md`：YAML frontmatter + markdown 正文），教 Agent「怎么用已有工具完成某类工作流」。它**不是工具**，不可直接调用——Agent 先经自动注册的 viewer tool 读取 skill 指令，再用已有工具（内置 `@Tool` / MCP 工具）去执行。

与 Tool 的本质区别：Tool 是「可执行动作」（有 JSON Schema、有 `callAsync`）；Skill 是「过程知识」（只进提示词，无独立执行体）。因此 Skill 的落地成本低、不新增工具调用面，适合把「多工具编排的工作流」固化为可复用能力。

## 二、core 包基础类型（`io.agentscope.core.skill.*`）

### 2.1 `AgentSkill`（skill 对象）

| 访问器 | 类型 | 说明 |
|---|---|---|
| `getName()` | String | 名称（来自 frontmatter `name`） |
| `getDescription()` | String | 描述（frontmatter `description`） |
| `getMetadata()` | `Map<String,Object>` | 全部 frontmatter 元数据 |
| `getSkillContent()` | String | markdown 正文（去 frontmatter） |
| `getResources()` | `Map<String,String>` | 附带资源文件（路径→内容） |
| `getSkillId()` / `getSource()` / `getOriginDir()` | — | 标识 / 来源 / 磁盘源目录 |

构造：`new AgentSkill(name, description, content, resources[, source])`，或 `AgentSkill.builder()` / `toBuilder()`。

### 2.2 `AgentSkillRepository`（skill 来源接口）

```java
public interface AgentSkillRepository extends AutoCloseable {
    AgentSkill getSkill(String name);
    List<String> getAllSkillNames();
    List<AgentSkill> getAllSkills();
    boolean save(List<AgentSkill> skills, boolean force);   // 只读实现返回 false
    boolean delete(String name);
    boolean skillExists(String name);
    AgentSkillRepositoryInfo getRepositoryInfo();           // type/location/isWritable
    String getSource();
    void setWriteable(boolean); boolean isWriteable();
}
```

内置实现：

| 实现 | 载体 | 只读 | 用途 |
|---|---|---|---|
| `FileSystemSkillRepository` | 磁盘目录 | 可写 | 开发期 / 用户工作区 |
| `ClasspathSkillRepository` | classpath 资源（jar 或 dev classpath） | 只读 | **系统内置 skill** |
| `WorkspaceSkillRepository`（harness） | harness 工作区 | — | 每用户工作区 skill |
| `RuntimeContextSkillRepository`（harness，接口） | 运行时按 `RuntimeContext` 解析 | — | 编程式注入 |

### 2.3 `ClasspathSkillRepository`（系统内置 skill 的载体）

```java
new ClasspathSkillRepository(String resourcePath)   // throws IOException
```

- 从 classpath 读父目录下所有 `*/SKILL.md`，目录约定：

```
resources/
└── skills/              ← 传 "skills"
    ├── skill-a/SKILL.md
    ├── skill-b/SKILL.md
    └── skill-c/SKILL.md
```

- **只读**：`save`/`delete`/`setWriteable` 均为 no-op（打 warn 日志）。
- **AutoCloseable + 共享 JAR FS**：jar 环境下用 `FileSystems.newFileSystem` 挂载虚拟 FS，静态 `SHARED_FILE_SYSTEMS` 按 URI 引用计数。**因此它必须单例共享、不可每次构建 Agent 时 new/close**，否则反复挂载/卸载 jar FS（bootJar 部署时尤其致命）。
- frontmatter 解析走 `SkillFileSystemHelper` + `MarkdownSkillParser`（下节）。

### 2.4 `SkillFilter`（启用/停用筛选）——用户选择的关键

```java
// Standalone（builder 级，完整策略）
SkillFilter.all();                 // 全部启用（无 filter 时的默认）
SkillFilter.none();                // 全部禁用
SkillFilter.only("a","b");         // 白名单：仅这几个
SkillFilter.except("a");           // 黑名单：除这几个外全启用

// Overlay（RuntimeContext 级，部分覆盖）
SkillFilter.enable("a");           // 追加启用（其余不变）
SkillFilter.disable("b");          // 追加禁用（其余不变）

boolean isAllowed(String name);    // 判定
SkillFilter overlay(SkillFilter runtimeOverlay);   // base ⊕ overlay 合并
```

语义要点（源码核实）：

- `only(...)` = `Mode.WHITELIST`，`except(...)` = `Mode.BLACKLIST`，`all/none` 是绝对策略；`enable/disable` 是 `OVERLAY_ENABLE/DISABLE`，仅作 per-call 覆盖用。
- `overlay(runtime)`：`runtime` 为 null 或非 overlay 时直接**替换** base；否则「被点名的 skill 用 overlay 决策，其余落到 base」。
- **默认（不配 filter）= `all()`**，即所有来源的所有 skill 全启用。

→ 「用户选择」落到 `SkillFilter.only(enabledCodes)`（opt-in）或 `SkillFilter.except(disabledCodes)`（opt-out）。

## 三、SKILL.md 格式

由 `MarkdownSkillParser`（SnakeYAML `SafeConstructor`，禁重复 key、嵌套深度/别名/codepoint 上限均有防护）解析：

```markdown
---
name: portfolio_review
description: 对用户持仓做一次组合诊断，产出结构化投研结论。
version: 1.0.0
# 任意自定义元数据（category / default_enabled / ...）会被保留进 metadata
---
# 正文：分步指令
1. 调用 ...
2. ...
```

- 无 frontmatter 时返回空 metadata、全文为 content；frontmatter 超 16KB 或 YAML 解析失败时降级为「简单 `key: value` 逐行提取」。
- `name` + `description` 是框架向 LLM 暴露的最小集合；其余元数据可在应用层自定义（如 `category` 分类、`default_enabled` 默认开关）。

## 四、harness 侧 Skill 子系统（`io.agentscope.harness.agent.skill.*`）

### 4.1 runtime（运行时装配）

| 类 | 职责 |
|---|---|
| `SkillRuntime` | 装配中枢：`currentCatalog(RuntimeContext)` 求当前 skill 目录、`loadTool()` 暴露 viewer tool、`prepareToolkit(Toolkit)`、`install(SkillCatalog, RuntimeContext, Toolkit)`、`renderPrompt(SkillCatalog, SkillFilter)` |
| `SkillCatalog` | `of(List<HarnessSkillEntry>)` / `empty()`；`get(id)` / `all()` / `ids()` / `isEmpty()` |
| `HarnessSkillEntry` | record `(AgentSkill skill, SkillResources lazyResources, String filesRoot)` |
| `SkillPromptBuilder` | 把 skill 清单渲染进系统提示词（`DEFAULT_HEADER` + 名称/描述列表 + 代码执行说明） |
| `SkillLoadTool` | viewer tool（`TOOL_NAME`，等价 core 的 `load_skill_through_path`），参数 `skillId` + `path`（`SKILL.md` 或资源路径），返回内容并**激活该 skill 绑定的 tool group** |
| `SkillResources` / `EmptySkillResources` / `LazyResourceCapable` | skill 附带的资源文件懒加载 |

### 4.2 repository（来源）

- `WorkspaceSkillRepository`：构造 `(AbstractFilesystem, String skillsRelativeDir, Supplier<RuntimeContext>[, String][, boolean])`，从 harness 工作区加载 skill，随 `RuntimeContext.userId` 隔离。
- `RuntimeContextSkillRepository`（接口）：`getAllSkills(RuntimeContext)` —— **编程式 per-call 注入 skill 的扩展点**。

### 4.3 curator（skill 生命周期治理，本特性一期不用）

`skill/curator/` 下是 harness 的「skill 自治」能力：`SkillCurator`（从成功模式自动沉淀候选 skill）、`SkillPromoter`/`SkillPromotionGate`（晋升审批门控）、`SkillSecurityScanner`（候选 skill 安全扫描）、`AllowListFilter`/`CanaryFilter`/`EnvironmentFilter`（可见性过滤）、`SkillAuditLog`/`SkillUsageStore`（审计与用量）。配套 `SkillManageTool`/`ProposeSkillTool` 供 Agent 自建 skill。

> 这套是「让 Agent 自己写 skill」的机制，与本特性「系统内置 + 用户选择」无关。构建时**不**调 `enableSkillCurator`/`enableSkillManageTool`/`enableSkillPromotionGate` 即默认关闭。

## 五、`HarnessAgent` 的 skill 装配点

`HarnessAgent.Builder`（`io.agentscope.harness.agent.HarnessAgent$Builder`）中与 skill 相关的方法（javap 核实）：

```java
// 显式来源
skillRepository(AgentSkillRepository)
skillRepositories(List<AgentSkillRepository>)
projectGlobalSkillsDir(Path)                 // 项目级全局 skill 目录

// 筛选（用户选择）
skillFilter(SkillFilter)
enableSkills(String...)                      // 便捷封装
disableSkills(String...)
skillsEnabled(boolean)                       // 总开关

// 收敛 skill 面
disableDefaultWorkspaceSkills()              // 关掉工作区默认 skill
disableDynamicSkills()                       // 关掉 curator 动态 skill

// 自治（本特性不用）
enableSkillManageTool(SkillManageConfig / boolean)
enableSkillCurator(SkillCuratorConfig)
enableSkillPromotionGate(SkillPromotionGate, SkillVisibilityFilter)
```

`HarnessAgent` 实例侧：`getSkillRepositories()` 返回生效的 skill 来源列表；`getSkillUsageStore()` / `runCuratorOnce()` / `promoteSkill(...)` / `queryAudit(...)` 属 curator 治理面。

关键结论：

1. **「系统内置」** → 单例 `ClasspathSkillRepository("skills")` + `.skillRepository(repo)`。
2. **「用户选择」** → `.skillFilter(SkillFilter.only(enabled))` 或 `.except(disabled)`。
3. **「技能面可预期」** → 显式 `.disableDefaultWorkspaceSkills().disableDynamicSkills()`，避免 harness 自动把工作区/动态 skill 混入。
4. harness 的 `DynamicSkillMiddleware`（core 侧）在每次 `call()` 重建 skill 提示词与 tool group，配置变更**下次调用即生效**（与 MCP 的「每请求现建 Agent」模型天然契合）。

## 六、与本项目结合点

- 本项目已用 `HarnessAgent`（`HarnessAgentFactory.build(userId)` 每请求现建），MCP 特性已确立「系统内置目录 + 用户选择 + 每请求按用户装配」的范式。
- Skill 是同一范式的第二个落点：目录在 classpath（非 DB），用户选择在 DB（`skill_user_config`），装配走 `SkillFilter`。
- 与 MCP 的差异：MCP 目录在 DB（因 provider 有 URL/token 等运行期可变属性）；Skill 目录天然在 classpath（内容静态、只读），DB 只存用户选择，避免「DB 目录 vs classpath 内容」双源不一致。

## 参考

- 官方 Tool 文档的 Skill 章节（`tool.md`）：Skill 注册、`load_skill_through_path`、`SkillToolGroup` 按需披露工具。
- 本目录 `overview.md`（AgentStateStore）、`agent.md`、`middleware.md` 关联阅读。
