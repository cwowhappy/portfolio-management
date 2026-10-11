# AgentScope Java 版本演进调研

日期：2026-10-11
分支：`main`
性质：依赖版本调研——评估 AgentScope Java 自本项目锁定的 2.0.3 以来的版本演进与升级路径
状态：调研完成，待评审

---

## 一、调研背景与目的

本项目（A 股投研对话助手）后端在 `backend/build.gradle` 中使用 AgentScope Java **2.0.3** 的四个构件：

- `io.agentscope:agentscope-core:2.0.3`
- `io.agentscope:agentscope-extensions-model-openai:2.0.3`（DeepSeek 走 OpenAI 兼容协议）
- `io.agentscope:agentscope-agui-spring-boot-starter:2.0.3`（AG-UI 协议 SSE 流式输出）
- `io.agentscope:agentscope-harness:2.0.3`

项目对框架的关键使用面（本次逐一对照核实）：

| 使用面 | 项目落点 |
|---|---|
| 按用户构建 HarnessAgent | `HarnessAgentFactory.build(userId)`：skillFilter 过滤、`JsonFileAgentStateStore` 状态存储、CompactionConfig 上下文压缩、MemoryConfig 长期记忆、Hook/Middleware 注入 |
| 服务端会话记忆 | `agentscope.agui.server-side-memory: true`（`application.yml`） |
| AG-UI 事件序列化 | 自定义 `AguiEventNonNullCodec` / `AguiWireJsonConfig`（Jackson 2 剥 null，规避前端 zod 拒收） |
| RuntimeContext 注入 | `InvestAguiRuntimeContextResolver`（web 层，从 SecurityContext 读 userId） |
| MCP 工具集成 | `UserToolkitFactory` 装配内置 `@Tool` + MCP 工具（`McpClientPool`），写工具触发 HITL 审批（ADR-0010） |
| 内置 Skill | `src/main/resources/skills/` + `ClasspathSkillRepository` |
| 可观测 | `TrustAgentHook` / `ObservabilityAgentHook` / `ReportUsageHook`（均实现 `Hook, RuntimeContextAware`） |

本报告调研 2.0.3 之后的版本变更，评估升级收益、改造成本与兼容性风险。

## 二、版本现状

### 2.1 版本清单（Maven Central 主源）

来源：[repo1.maven.org maven-metadata.xml](https://repo1.maven.org/maven2/io/agentscope/agentscope-core/maven-metadata.xml)（`io.agentscope` 组下 `agentscope-core`，其余三构件 metadata 一并核验）。

`agentscope-core` 全量版本（30 个）：0.1.0 → 0.2.0/0.2.1 → 1.0.0~1.0.12 → 1.1.0-RC1/RC2 → 2.0.0-RC1~RC5 → 2.0.0 → 2.0.1 → 2.0.2 → 2.0.2-subagent-bugfix → **2.0.3 → 2.0.4 → 2.1.0-BETA1**。

**2.0.3 之后共两个版本**，同日发布：

| 版本 | 性质 | GitHub Release 时间（UTC） | 来源 |
|---|---|---|---|
| **2.0.4** | 最新稳定版 | 2026-10-08 03:40 | [release v2.0.4](https://github.com/agentscope-ai/agentscope-java/releases/tag/v2.0.4) |
| **2.1.0-BETA1** | 预发布（prerelease） | 2026-10-08 12:42 | [release v2.1.0-BETA1](https://github.com/agentscope-ai/agentscope-java/releases/tag/v2.1.0-BETA1) |

repo1 目录时间戳与 GitHub release 时间互相印证（2.0.3=2026-09-07、2.0.4=2026-10-08 03:41、2.1.0-BETA1=2026-10-08 12:31）。

四构件版本起点差异（均含 2.0.4 与 2.1.0-BETA1）：`agentscope-agui-spring-boot-starter` 自 1.0.3 起持续发布；`agentscope-harness` 自 1.1.0-RC1 起；`agentscope-extensions-model-openai` 自 2.0.0-RC5 起。

发布节奏参考：2.0.0（2026-07-10）→ 2.0.1（08-06）→ 2.0.2（09-03）→ 2.0.3（09-07）→ 2.0.4（10-08），约 1~2 个月一个 minor/patch。`io.agentscope` 组当前已扩展至百余个构件（含 channel/sandbox/RAG/runtime/service 全家桶，见 [组目录](https://repo1.maven.org/maven2/io/agentscope/)）。

### 2.2 官方文档站

文档站已迁移 Mintlify（#3081/#3082/#3276），现行结构 `java.agentscope.io/v2/{zh,en}`，双轨定位：**Harness SDK**（嵌入自研应用，本项目用法）与 **AgentScope Service**（自托管 Agent-as-a-Service 平台，2.1.0-BETA1 的发行主体）（[官网首页](https://java.agentscope.io/v2/zh/intro)、[Service 文档](https://java.agentscope.io/v2/zh/service/index)）。

## 三、逐版本变更整理（2.0.3 之后）

### 3.1 2.0.4（2026-10-08，最新稳定版）

来源：[Release Notes v2.0.4](https://github.com/agentscope-ai/agentscope-java/releases/tag/v2.0.4)、[tag diff v2.0.3...v2.0.4](https://github.com/agentscope-ai/agentscope-java/compare/v2.0.3...v2.0.4)（关键条目已在 tag 源码逐条核实，标注 ※）。

#### 新增特性

**Core / 模型**

- **OpenAI Responses API 模块**（[#3078](https://github.com/agentscope-ai/agentscope-java/pull/3078)）——※ 落地为新构件 `agentscope-extensions-model-openai-official`（Maven 首发版本即 2.0.4，[metadata](https://repo1.maven.org/maven2/io/agentscope/agentscope-extensions-model-openai-official/maven-metadata.xml)），与现有 `agentscope-extensions-model-openai`（Chat Completions）并存。
- **`@Tool(returnDirect = true)` 短路推理循环**（[#2891](https://github.com/agentscope-ai/agentscope-java/pull/2891)）——※ `Tool.java` 新增 `boolean returnDirect() default false`，工具成功结果直接作为本轮最终回复，不再回喂模型。
- **ChatUsage 细分 token 指标**（[#3215](https://github.com/agentscope-ai/agentscope-java/pull/3215)）——※ 新增 `cacheCreationTokens` / `reasoningTokens` / `toolUsePromptTokens` 字段。
- **模型 failover 监听器**（[#3145](https://github.com/agentscope-ai/agentscope-java/pull/3145)）——※ `HarnessAgent.Builder.failoverListener(...)`。
- Anthropic bearer token 认证（[#3038](https://github.com/agentscope-ai/agentscope-java/pull/3038)）；Jev System One 客户端 + starter（[#3240](https://github.com/agentscope-ai/agentscope-java/pull/3240)）。

**Harness / 工具 / 存储**

- **`AgentRun`/`RunControl` 每次运行执行控制**（[#3279](https://github.com/agentscope-ai/agentscope-java/pull/3279)）——`prepareRun`/`prepareCall` 返回可取消句柄，唯一 runId、终态通知、立即取消与协作中断。
- **请求级工具配置与隔离**（[#3283](https://github.com/agentscope-ai/agentscope-java/pull/3283)）——工具可见性、流式回调、会话 skill 资源按调用隔离。
- ※ Builder 新增 `disableWebTools` / `webHttpClient`（可禁用内置 web 工具并注入 HTTP client，[#3075](https://github.com/agentscope-ai/agentscope-java/pull/3075)/[#3103](https://github.com/agentscope-ai/agentscope-java/pull/3103)）。
- **MCP metadata 按server/按工具透传**（[#3255](https://github.com/agentscope-ai/agentscope-java/pull/3255)）——※ `McpClientBuilder.propagateMeta(boolean)`。
- MongoDB 存储扩展（[#2698](https://github.com/agentscope-ai/agentscope-java/pull/2698)）、E2B 快照自动清理（[#2555](https://github.com/agentscope-ai/agentscope-java/pull/2555)）、`sharedLocalWorkspace`（[#3247](https://github.com/agentscope-ai/agentscope-java/pull/3247)）。

**Service / Channels**：重设计的 AgentScope Service（[#3080](https://github.com/agentscope-ai/agentscope-java/pull/3080)）；微信 iLink 个人通道（[#3184](https://github.com/agentscope-ai/agentscope-java/pull/3184)）；钉钉 HTTP 回调模式（[#3177](https://github.com/agentscope-ai/agentscope-java/pull/3177)）；channel token 可插拔存储与入站去重（[#3182](https://github.com/agentscope-ai/agentscope-java/pull/3182)/[#3175](https://github.com/agentscope-ai/agentscope-java/pull/3175)）。

#### 破坏性变更（Refactored）

- ※ **删除 `io.agentscope.core.hook.RuntimeContextAware` 接口**，同时移除 `activeRc`、agent 级 `getRuntimeContext()`（[#3279](https://github.com/agentscope-ai/agentscope-java/pull/3279)，PR 描述明确「Middleware and tool parameters are the context access points」）。该接口自 2.0.0 起 `@Deprecated(forRemoval=true)`，2.0.4 兑现移除。
- 移除 Harness repository context suppliers 与无上下文的 skill-promotion 重载（[#3279](https://github.com/agentscope-ai/agentscope-java/pull/3279)）。
- **删除公共类型**：`RegisteredSkill`、`ToolkitAware`、`agui.model.ToolMergeMode`、`ToolInjection`；**删除成员**：`setInternalChunkCallback`、`syncToolGroupStates`、`isSkillActive`、`replaceMetaActiveGroups`（[#3283](https://github.com/agentscope-ai/agentscope-java/pull/3283)）。
- ※ **`ToolMergeMode` 迁包 + 枚举重命名**：`io.agentscope.core.agui.model.ToolMergeMode` → `io.agentscope.core.tool.ToolMergeMode`；`FRONTEND_ONLY`→`EXTERNAL_ONLY`、`MERGE_FRONTEND_PRIORITY`→`MERGE_EXTERNAL_PRIORITY`；starter `AguiProperties.defaultToolMergeMode` 默认值同步替换（starter diff 实证）。语义不变（前端=external 术语统一），仅编译层面重命名。
- **`HttpTransportConfig#getHttpVersion()` 默认返回 `null`**（原 `HTTP_2`）——明文 HTTP 调用方需自行判空防 NPE，`https://` 不受影响（[#3214](https://github.com/agentscope-ai/agentscope-java/pull/3214)）。
- ※ `DashScopeMessage`/`OpenAIMessage` 移除 `cacheControl`（getter/setter/builder），消息级 `cache_control` 不再发出。
- 移除未用的 message role 参数（[#3045](https://github.com/agentscope-ai/agentscope-java/pull/3045)）；`Version.VERSION` 改由 Maven 版本派生（[#3087](https://github.com/agentscope-ai/agentscope-java/pull/3087)）。

#### 重要修复（与本项目相关者优先）

- **并发隔离**：并发调用的上下文隔离与单执行取消（[#3279](https://github.com/agentscope-ai/agentscope-java/pull/3279)）；跨会话工具/skill 干扰防护（[#3283](https://github.com/agentscope-ai/agentscope-java/pull/3283)）。
- **AG-UI 权限（HITL）链路**：权限 resume 后保留 AG-UI 工具结果（[#3100](https://github.com/agentscope-ai/agentscope-java/pull/3100)）；不连续文本段唯一 ID（[#3010](https://github.com/agentscope-ai/agentscope-java/pull/3010)）；终态信号前清除 active-run 标记（[#3109](https://github.com/agentscope-ai/agentscope-java/pull/3109)）。
- **HITL 拒绝语义**：被拒绝的确认调用也发 tool-result 事件，interrupt schema 新增 `reason`（自定义拒绝理由）（[#3104](https://github.com/agentscope-ai/agentscope-java/pull/3104)/[#2546](https://github.com/agentscope-ai/agentscope-java/pull/2546)）——※ `PermissionConfirmEventConverter` diff 实证。
- 流式推理详情跨 chunk 保留、thought signatures JSON 往返（[#3128](https://github.com/agentscope-ai/agentscope-java/pull/3128)/[#2910](https://github.com/agentscope-ai/agentscope-java/pull/2910)/[#2914](https://github.com/agentscope-ai/agentscope-java/pull/2914)）；空 agent 结果后持久化状态（[#3049](https://github.com/agentscope-ai/agentscope-java/pull/3049)）；JSON Schema 生成线程安全（[#2796](https://github.com/agentscope-ai/agentscope-java/pull/2796)）；JDBC 状态存储三连修（[#3165](https://github.com/agentscope-ai/agentscope-java/pull/3165)/[#3220](https://github.com/agentscope-ai/agentscope-java/pull/3220)/[#3236](https://github.com/agentscope-ai/agentscope-java/pull/3236)，本项目用 JsonFile 状态存储，不直接相关）。
- ※ starter 改用模块化 core/AG-UI 依赖（[#3156](https://github.com/agentscope-ai/agentscope-java/pull/3156)）——starter POM 中 `agentscope-extensions-agui` 从 provided/optional 转 compile。
- 文档：`CompactionConfig.keepTokens` 默认值文档修正（[#3144](https://github.com/agentscope-ai/agentscope-java/pull/3144)）。

### 3.2 2.1.0-BETA1（2026-10-08，预发布）

官方 Release Notes 仅 4 行：主体是 **AgentScope Service 预发布发行物**（Docker 镜像、Compose/Helm 安装包、CLI 与 Runtime Host），指向[官方中文文档](https://java.agentscope.io/v2/zh/service/index)。**库构件（core/harness/model-openai/agui-starter）随同发布了 2.1.0-BETA1 版本**（Maven metadata 实证），其代码变更官方未单独整理 changelog，以下从 [tag diff v2.0.4...v2.1.0-BETA1](https://github.com/agentscope-ai/agentscope-java/compare/v2.0.4...v2.1.0-BETA1)（44 个 commit）提取归纳，关键条目源码核实（※）：

**对本项目有直接价值：**

- ※ **DeepSeek thinking 模式修复**：`reasoning_content` 必须为**所有** assistant 轮全量回传（此前仅保留带工具调用的段），否则 DeepSeek API 返回 HTTP 400（[#3272](https://github.com/agentscope-ai/agentscope-java/pull/3272)，`DeepSeekFormatter` javadoc 明确引用 [DeepSeek 官方文档](https://api-docs.deepseek.com/guides/thinking_mode#tool-calls)）。
- ※ DeepSeek `deepseek-flash` 上下文窗口表补录（[#3380](https://github.com/agentscope-ai/agentscope-java/pull/3380)）。
- Harness 记忆与会话检索可选**多关键词匹配模式**（[#3062](https://github.com/agentscope-ai/agentscope-java/pull/3062)）。
- ※ `ToolEmitter.getToolCallId()` 暴露工具调用 ID（[#2720](https://github.com/agentscope-ai/agentscope-java/pull/2720)）。
- 流式传输错误重试（`ModelHttpException` 无状态码包装场景，[#3058](https://github.com/agentscope-ai/agentscope-java/pull/3058)）。
- SKILL.md 支持 UTF-8 BOM（[#3385](https://github.com/agentscope-ai/agentscope-java/pull/3385)）。

**框架演进（间接相关）：**

- ※ **Harness context 重设计 + Service 会话与 JEV 统一**（[#3441](https://github.com/agentscope-ai/agentscope-java/pull/3441)）——harness `memory/session` 包大幅重构（`SessionEntry`/`SessionTree`/`SessionTranscriptWriter` 删除约 1500 行）；※ Builder 移除 `transcriptStore`/`transcriptTenant`，新增 `sessionLogStore`/`sessionHistoryMode`/`legacySessionHistory`/`contextPolicy`/`contextSelectionPolicy`/`taskContext`/`instruction` 等；※ `MemoryConfig` 移除 `sessionRetentionDays`，`CompactionConfig` 移除 `offloadBeforeCompact`、新增可插拔 `strategy(ConversationCompactionStrategy)`。
- middleware 扩展点增强：`activePoints()` 声明（[#3371](https://github.com/agentscope-ai/agentscope-java/pull/3371)）、`onAgentStateReady`（[#3370](https://github.com/agentscope-ai/agentscope-java/pull/3370)）。
- `RuntimeContext` 每调用 runId 并与 AgentRun 对齐（[#3290](https://github.com/agentscope-ai/agentscope-java/pull/3290)）；`HarnessAgent.Builder.conflictPolicy` 暴露（[#3263](https://github.com/agentscope-ai/agentscope-java/pull/3263)）。
- OpenAI `OPENAI_BASE_URL` 环境变量支持（[#3351](https://github.com/agentscope-ai/agentscope-java/pull/3351)）；app 自持 OpenTelemetry SDK（[#3250](https://github.com/agentscope-ai/agentscope-java/pull/3250)）；Anthropic/Gemini server tools（[#2489](https://github.com/agentscope-ai/agentscope-java/pull/2489)/[#2767](https://github.com/agentscope-ai/agentscope-java/pull/2767)）；JDBC 方言构建器整合（[#3309](https://github.com/agentscope-ai/agentscope-java/pull/3309)）；依赖升级（anthropic-java 2.52→2.67、openai-java 4.52→4.73、otel 1.61→1.66 等）。

## 四、对本项目的影响评估

以下结论均以**三份 tag 源码（v2.0.3 / v2.0.4 / v2.1.0-BETA1）逐一对照项目 59 个 `io.agentscope` import 与全部 builder 调用**核验为依据。

### 4.1 兼容性基线（好消息）

| 维度 | 结论 | 证据 |
|---|---|---|
| **Jackson** | 三版均锁 **Jackson 2.21.1**（`agentscope-dependencies-bom` 的 `jackson.version`），Jackson 2 → Jackson 3 迁移未发生，项目的 Jackson 2 锁定与 `AguiEventNonNullCodec` 前提不变 | ※ 三 tag BOM 逐版比对 |
| **Spring Boot** | agui starter 三版 POM 除版本号外**完全一致**，均直依 Spring Boot **4.0.3**（与本项目 Boot 4.0.x 对齐） | ※ 2.0.3/2.0.4 发布 POM diff |
| **AguiEvent** | 三版**字节级相同**（git blob 哈希一致），`JsonCodec` 接口方法集三版相同——`AguiEventNonNullCodec`/`AguiWireJsonConfig` 零改动 | ※ blob 哈希 76dc5e0d 三版一致 |
| **server-side-memory** | 属性存续（`AguiProperties.isServerSideMemory`），`AguiMvcController` 类名存续（日志配置有效） | ※ v2.0.4 源码 |
| **Resolver** | `AguiRuntimeContextResolver.resolve(AguiRuntimeContextRequest<?>)` 签名三版未变——`InvestAguiRuntimeContextResolver` 零改动 | ※ 三版源码 |
| **Builder 面向** | `HarnessAgentFactory` 所用 15 个 builder 方法（name/sysPrompt/model/toolkit/skillRepository/skillFilter/disableDefaultWorkspaceSkills/disableDynamicSkills/workspace/stateStore/compaction/memory/hook/middleware/maxIters）在 2.0.4 与 2.1.0-BETA1 **全部存续** | ※ 三版 Builder 方法集 diff |
| **工具/MCP 面** | `Toolkit`/`McpClientWrapper`/`McpTool`/`McpClientBuilder`/`Tool`/`ToolEmitter`/`ToolParam` 等全部存续（MCP 仅新增 `propagateMeta`） | ※ 逐类 cat-file 核验 |

### 4.2 升级 2.0.4 的碰撞点与改造清单

**唯一硬碰撞**：`io.agentscope.core.hook.RuntimeContextAware` 在 2.0.4 被删除。项目三个 Hook 类受影响：

- `backend/src/main/java/com/portfolio/invest/agent/trust/TrustAgentHook.java`（MS-29 B5）
- `backend/src/main/java/com/portfolio/invest/agent/observability/ObservabilityAgentHook.java`（MS-30 B6）
- `backend/src/main/java/com/portfolio/invest/agent/report/ReportUsageHook.java`（MS-32 B8）

三者均 `implements Hook, RuntimeContextAware` 并在 `setRuntimeContext(RuntimeContext)` 注入点缓存上下文（`ObservabilityAgentHook` 用它存 nanoTime 起点等回合级数据）。迁移路径官方已给：**改用 `MiddlewareBase`（上下文经 middleware/工具参数显式传递）**——项目已有同款先例 `TrustWireMessageIdMiddleware`，改造模式可参照；或评估 Hook 事件是否已携带所需数据。

**确认无影响的破坏性变更**（已逐项对照项目代码）：

- `ToolMergeMode` 迁包/重命名——项目**未显式配置** `default-tool-merge-mode`，用默认值且语义不变；
- `HttpTransportConfig#getHttpVersion()` 默认 null——DeepSeek 走 `https://api.deepseek.com`，官方明示 https 不受影响；
- `OpenAIMessage.cacheControl` 移除——项目未用（DeepSeek 不支持该参数）；
- 删除的 `RegisteredSkill`/`ToolkitAware`/`ToolInjection`/`isSkillActive` 等——项目 import 清单中均未出现。

### 4.3 升级收益（按对本项目的相关度排序）

1. **HITL/AG-UI 审批链路修复**（2.0.4 #3100/#3104/#3010/#3109）：权限 resume 后工具结果保留、拒绝也发 tool-result 事件并支持 `reason`——直接作用于 ADR-0010 审批卡片链路的边角缺陷。
2. **DeepSeek 流式推理保留修复**（2.0.4 #3128/#2910）：跨 chunk reasoning 保留与 JSON 往返，`enable-reasoning: true` 场景更稳。
3. **并发隔离**（2.0.4 #3279/#3283）：共享 agent 的跨调用上下文泄漏与跨会话工具/skill 干扰修复——本项目虽按请求构建 agent，但同一用户并发多轮/多入口（AG-UI + 飞书 `FeishuAgentInvoker`）仍受益。
4. **观测增强**（2.0.4）：`ChatUsage` 细分 token（reasoningTokens 等）可充实 MS-30 轮级观测；`ToolEmitter.getToolCallId()`（2.1.0）可关联工具调用。
5. **2.1.0-BETA1 的 DeepSeek thinking 修复**（#3272）：若启用 thinking 模式 + 工具，旧版会撞 HTTP 400——但 2.1.0-BETA1 是预发布，建议等 2.1.0 正式版。

### 4.4 风险

- 2.0.4：改造面小而集中（三个 Hook 迁移），但 Hook 是观测/可信/研报三域的埋点，迁移后须回归 `make test-backend` + smoke（AG-UI 真流 + 审批卡片 e2e）。
- 2.1.0-BETA1：**不建议生产采用**——预发布标记、Harness memory/session 包重构幅度大（约 -1500 行删改）、且官方未发布库构件 changelog；其价值点（DeepSeek reasoning_content、多关键词记忆检索）等 2.1.0 正式版一并拿。

## 五、结论与建议

1. **建议升级至 2.0.4**（最新稳定版）：收益集中在 HITL 审批链路修复、DeepSeek 流式稳定性与并发隔离，且与项目的 Spring Boot 4 / Jackson 2 约束完全兼容。改造点唯一且明确：三个 `RuntimeContextAware` Hook 迁移到 `MiddlewareBase`（或事件携带上下文），迁移模式可参照仓内 `TrustWireMessageIdMiddleware`。升级后重点回归：HITL 审批 e2e、观测钩子数据完整性、AG-UI SSE 流式。
2. **不建议现阶段采用 2.1.0-BETA1**：预发布 + Harness 记忆/会话包大重构。持续跟踪 2.1.0 正式版（按 1~2 月节奏预计 2026 年内），届时重点评估 DeepSeek thinking 修复（#3272，对 thinking + 工具场景是刚需）与记忆多关键词检索（#3062）。
3. **Jackson 2 锁定短期无解耦压力**：三版 BOM 均为 Jackson 2.21.1，`AguiEventNonNullCodec` 的回收条件（上游改发缺省或前端收 null）在 2.0.4/2.1.0-BETA1 均未出现，继续保留。

## 附：可信度说明与调研方法

- 主源：Maven Central（repo1 maven-metadata.xml + 目录时间戳）、GitHub 官方仓库（releases API、tag diff、PR 描述）、官方文档站（java.agentscope.io，curl 直拉 markdown 源）。所有「※」条目经三份 tag（v2.0.3/v2.0.4/v2.1.0-BETA1）源码逐条核实，非仅凭 release notes 转述。
- 限制一：2.1.0-BETA1 官方 release notes 仅 4 行（Service 发行物说明），**库构件变更无官方逐条清单**，第三节 3.2 由 tag 间 44 个 commit 归纳（commit 即主源，可信，但非官方整理口径）。
- 限制二：search.maven.org solr API 对 `g:io.agentscope` 返回 0 结果（索引异常），版本清单改以 repo1 maven-metadata.xml 为准，并与 GitHub releases 交叉验证一致。
- 限制三：2.0.4 与 2.1.0-BETA1 同日发布（2026-10-08），从 commit 历史看 main 分支已进入 `2.0.5-SNAPSHOT` 与 2.1.0 双轨，后续 2.0.5 patch 与 2.1.0 正式版可能相继出现，本报告结论有效期建议以一个月为限。
