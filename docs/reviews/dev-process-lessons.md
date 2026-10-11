# 特性开发流程与经验沉淀

> 滚动文档：每次特性交付复盘后滚动更新。目的不是复述各特性复盘的交付结论（结论在 `features/<feature>/08-复盘总结/`），而是沉淀**特性开发的 canonical 流程形态**与**跨特性复发的开发经验**。代码审查方法论与审查问题清单归 [code-review-lessons.md](code-review-lessons.md)，本文与其互补、按链接互引，不重复其内容。
>
> 建档：2026-10-11（MS-31 交付复盘后首建，材料覆盖 MS-06~MS-31 全量复盘与验证记录）。

---

## 一、定位与来源

- **本文管什么**：特性从立项到复盘的全流程标准形态（供下次特性开发照做）、流程机制的演进背景、跨特性教训（≥2 特性复发的才进「开发经验」章，单特性的留在该特性复盘）。
- **不管什么**：代码审查方法论与审查发现问题模式（→ [code-review-lessons.md](code-review-lessons.md)）；单特性交付细节（→ 各特性复盘）；产品功能现状（→ [docs/function/](../function/)）。
- **材料来源**：2026-09-03（MS-06）至 2026-10-11（MS-31）共 17 个特性目录 19 份复盘总结（其中 feishu-messaging、account-email 两份为 2026-10-11 补建——交付当时未回填，依 git/PR 证据回溯整理，非交付时点一手记录）+ 4 份 P0 验证记录，以及用户工作偏好（严格 TDD + grilling + subagent-driven）。代号索引：

| 代号 | 特性 | 复盘/验证记录 |
|---|---|---|
| MS-06 | journal 投资决策记录 | [features/journal/08-复盘总结/复盘总结.md](../../features/journal/08-复盘总结/复盘总结.md) |
| MS-08 | rebalancing-screening | [features/rebalancing-screening/08-复盘总结/复盘总结.md](../../features/rebalancing-screening/08-复盘总结/复盘总结.md) |
| MS-09 | industry-listed-research | [features/industry-listed-research/08-复盘总结/复盘总结.md](../../features/industry-listed-research/08-复盘总结/复盘总结.md) |
| MS-10 | industry-unlisted-research | [features/industry-unlisted-research/08-复盘总结/复盘总结.md](../../features/industry-unlisted-research/08-复盘总结/复盘总结.md) |
| MS-11 | wiki-knowledge-base | [features/wiki-knowledge-base/08-复盘总结/复盘总结.md](../../features/wiki-knowledge-base/08-复盘总结/复盘总结.md) |
| MS-12 | ai-research-tools-phase2 | [features/ai-research-tools-phase2/08-复盘总结/复盘总结.md](../../features/ai-research-tools-phase2/08-复盘总结/复盘总结.md) |
| MS-13 | risk-attribution-backtest | [features/risk-attribution-backtest/08-复盘总结/复盘总结.md](../../features/risk-attribution-backtest/08-复盘总结/复盘总结.md) |
| MS-14 | industry-tools-extension | [features/industry-tools-extension/08-复盘总结/复盘总结.md](../../features/industry-tools-extension/08-复盘总结/复盘总结.md) |
| MS-16/17/18 | mcp / skill / mcp-hitl | [mcp 验证记录](../../features/mcp-integration/03-实施计划/验证记录.md) · [skill 验证记录](../../features/skill-integration/03-实施计划/验证记录.md) · [HITL 验证记录](../../features/mcp-hitl/03-实施计划/验证记录.md) |
| MS-20~23 | research-intelligence | [features/research-intelligence/08-复盘总结/复盘总结.md](../../features/research-intelligence/08-复盘总结/复盘总结.md) |
| MS-24~27 | invest-sop | [features/invest-sop/08-复盘总结/复盘总结.md](../../features/invest-sop/08-复盘总结/复盘总结.md) |
| P1-10 | mcp-token-encryption | [features/mcp-token-encryption/08-复盘总结/复盘总结.md](../../features/mcp-token-encryption/08-复盘总结/复盘总结.md) |
| MS-28 | code-review-2026-10 两批 | [第一批](../../features/code-review-2026-10/08-复盘总结/复盘总结.md) · [第二批](../../features/code-review-2026-10/08-复盘总结/P2-第二批复盘总结.md) |
| MS-29 | trust-provenance | [features/trust-provenance/08-复盘总结/复盘总结.md](../../features/trust-provenance/08-复盘总结/复盘总结.md) |
| MS-30 | eval-observability | [features/eval-observability/08-复盘总结/复盘总结.md](../../features/eval-observability/08-复盘总结/复盘总结.md) |
| MS-31 | memory-personalization | [features/memory-personalization/08-复盘总结/复盘总结.md](../../features/memory-personalization/08-复盘总结/复盘总结.md) |
| MS-32 | deep-research | [features/deep-research/08-复盘总结/复盘总结.md](../../features/deep-research/08-复盘总结/复盘总结.md) |
| 跨 MS-15/M14/M03 | feishu-messaging | [复盘总结](../../features/feishu-messaging/08-复盘总结/复盘总结.md)（2026-10-11 补建）· [P0 验证记录](../../features/feishu-messaging/03-实施计划/P0-验证记录.md) |
| MS-15 | account-email | [复盘总结](../../features/account-email/08-复盘总结/复盘总结.md)（2026-10-11 补建） |

> 更早期（MS-03~MS-05、MS-19 等）的交付复盘以「轮」形式记在 [code-review-lessons.md](code-review-lessons.md)，本文按需引用。特性目录规范（01 需求 / 02 设计 / 03 计划 / 08 复盘 / 09 调研的固定编号与交付回填 checklist）见 [features/README.md](../../features/README.md)。

**交付记录的形态变迁**：复盘机制建立前（2026-09 上旬，MS-16~18/飞书），P0 技术验证以 `03-实施计划/验证记录.md` 形态留档（源码核实结论表 + 真握手抓包 + 结论速览）；2026-09-17 固定编号后，「验证记录/调研报告」与「复盘总结」分层归位（09-调研报告 + 08-复盘总结），验证记录形态延续至今。两条脉络在本文中同等对待：验证记录承载「事前证实」，复盘承载「事后偏差」（feishu-messaging 与 account-email 的复盘为 2026-10-11 补建，交付当时欠账）。

---

## 二、特性开发全流程（当前标准形态）

> 以 MS-29~31（阶段三）为 canonical；各环节演进背景见第三节。流程总览：**立项侦察 → 需求规格（grilling）→ 设计规格（file:line 锚定）→ P0 探测/验证先行 → 实施计划（任务级+事实锚点）→ 单分支 subagent-driven TDD 执行（逐任务审查）→ 全量收口门 → 独立终审+修复波 → PR 合并 → 交付回填+复盘 → 文档同步（链接门禁）**。

| 环节 | 产出物（落盘位置） | 质量门 | 主证据特性 |
|---|---|---|---|
| 立项侦察 | 侦察报告（特性目录） | 机制假设字节码/实机级证实 | MS-29 |
| 需求规格 | `01-需求规格/需求规格说明.md`（裁决编号留痕） | 范围收窄显式标注；YAGNI 与实现范围一致 | invest-sop、MS-14 |
| 设计规格 | `02-设计规格/设计规格说明.md`（file:line 锚定） | 双实证（字节码+集成测试） | MS-29/30 |
| P0 验证 | `09-调研报告/` 或 `03-实施计划/验证记录.md` | 结论可复现；是计划的输入 | MS-09/13/16/17/18 |
| 实施计划 | `03-实施计划/P<N>-*.md`（任务级） | 代码样例对照真实代码（四维核实清单） | MS-06/11/12/13 |
| 执行 | 单分支 commits + `.superpowers` 台账 | 逐任务 TDD 红绿 + 独立审查双签 | 全部 |
| 收口 | 全量 `make test` EXIT=0 + e2e fresh build | 覆盖门槛 ≥80% 三端 | 全部 |
| 终审 | 终审报告（会话产物）→ 修复波 + 定向复审 | 跨任务视角 + 跨文档对照 | MS-12/28/29/31 |
| 回填 | `08-复盘总结/复盘总结.md` + 六步 checklist | 计划偏差清单逐条留痕 | 全部 |

### 2.1 立项与侦察（阶段三新增的固定前置）

- **做什么**：规划文档（roadmap）定里程碑后，写需求规格前先派多路只读侦察（MS-29 七路：AG-UI 通道/持久化/工具拦截/前端挂点等；MS-30/31 各四路），核实机制假设与既有代码现状。
- **产出**：侦察报告落特性目录，**结论直接进设计规格的依据**。
- **价值证据**：MS-29 侦察在写设计前推翻核心前提（TextMessage 无 metadata 字段，规划点名的扩展路径不存在）、抓到 PUT 全删重插会冲掉后端直写——「设计规格因此没有一行凭记忆写的代码引用」。机制假设必须字节码/实机级证实（MS-29 B5 锚点改判：设计裁定时证据不足 → 实现探针推翻 → 改判留痕）。
- **拍板机制**（阶段三惯例）：悬空项（技术选型/口径收窄）在规划或交付注记中列「待拍板」清单，用户逐项裁决后以跟进 PR 收口（MS-30 拍板三件：token 接线/抽取轨并入/ClassOrderer）。

### 2.2 需求规格（01-需求规格）

- **做什么**：brainstorming + grilling 质询澄清，落 `需求规格说明.md`：背景目标 + 澄清决策汇总（每条裁决编号留痕，D#）+ 功能需求 + NFR + 验收标准 + YAGNI。
- **质量门**：范围裁定必须显式记录——计划从 SRS 收窄任何范围都要标注「延后」，否则就是静默范围收缩（MS-03 教训，见 [code-review-lessons.md](code-review-lessons.md) 2026-08-30 轮）；范围先裁决再动工（MS-14 grilling 三轮把「4 点」收敛为「3 点交付 + 1 点随 MS-10」，阻塞项不硬等）。
- **常见坑**：验收标准须与 roadmap 原文逐条对账（MS-09/11/13 复盘均设「验收标准对账」节）；「明确不做 YAGNI」清单必须与实现范围一致。
- **价值证据**：invest-sop 18 问裁决（D9~D23）让 31 个任务简报全部携带明确决策，实现者零架构级返工。

### 2.3 设计规格（02-设计规格）

- **做什么**：架构/数据模型/接口契约/测试策略，**代码引用须 file:line 锚定**——事实锚点让低档模型也能做转录级任务而无一次架构误判（invest-sop）。
- **质量门**：设计稿的机制假设要可证实——「jar 字节码 + 集成测试」双证实推翻设计稿写法并回写规格（MS-30 schema 双段 `currentSchema=eval_schema,public`，单段下 pg_trgm 算子类必炸）。
- **常见坑**：设计稿 SQL/数值口径笔误靠「同一口径双实现一致性测试」拦截（MS-09 pb 分母条件加权 vs calc 无条件 Σ市值，对拍红灯当场逮住）；设计偏离在执行期发现时按 Ruling 台账回写规格消歧（MS-31 B2/B6 两处计划-规格矛盾均回填）。

### 2.4 P0 探测/技术验证先行

- **做什么**：凡外部依赖（数据源/协议/框架装配点/真实服务），先跑最小实证再写计划。四类先例：
  - **外部接口字段语义实测校准**：MS-09 用真实 token 探测 `income`，推翻计划三处先验（单位为元非万元、多行来自 update_flag、start_date 是公告日窗口）——「先验三处全错即证明」计划必须引用调研结论而非先验锚点。
  - **数据源可得性探测 + 降级路径实证 + 裁决落盘**：MS-13 主源被积分门槛挡住，先实证各预案（B 可得/C 返回空直接排除）再交用户裁决，裁决单独落盘 commit；MS-10 数据源替代裁决（天眼查出局，改公开流+人工策展）。
  - **框架装配点源码级核实**：MS-16/17 读 agentscope sources.jar 核实 bean 覆盖点/SkillFilter 语义/fat-jar 引用计数行为；MS-18 P0 真握手抓包勘误四条（RAW 透传/resume 契约错误/真实续跑形状/AG-UI 无法承载），推翻计划后回到设计环节重定方案再排 P1。
  - **真实链路冒烟**：飞书 P0 生产代码路径实发（含故意错 secret 验证降级路径 `code=19021` 实捕获）。
- **产出**：`09-调研报告/` 或 `03-实施计划/验证记录.md`，**是计划的输入而不是计划的附件**。
- **探针附录回填制**（MS-29 值得复用）：前置探针（B0）的结论写进实施计划附录、失败路径走既定回退——把 spike 的不确定性变成设计参数，而不是 spike 做完就丢。
- **常见坑**：探测结论本身也要可复现（MS-14 ISEXCHG 字段复测不可复现，当日勘误剔除，避免下游带幻影字段实现）；探查带错字段返回 0 行且无报错，勿据此误判权限缺失（tushare daily_basic 无 close 字段）；**会话内的 P0 报告要升格为 git 跟踪的验证记录**（mcp-hitl 补写先例，见经验 #37）。

### 2.5 实施计划（03-实施计划，P1/P2/… 分册）

- **做什么**：任务级计划，每 Task 含 Files / Interfaces 产销块 / TDD Steps（checkbox）/ Review Focus；册首带 Global Constraints 与**事实锚点**（实现者必读的既有代码 file:line，形态样例见 [P4-MS27-复盘闭环](../../features/invest-sop/03-实施计划/P4-MS27-复盘闭环.md)）。
- **质量门（计划核实清单，逐轮扩出）**：计划里的完整代码样例**必须对照真实代码库验证**——MS-06 以来最贵教训固化出的硬约束（8 处计划缺陷全部「凭记忆写」）。核实维度从「签名/类型/包名」（MS-06）扩到「数值刻度」（MS-12：ratio 百分数、权重和为 100）再扩到「时点口径」（MS-13：期初/期末、t/t−1）。record 加字段的调用点清单用 grep 生成不靠手工盘点（MS-08：9 处实际 vs 6 处计划）。
- **常见坑**：测试深度与 FR 完整性是**计划本身的验收项**（覆盖率逐任务下沉而非只设最后一道关，见 [code-review-lessons.md](code-review-lessons.md) 2026-08-30 轮）；「后续任务回接」的注释都是债，必须在后续任务简报里显式携带（invest-sop：携带的三条全兑现、没带的三条全漏）；计划写完做预检——任务间共享文件/链接依赖列成表并裁执行顺序，避免链接门禁卡死（PR #32）。

### 2.6 单分支全周期 + subagent-driven 执行

- **分支形态**：`feat/<msxx>-<feature>` 单分支承载调研/规格/计划/实现全周期，交付时开单一 PR（2026-10-10 裁定，沿 MS-29 先例一般化；MS-31 首次完整执行，过渡性立项 PR 不再开）。
- **执行形态**：subagent-driven——每任务「fresh 实现 + 独立审查」双签；审查做「报告 vs diff」对账，不轻信实现者自报（invest-sop 28 次审查全部对账，抓过实现者真 bug 与报告笔误）。跨端大批次按目录隔离并行 + 后端串行（避免 Gradle 构建目录竞争，MS-28：24 单元零文件冲突）。
- **TDD 纪律**：每任务先红后绿再提交；红证据形态含行为红与编译红（新符号缺失型，brief 明示合法）；TDD 证据回传失败摘要让修复可审计（code-review-2026-10 第一批）。并发类修复须有「恰一成功」或对抗态断言（MS-28 B3/B6/B8/F3）。顺序偏差如实记录（P1-10：JPQL 批量更新未目击红，复盘如实记为纪律偏差而非隐瞒）。
- **模型分档**：机械抄写卡 haiku、常规集成 sonnet、并发/安全关键与终审 opus（MS-06 提出建议，MS-28 实证 haiku 4 卡全达标）。
- **红线（用户工作偏好）**：破坏性/共享状态操作（dev DB 的 flywayRepair、drop/create、`rm`）先停下确认，让用户亲自执行或明确授权，不擅自执行。
- **常见坑**：「先提交后验证」违纪（MS-08 只看 tail 未看断言计数，3 例全挂后 amend）；修复波也要跑 lint（MS-31 收口门 flake 两次复现后才修）；台账（progress.md）承担跨册/跨会话记忆，中断恢复靠「磁盘工作区状态与 git log 对账先行」（MS-11 实测零重复劳动）。

### 2.7 测试体系与收口门

- **分层**：后端四层（单测 / Testcontainers 集成 / Cucumber 中文 BDD / testFixtures 共享容器基座）+ 前端 vitest（组件级不许 0% 覆盖靠整体数字掩盖）+ Playwright e2e（`E2E_FRESH_BUILD=1` 净构建）+ collector pytest，规格见 [03-后端测试架构](../technology/architecture/03-后端测试架构.md)。
- **覆盖门槛**：三端 ≥80%——后端 JaCoCo 聚合 `test`/`integrationTest`/`bdd` 三层 exec 后统一卡指令/分支双门槛（挂 `check`，**聚焦跑单个 suite 不触发**，须全量）；前端 V8 语句/分支；collector `--cov-fail-under`。门槛要逐任务下沉而非只在收口暴露（MS-03 教训）。
- **收口门（全量 `make test`）**：隔离跑单类 ≠ 全量套件——共享容器的跨类种子冲突与格式门只在全量跑暴露（见经验 #10）。**宣称完成前必须全量真跑一次**。
- **e2e 证据等级**：真浏览器 e2e 抓 mock 盲区类缺陷（MS-08 反代 catch-all、P1-10 PUT 缺失、MS-31 window.confirm 破坏既有 spec）；数据态用例以 DB 落库为验收证据，证明力高于「页面绿了」（MS-11）。
- **CI 双态断言范式**：CI 种子空库与本地真库数据面分叉是常态——每条断言 `.or()` 双态 + 注释写明两态口径（MS-09 首创 boardHasData 门控、MS-13 复制、MS-14 第三次复用）；代价是 CI 永远走空态分支，数据态只靠本地真库兜底（三特性同象，未根治）。

### 2.8 独立终审与修复波

- **做什么**：全分支 diff 独立终审（最强模型档、跨任务视角）→ 修复波一次收敛 → 定向复审。Minor 不进逐任务修复循环，记台账终审 triage 后单波清（PR #32 先例）。
- **为什么必须有**：任务级审查有系统性盲区——跨任务交互（invest-sop 5 项 Important 中 3 项是「P2 留 P3/P4 回接、无人回接」）、跨文档口径对照（拦「两文件一致地错」的唯一层）、mock 之外的刻度/时序问题（MS-12 百分比二次放大 4 项由此而生）。审查网真实拦截记录：MS-29 全部 7 修复轮 + 终审 2 Important 均为审查代理拦下而非实现者自报。
- **常见坑**：简报/计划本身会错，终审锚点要指向事实源而非计划文本（MS-28 色相倒置：任务审查对着错误简报验证通过，终审对照 globals.css 实值才暴露）。

### 2.9 PR 合并与交付回填

- **交付回填 checklist** 六步（PR 合并后依序执行，定义见 [features/README.md](../../features/README.md)）：
  1. 模块文档（docs/function/modules/）功能点置 ✅ + 交付说明；
  2. 功能模块概览看板 + 产品概览 §6 In/Out 范围清单（交付改变版本边界时——2026-09-26 MS-10 终审发现漏此文件致 Out 残留，补入）；
  3. roadmap：里程碑节状态 + 基线表 + 变更记录行 + 风险表；
  4. features 索引行补「已交付 日期 + PR #N」；
  5. `08-复盘总结/复盘总结.md` 回填（含计划偏差清单）；
  6. plans/ 计划头部状态行 + plans/README 索引（2026-10-04 审查发现 4 份已交付计划状态欠账，补入堵根因）。
- **复盘的固定结构**：交付结果（验收条款对账 + 质量证据）→ 执行注记/经验 → 不足与残留 → 优化建议 → **计划与实际的偏差清单**（偏差+根因+发现时机+处置，逐条留痕）——偏差清单是本仓库复盘最有价值的部分，本文第四章主要由它聚合。
- **首跑观察项清单**（MS-30/31 惯例）：部署机/CI 首跑才能回收的观察项单独成表（观察项/来源/回收动作），不混入「不足」。

### 2.10 文档同步与治理门禁

- **链接门禁**：`make check-links`（[scripts/check-links.py](../../scripts/check-links.py)）全仓 markdown 相对链接断链即失败，挂 `make test` 与 CI link-gate job；链接目标须 git 跟踪（gitignore 会话产物本地存在、CI checkout 即红）。
- **契约同步面**：README 端点表 / AGENTS.md 命令表 / DDD 规范域清单（新域登记）+ ArchUnit 白名单（`PackageConventionsTest`）/ 模块文档 / 功能文档——新端点、新域、新命令不同步即文档债（MS-11：DDD 规范漏登 analytics 域，终审发现实有 13 包而文档列 12）。
- **迁移配套动作**：新增 Flyway 迁移 = 改多处——迁移文件 + 版本断言/计数守卫测试 + `scripts/e2e-cleanup.sh` FK 表清理行 + DDD 规范登记（MS-11 提出制度化清单；MS-30/31 已由 Flyway 计数守卫前置拦截）。

---

## 三、流程演进（背景，非操作指南）

| 时点 | 事件 | 动因 |
|---|---|---|
| 2026-08-19~31 | code-review 轮次期：首审→复查→MS-03/04 交付复盘以「轮」写入 code-review-lessons | 复盘机制未独立，与审查经验同文档 |
| 2026-09-01 | 工作流偏好固化：严格 TDD + grilling 澄清 + subagent-driven（MS-05 时用户明确要求） | 单人直写计划的缺陷只能靠事后返工 |
| 2026-09-03 | MS-06 journal 复盘：首个独立特性复盘总结（含计划缺陷清单八条） | 约 8 处计划缺陷全部「凭记忆写」，被 subagent 双评审拦下 |
| 2026-09-06~08 | MS-16~18 P0 验证先行形态确立（装配点源码核实/协议真握手勘误） | SDK 文档与实际行为不符，mock 永远测不出 |
| 2026-09-12 | PR #32 文档大更新：四路审计结论被逐层推翻 | 「两文件一致地错」任务级审查拦不住 |
| 2026-09-17 | features/ 固定编号目录规范：01 需求/02 设计/03 计划/08 复盘/09 调研 + 交付回填 checklist | 各特性目录结构漂移，复盘回填无固定位置 |
| 2026-09-22 | 计划核实清单扩维至数值刻度（MS-12 终审抓百分比二次放大，mock 盲区第五例）；e2e 陈旧构建与 mock 盲区五实例固化 | 刻度不在「签名核实」清单内，小数 fixture 掩盖百倍放大 |
| 2026-09-24 | 计划核实清单再扩时点口径（MS-13 rf 单位/cashWeight 快照两处笔误）；CI 双态断言范式成型 | 时序计算最易错在 t/t−1；CI 空库与本地真库分叉 |
| 2026-09-28~10-03 | v1 发布：Flyway/Alembic 双基线 squash；阶段二 34/34 收口盘点 | 22 个迁移文件收敛为单基线 |
| 2026-10-04 | plans 治理（PR #89 生命周期规则+回填第 6 步；PR #90 链接门禁挂 CI）；main 设四 required checks（PR #85 收 CI 债后） | 26 处断链无人拦；4 份已交付计划状态欠账 |
| 2026-10-05 | 阶段三规划（MS-28~32）+ MS-28 收口：code-review-2026-10 两批 35 修复 | 五路全量审查发现进工程前置 |
| 2026-10-06~10 | MS-29 立项侦察七路前置；MS-30 拍板三件跟进 PR 收口 | 机制假设靠侦察证实；悬空项显式拍板 |
| 2026-10-10 | 单分支全周期裁定（MS-31 首次执行，立项 PR 制废除） | 减少分支/PR 切换，规划与实现同变更单元评审 |
| 2026-10-11 | MS-31 交付：复盘成熟形态（首跑观察项清单 + 评审链拦截择要 + deferred 台账说明） | — |
| 2026-10-11 | MS-32 交付：smoke 真跑抓出全绿之下三系统性缺陷——探针拟真度分层沉淀（#51）；跨源集桩变更核查入建议清单 | 真值池 POST_CALL 通道为 agentscope 升级首要回归面 |

---

## 四、开发经验（跨特性教训分类沉淀）

> 每条：结论 → 复发出处 → 关键实例。复发 ≥2 次的标注次数。与 [code-review-lessons.md](code-review-lessons.md) 的审查清单互补：那边是「审查时怎么查」，这边是「开发时怎么避」。

### 4.1 规格与计划

1. **计划代码样例不对照真实代码 = 缺陷复制进代码**（7 特性复发：MS-06/08/09/11/12/13 + invest-sop）。MS-06 是教科书案例（8 处缺陷：`reconstitute` 非 `new`、缺 `@DataJpaTest`、userId 哨兵约定、`{string}` 漏引号、`key` 缺失、query 丢失…）；对照核实的红利同样明确——MS-11 计划期 Read 真实签名后 20 任务样例 verbatim 落地率极高、仅 2 处编译级笔误。
2. **计划核实清单必须含数值刻度与时点口径**（MS-12 + MS-13 + MS-14 三连发）。签名对了刻度错了：`ratio()` 返回百分数、权重和为 100——MS-12 展示层再 ×100 全线放大百倍，小数 fixture 恰好复刻错误假设而单测全绿；MS-13 cashWeight 期初/期末快照口径与设计规格冲突，手算对拍红灯才统一。时序计算（Brinson/回撤类）最易错在 t/t−1。
3. **外部接口字段语义（单位/去重/窗口参数）实测校准先行，计划引用调研结论而非先验锚点**（MS-09 + MS-13 + MS-14）。MS-09 单位锚点「工行 ≈6.58 万亿」实为口径混用；探测报告本身也要复现验证（MS-14 ISEXCHG 幻影字段当日勘误）。
4. **「后续任务回接」的注释都是债，必须显式携带进下游简报**（invest-sop + MS-20~23 双向验证）。invest-sop 终审 5 项 Important 中 3 项是无人回接的集成遗漏；research-intelligence 把「SQL IN 留 P4」写进 javadoc 废止口径 + P4 预检冲突扫描表主动捡起——两处都做就没人漏。
5. **主源不可得时先实证降级路径、拿选项拍板、裁决落盘**（MS-13 + MS-10 + MS-14）。不立即改计划——把各预案实测结论（可得/排除）连同推荐交用户裁决，裁决单独 commit 可追溯。
6. **已知答案测试是计划手算笔误的唯一防线**（MS-13×2 + MS-14 + invest-sop）。夏普 rf 漏 ÷365、TE 恒定差 std=0、kelly 0.6/2.0→0.2（实 0.4），全部 TDD 红灯 + 实现者复核拦下。
7. **计划与规格/代码冲突时，以代码与 brief 红线为准并当日回写消歧**（MS-13 + invest-sop + MS-31）。「计划冻结时规格旧文未同步」的内部矛盾靠逐任务评审对读拦下（MS-31 B2 返 void vs 规格 `CuratedMutationResult`、B6 游标推进语义矛盾，两处均规格回填消歧、未流到实现）；invest-sop 简报字段名与实码不符按「以实码为准」裁决。冲突裁决后必须回写，否则下一个读者拿到的仍是旧口径。
8. **设计分叉从机械项里分离出来，只对前者做选择题**（code-review-2026-10 + code-review-lessons 2026-09-03 轮）：几十条发现里真正有取舍的只有个位数（ERP 真实实现/@Version/备源），逐项给「倾向方案 + 替代方案 + 取舍理由」让确认者只做选择题，确认后并行执行零返工；机械项（错误码/校验/去重/命名）直接修。

### 4.2 测试与质量门

9. **mock 单测对库内/框架行为全盲，关键改动必须真浏览器 e2e 或真实依赖测试兜底**（≥7 特性：MS-08/12/19、mcp-hitl、P1-10、MS-31、account-email[补建回溯：e2e 404 逼出 catch-all 反代缺口]）。五类实例：库内默认值解析（agentId）、`import type` 擦除副作用注册、useInterrupt 元素 memo、`.next` 陈旧构建、服务层数值刻度 fixture 失真——绿的只是自己的假设，错误全在 mock 边界之外。fixture 必须取服务层真实产出形态（MS-12 固化、MS-30 沿用：zod fixture 从集成测试真实响应抄录）。
10. **隔离跑单类 ≠ 全量套件，宣称完成前必须全量真跑**（6 次：MS-04/06、mcp-hitl、MS-14、MS-31、MS-32）。共享 Testcontainers 容器的跨类种子冲突（userId 撞 AdminSeed/主键冲突/哨兵 id 碰撞——测试种子用高位哨兵 id 且须离散化）与格式门（collector 漏 ruff format）只在全量暴露；`make test 2>&1 | tail` 会吞 exit code（管道退出码 = tail 的 0）。MS-32 新形态：**改共享资产面（prompt 资产计数锚）只跑包级定向，跨源集（integrationTest）计数锚潜伏红**——凡动 EvalAssetHasher/PromptVersionRegistrar 消费面，定向范围须含 integrationTest 源集。
11. **新增 Flyway 迁移 = 改多处，版本断言漏同步是最高频返工**（6 次：MS-04/06/09/11/16/17，见 [code-review-lessons.md](code-review-lessons.md)）。止住靠机制化：迁移文件数=断言数双向守卫 → MS-30/31 的 Flyway 计数守卫测试前置（4→5、6→7 先改守卫再落迁移）。
12. **测试断言本身会固化 bug / 写错**（MS-08×2 + code-review-2026-10 B5 + MS-14）。CSV 断言没算固定列、表头漏 BOM、EmailCodeServiceTest 四分支断言固化枚举行为——先归因实现、看 diff 后确认测试自身错时，改测试口径而非迁就。
13. **测试基座用真实迁移构建，不用手工 DDL 副本**（code-review-lessons 2026-08-31 轮 + MS-10）。测「假 schema」的唯一约束改了测试还绿；跨服务数据面（shenwan_industry_mapping）在 Testcontainers 空库须测试内自种。
14. **GWT 命名规约是构建红线**（P1-10 + MS-09）。新测试类直接照 GWT 起名 + 中文 `@DisplayName`，别等 `TestSourceSetConventionsTest` 拦构建。
15. **跨端 wire 契约显式约定 null 字段形态，并用真实序列化 round-trip 钉住**（issue #25 + MS-19 同源复发 → 升级通用条款）。契约测试对象是「线上字节」不是「内存对象」；同类问题相邻特性出现两次即写进契约模板（已沉淀 `AguiEventNonNullCodec`）。
16. **CI 失败输出要能归因**：Gradle `testLogging exceptionFormat=full`（此前失败只给类名+行号，MS-20~23 CI 债排查两根因时补）；CI e2e job 依赖的客户端（postgresql-client）缺失会让 psql 门控用例静默跳过——**门控 skip 要能区分「门控跳过」与「装配缺失假绿」**（MS-30）。
17. **本地绿 CI 红优先怀疑平台/版本矩阵差异**（MS-20~23 CI 债两例）：darwin 时钟仅微秒（`Instant.now()` 纳秒尾恒 000）本地恒绿、linux 真纳秒被 PG timestamptz 微秒舍入 CI 恒红；CPython 3.12 `html.parser` 内部缓冲撞名 vs 3.13 已改名——mac/3.13 恒绿、CI/3.12 恒红。跨平台断言显式对齐时间精度（`truncatedTo(MICROS)`）。
18. **e2e 写库数据的残留治理要配套**（MS-10/11/14）：新建 FK 表必须同步 `scripts/e2e-cleanup.sh` 删行（MS-11 漏配致 dev 库累积 56 个 e2e 用户）；e2e 数据用固定前缀（E2E%）+ 护栏测试复核脚本行；smoke 用专用账号（invest-sop：admin 账号每次累积 1 项目 + 1 wiki 条目不清理）。
19. **eval/题库类测试资产自身先过自洽性检查**（MS-20~23 + MS-30）：题面数字与叙述矛盾（Q4 表列 vs 同比）、锚表述与桩口径跨源不自洽，会让换模型/改提示词的 FAIL 无法归因——扩题库时先自洽校验，误报数据回来再数据驱动校准锚表述，不拍脑袋改断言。
20. **死配置是「静默失败」在新层的复发，修复方式是接线或删除而非补文档**（code-review-2026-10 B3 + code-review-lessons）：`toolTimeout`/`poolMaxSize` 声明零引用；MS-30 `unit-price-cny-per-mtok` 实现改道前端后成死键——三处文档标注「设前端变量」只是止血，最终要删。
21. **跨上下文装配方案（子进程/独立 schema）不能凭框架文档直觉写，须逐层实证**（MS-30）：env 白名单（「变量缺席即跳过」运行时语义复用）、类级条件装配（`@ConditionalOnExpression` 沿全仓唯一先例不引新机制）、schema 双段 URL（单段 pg_trgm 算子类必炸）三层各自实证后才落地并回写设计规格；「jar 字节码 + 集成测试」双证实是最低标准。

### 4.3 前后端协作与集成

22. **并行开发的「契约接缝」是收口方核心工作**（MS-28 + code-review-lessons 2026-09-03 轮）。改构造签名必须 grep 全部调用点（含 @Bean 装配与 TestConfiguration）；并行跨端任务把 wire 契约显式列给某一方的收尾清单；跨端修改先定契约再并行。
23. **前端自顶向下从 FR 长出来，不是从后端端点长出来**（MS-03 教训，见 code-review-lessons）；反代 route handler 一律透传 query string 与响应头（`Content-Disposition` 等），主端点含根路径必须 `[[...path]]` 可选 catch-all（MS-08）。
24. **真 LLM 的 e2e/smoke 有专门工程约束**（MS-12 + MS-14 + MS-20~23）。超时放宽（18 参工具 240s）；指令显式点名工具名 +「只用内置工具」（否则 LLM 顺手调 MCP 写工具触发 HITL 挂起）；LLM 边界工具对枚举/代码类入参做防御校验（非法 index 已修为白名单外降级）；先抓一次真实对话快照再写断言。
25. **中文产品前端一级公民：IME 组合态**（code-review-2026-10 B2，4 处复发）。Enter 提交必须 `isComposing` 守卫，组件测试显式构造组合态事件（e2e 覆盖不了）。
26. **跨任务/跨特性副作用靠 e2e 全量回归兜底**（MS-31 + MS-11）。MS-31 F4 把删除确认改 `window.confirm`，破坏既有 conversation.spec（无 dialog handler 时 Playwright 点击即崩）——实现者自查未见，评审以独立证据链确认；MS-11 组件新增 `useAuth()` 使既有测试无 Provider 必炸。**有意变更也要跑全量 e2e，不只跑新用例**。

### 4.4 数据与迁移

27. **迁移不可变：合入/应用后注释也是契约**（MS-30 实战回退）。改内容即 Flyway validate 炸已应用库——措辞债记 javadoc/文档还，不动迁移；dev 库手动插的迁移行 checksum=NULL 是脏基线，PR 前须重置（MS-20~23）。
28. **金融计算的日历/时区口径是正确性问题**（MS-09 BDD 互扰 + code-review-2026-10 C1/B1 + MS-28 succeeded_on 时区）。凡「日期」参与计算/过滤/对账，先问：哪个时区的哪个自然日？日历数据从哪来？BDD 种子固定日 + 键位错开 + 不依赖 `max(trading_day)` 全局态（MS-09 固化为测试架构约定）。
29. **同一口径两处实现必配一致性测试**（MS-09 + MS-14）。重算 SQL vs Python calc、后端 vs 前端推算——口径漂移无报错、数值差异小，只有不变量断言能拦。
30. **DB 读侧依赖的不变量下沉 DB 约束**（MS-04 终审 + code-review-lessons）：部分唯一索引（至多一个 active）、「读-改-写」钱账聚合加 `@Version` 乐观锁、`@Modifying` 批量 update 加 `clearAutomatically`。
31. **种子显式 id 不推进 BIGSERIAL 序列**（MS-10）：迁移尾部 `setval(max(id))` 防应用层首插撞键；种子日期用 `CURRENT_DATE` 相对化防窗口断言过期（MS-10 V19.1）。
32. **浮点阈值/边界断言用整数交叉相乘**（MS-30）：`1.0−0.9=0.0999…98` 会漏报「恰降 10pp」——`100·(bp·ct−cp·bt) ≥ 阈·bt·ct` 长整型判定 + 边界用例钉死；浮点路径差断言用 `isCloseTo(within(1e-9))` 而非字面量相等（MS-12 偏差 #5）。

### 4.5 文档与治理

33. **引用数字必须 git 实证，不凭汇总记忆**（MS-11 双向验证 + MS-20~23 报告计数 52→36）。编排者给交付文档的补充清单也错（「前端新增 25」实为 38）——`git diff` 数测试方法、`git log -S` 归因，与要求实现者的标准一致。
34. **审计结论本身也有错：口径修正进台账、终审跨文档对照是拦「一致地错」的唯一层**（PR #32 四路审计被推翻 5 处 + MS-31 两处计划-规格矛盾）。实现者与审查者各自独立 Read 复核；终审输入机械地包含台账全部 deferred/传导项，不手工挑。
35. **注释/javadoc 声称的保障必须机械可查**（MS-10「由组件测试锁定」不实被评审抓出 + MS-30 PDF 端口 javadoc 契约修正）。无对应测试文件引用的保障声明不可信。
36. **命名原则全链一致：intelligence 全名禁 intel 缩写**（用户 2026-09-29 裁定，表/类/路由/配置全链 + 跨特性同步改名）——杜绝双写漂移；类似地，语义妥协型 fix 显式标注并留恢复路径（PR #21 强制 readOnly 过渡，MS-18 按 MCP 规范恢复判定），否则「临时」会变成「永久」。
37. **会话产物（`.superpowers/` 等不入库目录）的结论必须升格进 git 跟踪文档**（mcp-hitl 验证记录补写先例 + plans 治理链接门禁盲区）。P0 报告声称「完整版已写入」但文件从未提交、gitignore 台账本地存在 CI 断链——**结论性内容落特性目录（验证记录/复盘/调研报告），会话台账只留过程**。
38. **跨特性工作的落位判据**（2026-10-04 明文化，[features/README.md](../../features/README.md)）：单特性 → 特性目录 `03-实施计划/`；跨特性一次性工程战役（有明确终点）→ `features/plans/`；跨特性长期多阶段演进 → 独立特性目录并登记索引。

### 4.6 部署与上线

39. **部署顺序依赖写进发布方案**：先配 `MCP_SECRET_KEY` 再设 token（反序 503，P1-10）；collector 新任务 YAML 先 `seed` 登记 `collector_task` 再跑（MS-09）；eval jar 同目录分发 + `INVEST_EVAL_DATA_ROOT` 绝对路径（MS-30）；飞书 7 变量 / MAIL_* 按服务重启（飞书/account-email）。
40. **部署机人工项与首跑观察项单独立表**（MS-28/30/31 惯例）：不阻塞合并、上线时执行、回收动作明确——避免「本地全绿」掩盖「部署环境才显形」的项（SMTP 无超时持 advisory lock 挂死、CI runner 到东财可达性、调度无分布式锁需单副本部署）。
41. **smoke 的环境前置自检**：固定共享 threadId 一旦残留未应答 HITL 中断即永久卡死、重启不自愈、只能人工清 `.agentscope/state/`（MS-12/14 实证，同机残目录显示已多次发生）——threadId 应按次唯一；健康检查失败输出「需先 make dev」提示（MS-10）。
42. **CI 门禁本身要治理**：main 四 required checks（backend/frontend/collector/e2e）+ link-gate（须手动加入分支保护，遗留人工动作）；本地 `make test` 不跑 collector ruff（CI 拦 E501 的盲区）；环境门控测试（env 白名单/固定码）必须「缺席即跳过」语义可验证，防止配置漂移后假红/假绿。

### 4.7 环境与工具坑

43. **定向测试命令陷阱**：`pnpm test -- <file>` / `pnpm test:e2e -- <spec>` 经 pnpm 透传后过滤失效跑全套（MS-11 两任务复现）；`npx playwright test portfolio` 按子串命中仓库名也跑全套——定向用 `pnpm vitest run` / `npx playwright test e2e/xxx.spec.ts` 全路径。
44. **`.env` 值含 `#` 时三种加载器语义分歧**（v1 发布实测）：make `-include` 截断为注释（拿到截断 SMTP 密码假红）、bash source 与 systemd EnvironmentFile 保留——排查比对 make（`make -pn`）与 bash 读值。
45. **IDE/TS server 对新建文件的 cannot-resolve 诊断频繁滞后误报**（MS-11 每任务裁决一次）：以 gradle JUnit XML 计数、`tsc --noEmit`、vitest 实跑为准。
46. **macOS `strings` 读不了 .class**（0xCAFEBABE 撞 Mach-O 魔数静默输出空，险些误判「上游无此文案」）：核对 jar 常量用 `javap -v`；agentscope jar 在仓库 `.gradle-home`。
47. **e2e 清 `.next` 前必须先停前端服务**——对运行中的 Next 服务清 `.next` 当场炸全量套件，重启即恢复（两操作互斥）。
48. **本地 dev DB 是本机 Homebrew postgres（localhost:5432）非 docker**：修 Flyway 状态连本机 psql。
49. **上游框架的文档不可全信，以源码/实测为准**（MS-16~18 三连 + MS-32）：MCP client `buildSync()` 后必须 `initialize()` 握手（SDK 不强制、报错要跑起来才见）；agentscope Javadoc「not thread-safe」与实际并发实现不符（过时）；「等上游实现」的依赖项记录在验证文档并绑定版本号（2.0.1 勘误 → 2.0.3 消解），升级窗口加迁移探针（forRemoval 钉版，MS-29）；MS-32：`HarnessAgent.ensureSessionDefaults` 对裸 rc 恒派生新实例（无文档行为）——编排器侧 rc 与工具侧 rc 断裂，字节码确诊。
51. **探针/单测拟真度分层：原语级（单测可证）< 装配级（字节码可证）< 运行时链路级（只有真跑可证）**（mcp-hitl P0 真握手 + MS-32 真值池 rc 断裂）：MS-32 的 B0 探针轮在前两层全过，真值池快照机制却在 smoke 真 LLM 链路失效（工具调用全 SUCCESS、快照恒空）——三缺陷（另含 cap 撞线/parser 裸换行）在单测 2426 + BDD 34 + e2e 76 **全绿之下**仍然存在。**smoke 级真跑是涉及框架运行时链路的唯一充分验收**；跨源集改桩时 grep 范围显式含 bdd/integrationTest/eval（MS-32 终审 C-1：修复波漏迁 bdd 桩，全量 make test 才拦下）。
50. **Gradle 测试任务 UP-TO-DATE 假绿**（飞书 + MS-09/11 两特性）：env 门控类测试（如 `@SpringBootTest(properties=...)` 换密钥）重跑被 UP-TO-DATE 跳过、显示旧结果——验证证据要「全新跑」：`--rerun-tasks` / cleanTest，收口口径写明「全新证据」（MS-09 `--rerun-tasks`、MS-11 cleanTest）；同因，`@SpringBootTest(properties=...)` 只认编译期常量，运行时拼接值（如 `Base64.encode()`）直接编译失败，用固定字面量（P1-10）。

---

## 五、高频复发模式榜

> 统计口径：一次计一条的特性级事件（同特性多实例不重复计）；早期特性（MS-03/04/19 等）经 [code-review-lessons.md](code-review-lessons.md) 计入。

| # | 模式 | 复发次数（特性） | 现行拦截机制 | 状态 |
|---|---|---|---|---|
| 1 | 计划凭记忆写、不对照真实代码/实测（签名/清单/先验/刻度/时点） | 7（MS-06/08/09/11/12/13 + invest-sop） | 计划核实清单（签名/类型/数值刻度/时点口径）+ 设计事实锚点 + 已知答案测试 | 仍在复发，维度逐轮扩出 |
| 2 | mock/fixture 盲区：单测绿 ≠ 真行为 | ≥8（MS-08/12/19、mcp-hitl、P1-10、MS-31、account-email、MS-32[smoke 抓全绿之下三缺陷]） | 真浏览器 e2e fresh build + 真实序列化 round-trip + fixture 取服务层真实产出 + 框架运行时链路走 smoke 真跑（#51） | 有兜底、无根治 |
| 3 | Flyway 版本断言/迁移配套漏同步 | 6 次（MS-04/06/09/11/16/17） | Flyway 计数守卫测试前置（MS-30/31 起机制化） | 已机制化止住 |
| 4 | 隔离跑 ≠ 全量跑（种子冲突/格式门/跨源集计数锚） | 6（MS-04/06、mcp-hitl、MS-14、MS-31、MS-32） | 收口门全量 make test；哨兵 id 约定；共享资产面定向含 integrationTest | 仍偶发（哨兵碰撞 MS-31、资产锚 MS-32） |
| 5 | 文档/口径「两文件一致地错」与引用数字失实 | ≥4（PR #32、MS-11、MS-20~23、MS-31） | git 实证引用数字 + 终审跨文档对照 + 链接门禁 | 靠终审兜底 |
| 6 | CI 空库与本地真库数据面分叉 | 3（MS-09/13/14 同象） | or() 双态断言 + 空库门控 skip | 未收敛（接受态） |
| 7 | 「先提交后验证」/修复波漏 lint | 2（MS-08、MS-31；MS-14 格式门归 #4） | verification-before-completion + 看断言计数不看 tail | 纪律项 |
| 8 | 上游框架契约与文档不符（握手/Javadoc 过时/null 序列化/rc 派生无文档） | 4（mcp-hitl ×2、MS-16/17、MS-29、MS-32） | P0 源码核实 + 真握手冒烟 + 版本绑定留档 + 字节码确诊（#51 分层） | 新接框架必踩一次 |
| 9 | 验证证据不新鲜（UP-TO-DATE 假绿/复用旧 .next） | 3（MS-09/11、飞书、MS-19） | `--rerun-tasks`/cleanTest + `E2E_FRESH_BUILD=1` 收口写明「全新证据」 | 已有开关，靠纪律启用 |

> 维护约定：每次特性交付复盘回填时，本文第四/五章滚动增补（复发次数 +1、新拦截机制、状态变更）；流程形态变更（新环节/新门禁）更新第二章并同步 [features/README.md](../../features/README.md) 的流程描述。
