# 代码审查经验沉淀

> 滚动文档：每轮深度 CodeReview 后追加一节经验。目的不是复述报告结论（结论在 `docs/reviews/code-review-*.md`），而是沉淀**可复用的方法论、反复出现的问题模式和审查清单**。

---

## 2026-08-29 轮（后端 + 前端 + collector 首审）

### 本轮做了什么

- 对上轮（2026-08-19）报告逐条核验：3 个 P0 全部修复、P1/P2 大部分闭环，修复均带回归测试。
- 首次将 collector（Python 采集服务）纳入审查，发现唯一 P0。
- 新发现 P0×1 / P1×9 / P2×27，当日在 `chore/code-review-2026-08-29` 分支全部修复闭环，`make test` 三端全绿。

### 方法论经验（值得固化）

1. **审查先做"上轮核验"再做"新发现"**。核验旧条目让修复质量可见（本轮确认上轮修复闭环率高），也暴露"修复引入的新风险"——典型例子：上轮把健康探活修成"绕过缓存直测上游"，本轮发现它变成了匿名可刷的限流配额消耗口（B-P1-1）。**修复本身要纳入下一轮审查范围**。
2. **未被 CI/测试流程覆盖的代码是高危区**。collector 不在 `make test` 和 CI 里，80% 门槛只手动生效——唯一 P0 就出在这里。教训：**先把代码纳入强制测试流程，再谈覆盖率数字**。审查任何服务时，第一眼看"它的测试会不会被自动跑"。
3. **失败路径要用真实依赖测，不能只 mock**。collector P0（事务未回滚导致重试与失败日志全失效）被全套 MagicMock 测试完美掩盖——mock 连接不会进入 aborted transaction 状态。教训：**凡是涉及事务、连接、锁、序列化的行为，至少要有一条真实 DB/真实容器（testcontainers）的窄端到端测试**。同理本轮发现 `test_backfill.py` 把错误行为断言为"正确"——**审查测试断言本身是否固化 bug**。
4. **逐项确认方案的机制成本低、返工少**。本轮 P0→P2 共 37 项，逐项与作者确认（含三个分叉点：重试配置生效 vs 删除、健康检查拆端点 vs 加缓存、渲染节流参数），确认后并行执行零返工。关键做法：每项给出**倾向方案 + 明确的替代方案 + 取舍理由**，让确认者只需做选择题。
5. **跨端修改先定契约再并行**。health/status 拆端点涉及后端、前端、docker-compose、Playwright 四方，先把响应结构写成明确契约，两端并行改完直接咬合。并行修复按目录隔离分工（backend/frontend/collector），无文件冲突。
6. **修复的收尾包括文档同步**：AGENTS.md（流程变化如 make test 范围）、README（端点表）、模块文档（接口契约）必须在同一分支内更新，否则文档与代码漂移会累积成下一轮的债。

### 反复出现的问题模式（未来审查清单）

按本轮命中率从高到低排列，后续审查可直接对照排查：

- **资源边界**：无界缓存（任意输入作 key）、限流被重试/降级绕过、匿名端点触发重计算/外呼（健康检查、估值全表扫描）。→ 每个匿名端点问一句：刷它会消耗什么？
- **静默失败**：配置声明了但没人消费（retry_max 死配置）、初始化数据永不更新（交易日历跨年断更）、异常被吞或逃出捕获层级（calc KeyError 逃逸）、调度器 misfire 静默丢任务。→ 搜索"只在启动时执行一次"和"catch 后只 log"的代码。
- **失败路径不工作**：重试机制在真实故障下失效（事务未回滚）、失败记录写不进库、熔断计数被重试放大。→ 专门演练故障注入，而不只测 happy path。
- **冷启动缝隙**：定时任务首次触发要等一个完整 interval，依赖它的下游任务在新部署后必失败 N 天。→ 每个 interval 任务问：首次数据何时产出？
- **前端流式三件套**：竞态（无序号/取消守卫）、防抖丢尾部（cleanup 不 flush）、渲染/持久化写放大（每 token 全量重渲染/重写）。→ 流式场景必查这三点。
- **端点语义错位**：liveness 探针依赖外部服务（应零外呼）、业务状态与存活探活混在一个端点。→ liveness 只回答"进程活着吗"。
- **边界校验缺失**：DTO 零校验直达 DB 约束，约束违例变 500；输入长度/条数无上限变存储滥用。→ wire→domain 边界必校验，约束违例必映射 4xx。
- **配置与凭证卫生**：magic number 散落、密钥/key 硬编码、配置语义字符串与实际类型对不上。→ 新增配置项走统一 properties，语义写成枚举。

### 待观察 / 下轮关注

- CI collector job 推送后的首跑情况。
- `make smoke` 验证 health/status 跨端契约（本轮并行修改，未做真实联调）。
- `retry_max` 语义定为"重试次数"（总尝试 = retry_max+1），与历史口径不同，观察是否引起误解。
- 前端 `app/` 层已纳入覆盖 include，但 health/status 等路由的分支覆盖（60%）有提升空间。

---

## 2026-08-30 轮（持仓组合管理 MS-03 交付复盘）

### 本轮做了什么

- 用 superpowers 工作流（brainstorming → writing-plans → subagent-driven-development）完整交付 MS-03 持仓组合管理（M08-F01~F11）：后端 `domain/portfolio` + `application/portfolio` + `web/PortfolioController`（17 端点）+ Flyway V5，前端 `/portfolio` 页面。
- 16 任务 + 2 轮修复（`getOrCreatePortfolio` 并发竞态、SRS 规格缺口补齐）+ 1 轮测试回填，最终 `make test` 三套件 ≥80%、e2e 2/2、PR #6。

### 暴露的两个返工点

| 返工点 | 现象 | 根因 |
|---|---|---|
| P2 覆盖率跌破 80% | BRANCH 82.63% → 77.06%（差 2.9pt） | 计划测试模板只写 happy path；门槛只在最后一个 Task 才跑；没做覆盖率影响预估 |
| 功能遗漏 | FR-A2 编辑 / FR-B1 改名 / FR-B2 现金录入 / FR-A5 分红录入 被静默丢弃 | 计划从 SRS 收窄却没显式标「延后」；计划无 FR→实现 可追溯矩阵 |

### 方法论经验（值得固化）

1. **计划是 subagent 的契约，计划的缺口 = 代码的缺口**。subagent-driven 会忠实地把计划里的「测试不足」和「FR 遗漏」复制成代码里的缺口，而中间的任务审查只对 brief 负责、无法早期拦截——缺口只能由最终全分支审查或覆盖率门槛兜底，代价是返工。**写计划时必须把「测试深度」和「FR 完整性」当成计划本身的验收项，而不是代码的验收项。**
2. **覆盖率门槛要逐任务下沉，不能只设最后一道关**。P2 把 jacoco 门槛放在 Task 5，中间任务都 `-x jacocoTestCoverageVerification` 跳过，导致缺口在代码全写完才暴露。**每个 Task 的测试模板显式枚举要覆盖的分支，并让新增代码在 Task 内就过覆盖率门槛。**
3. **计划要带「FR→实现」可追溯矩阵**。计划的 Self-Review 写「Spec 覆盖…全部落地」是一句自我断言，没用；真正有效的是开头列一张表：每个 SRS FR → 实现它的 task/文件/端点，未实现的显式标「延后」。**Self-Review 的 spec 覆盖必须从一句话改成逐条 FR 对照表。**
4. **前端要「自顶向下」从 FR 长出来，不是「自底向上」从后端 API 长出来**。计划列组件时若只映射「后端已有端点」，就会漏掉「需要新增端点」的 FR（如编辑持仓、分组改名）。**逐条 FR 问「哪个组件/端点实现它」，找不到即遗漏。**
5. **「明确不做 YAGNI」必须与实现范围一致**。SRS 的 YAGNI 章节列了 FIFO/多标签/CSV/对比配置等，却没列编辑持仓/改名/现金录入/分红录入——后者在 SRS 里是 in-scope，却在计划里被静默丢弃。**计划从 SRS 收窄任何范围，都要在计划里显式标注「延后」，否则就是静默范围收缩。**
6. **写计划时做「覆盖率影响预估」**。P1 结束时 BRANCH 只高出下限 2.6pt，这个预警信号被忽略；在 82% 的地基上加 322 行薄测试的大服务，跌破 80% 是必然。**新增大代码块前，估算行数/分支数 + 所需测试量。**

### 反复出现的问题模式（未来写计划 / 审查清单）

- **计划测试模板 = happy path 演示** → 每个方法显式枚举全部分支（含空值、异常、边界），测试覆盖全分支。
- **覆盖率门槛只放最后** → 逐任务下沉，新增代码在 Task 内过门槛。
- **「spec 覆盖」是自我断言** → 改成 FR→文件/端点 的逐条对照表，写计划时自检一遍。
- **组件列表 = 后端端点映射** → 改为 FR 映射，自顶向下排。
- **计划范围 ≠ SRS 范围且未标注** → 收窄任何范围都显式写「延后」，SRS YAGNI 与计划范围对齐。
- **逐任务审查对 SRS 完整性盲区** → 开始实现前做一次 FR-by-FR 的 spec 审计（对照 SRS 查计划），把遗漏挡在编码前。

### 待观察 / 下轮关注

- MS-04 资产配置基础的计划编写直接套用上述清单（覆盖率逐任务 + FR 矩阵 + 范围对齐）。
- `make smoke`（真实行情 + AI 对话）仍未跑，合并 MS-03 前建议补一次端到端冒烟。

---

## 2026-08-31 轮（collector 测试体系加固收尾）

### 本轮做了什么

- 对上轮（2026-08-29）collector 审查暴露的测试缺口逐项闭环：用例 103 → 148，覆盖率 89% → 95.56%，T3 幂等 upsert 落实 1/6 → 6/6，全量 YAML 装配冒烟补齐（四个 registry 36–62% → 86–100%），详见 `features/plans/2026-08-31-collector测试体系加固.md`。
- 基建改造：conftest 废弃手工 DDL，集成测试 schema 改用真实迁移构建（alembic upgrade head + 回放后端 Flyway V3/V4 SQL）；两处生产修复（`seed_tasks` 键校验 fail-fast、任务 job 异常兜底）。

### 反复出现的问题模式（新增审查清单）

- **手工 DDL 副本漂移**：测试用一份手工维护的 DDL 建 schema，与真实迁移（alembic/Flyway）必然双向漂移——测的是「假 schema」，唯一约束改了测试还绿。→ 集成测试 schema 必须由真实迁移构建；在 conftest/fixtures 里看到手工 DDL 副本即判债。
- **装配链路被 mock 绕过**：测试全用 mock 注册表注入组件，registry `get()`、YAML 解析、装配全链路零覆盖（本轮实测四个 registry 覆盖率 36–62%），配置层 typo 与注册遗漏只有上线才暴露。→ 至少一条「真实 YAML + 真实注册表」的全量装配冒烟（`load → build_registries → assemble`），顺带把所有 registry 的 get/未注册分支打满。

---

## 2026-08-31 轮（MS-04 资产配置基础交付复盘）

### 本轮做了什么

- 用 superpowers 工作流完整交付 MS-04 资产配置基础（M07-F01 模板库 + F02 自定义方案 + 偏离度对比，F03 风险测评后置）：后端 `domain/allocation` + `application/allocation` + `web/AllocationController`（7 端点）+ Flyway V6，前端 `/allocation` 页面。
- 22 提交、16 任务逐任务 review clean、终审（opus）2 Important + 1 Minor 一轮修复闭环、`make test` 三端全绿，PR #10。

### 一、需求规格说明（要点）

> 完整版见 `features/asset-allocation/01-requirement/需求规格说明.md`，此处只列关键决策。

- **范围**：MS-04 = F01 模板库 + F02 自定义方案；F03 风险测评本里程碑后置（相对产品落地计划原文 F01/F02/F03 有收窄，已在「需求澄清汇总」显式记录）。
- **资产类别**：固定 5 大类枚举（股票/债券/黄金/现金/REITs），不分地域——与 M08 持仓「权益/现金」两片对齐，模板（永久组合/全天候）天然需要债券/黄金。
- **方案模型**：多方案/用户 + 唯一「生效」标记；偏离度以生效方案为准。
- **偏离度**：仅展示于 `/allocation`，不改动 `/portfolio`；持仓侧映射 A股+ETF→股票、现金→现金、其余类别记 0。
- **明确不做**：风险测评、再平衡、回测、地域拆分、债券/黄金/REITs 持仓录入、AI 配置建议工具。

### 二、技术规格说明（要点）

> 完整版见 `features/asset-allocation/02-plan/`，此处只列架构决策。

- **领域**：`AssetClass` 枚举、`AllocationPlan` 不可变聚合根（`validateWeights` 非负且和=100 精确）、`AllocationTemplate` 4 模板、`AllocationException/ErrorCode`。纯 POJO，零 Spring/JPA（ArchUnit 强制）。
- **持久化**：Flyway V6 两表归一化（`allocation_plan` + `allocation_plan_weight`），JPA 扁平实体 + `AllocationPlanRepositoryImpl`（`save` = 存 plan → 删旧权重 → 插新权重）。
- **用例**：`AllocationApplicationService` 注入 `AllocationPlanRepository` + 复用 `PortfolioApplicationService.allocation()`（`application.allocation → application.portfolio` 在 ArchUnit 白名单内，无需改 `PackageConventionsTest`）。
- **偏离度集成**：`mapHoldings` 把 portfolio 的「权益/现金」两片映射 STOCK/CASH，`deviation` 逐类算「实际−目标」。
- **前端**：同源反代（`relay`）+ zod 契约 + `/allocation` 页面（DeviationChart/PlanEditor/PlanList），`RequireAuth` 包裹。

### 三、实现过程（TDD 符合性评估）

**结论：TDD 只在纯领域层严格落地，应用/基础设施/Web 层是「实现先行、测试后置」。**

- **严格 RED-GREEN**（先写失败测试 → 跑红 → 实现 → 跑绿）：`AssetClass`/`AllocationTemplate`/`AllocationPlan` 三个纯领域任务（P1 Task 1-3）。
- **编译即收、测试拆到后续独立任务**：仓库接口+迁移（P1 Task 4）、JPA 实体+仓库实现（P1 Task 5）、DTO/命令（P2 Task 1）、服务（P2 Task 2）、控制器（P2 Task 3）——它们的测试被计划刻意拆成 P2 Task 4（服务单测）/Task 5（切片）/Task 6（BDD）。
- 由此：服务/控制器在写的时候没有即时测试护栏，其分支逻辑（偏离度映射、异常映射）要等到后续测试任务或终审才暴露。这是**计划的结构选择**（把测试当独立交付物），不是执行者偏离——但严格意义上，应用/Web 层是 test-after 而非 test-first。
- **对下轮建议**：若严格 TDD 是目标，服务/控制器任务应把「写失败测试」作为该任务第一步（而非拆到独立任务），至少对偏离度映射/异常映射这类含分支逻辑的方法。

### 四、测试用例整理与覆盖情况

| 层 | 测试文件 | 用例数 | 覆盖点 |
|---|---|---|---|
| 领域单测 | AssetClassTest / AllocationTemplateTest / AllocationPlanTest | 1 / 2 / 5 | 枚举标签、模板权重和=100、权重校验（空/负/非100）、不可变变更 |
| 服务单测 | AllocationApplicationServiceTest | 9 | 模板/创建/重复权重/激活/非本人404/偏离度映射与差值 |
| 切片 | AllocationControllerTest | 5 | 7 端点 HTTP 语义 + 404 异常映射 |
| 集成 | AllocationPlanRepositoryImplTest | 4 | 保存回读/权重替换/激活查询/级联删除（真实 PG） |
| BDD | allocation_plan.feature | 2 场景 | 套模板激活 + 偏离度空态/五类 |
| 前端 API | allocationApi.test.ts | 5 | 端点/方法/body/契约解析/schema-drift |
| 前端路由 | allocationRoute.test.ts | 5 | 反代上游路径/Cookie/body 透传 |
| 前端组件 | DeviationChart.test.tsx | 2 | 空态/偏离摘要 |
| 前端 e2e | allocation.spec.ts | 1 | 套模板全链路 |

- **覆盖率**：`make test` 全绿——后端 JaCoCo 覆盖门（≥80% 通过）；前端 44 测试文件 V8 语句 95.32% / 分支 89.25%；collector 未受影响 95.56%。

### 暴露的返工点（本轮）

| 返工点 | 现象 | 根因 | 兜底者 |
|---|---|---|---|
| 集成测试用户 id 冲突 | 全量 `make test` 下 AllocationPlanRepositoryImplTest 4 用例 DuplicateKeyException | 测试种子 `app_user(id=1)` 与 AdminSeedRunner 在共享容器占用的 id=1 撞车；单类隔离跑不暴露 | 全量 make test |
| Flyway 版本断言未更新 | FlywayMigrationIntegrationTest 断言 `containsExactly("1".."5")` 失败 | 新增 V6 但没同步更新迁移数量断言 | 全量 make test |
| 生效唯一性无 DB 约束 | 并发 activatePlan 可产生两个 active 行 → /deviation 500 | 计划把「本层不做唯一性约束」defer 到服务层，读侧单结果 Optional 在脏数据下抛异常 | 终审（opus） |
| 前端权重和浮点精度 | 小数权重（33.33+33.33+33.34）被 `sum !== 100` 误拒 | 计划代码用精确相等，spec 风险表明明写了「含容差」 | 终审 |
| 空态缺 testid | e2e 定位不到空态 | 计划组件空态分支漏加 `data-testid` | 实现者现场修复 |

### 方法论经验（值得固化）

1. **隔离跑单类集成测试 ≠ 全量套件**。`./gradlew integrationTest --tests X` 快，但共享 JVM 单例容器的跨类种子冲突只有全量跑才暴露。**写完最后一块集成测试后，必须跑一次全量 `make test`（或至少全量 integrationTest）再宣称完成**——本轮两个集成测试缺陷都是隔离跑全绿、全量跑才红。
2. **管道吞 exit code**。`make test 2>&1 | tail` 让管道退出码 = tail 的 0，把失败伪装成成功。**跑长命令取尾时用 `set -o pipefail` 或根本不加管道，让真实退出码透出**。
3. **加 Flyway 迁移必查「迁移数量/版本断言」**。FlywayMigrationIntegrationTest 硬编码版本列表；任何新 V* 迁移都要同步 grep/更新这类断言。**新增迁移 = 改两处（迁移文件 + 版本契约测试）**。
4. **「服务层唯一性」不能替代「读侧依赖的不变量」**。`findActiveByUserId` 返回单结果 Optional，意味着「至多一个 active」是读侧正确性的前提——这类不变量应下沉到 DB（部分唯一索引），而不是只在服务编排里保证。**读路径依赖的约束，要在持久化边界强制**。
5. **spec 里写了「含容差」，计划代码就要用容差**。计划把 spec 风险表的「校验和=100%（含容差）」翻译成了精确相等，终审才抓回。**写计划代码时对照 spec 的风险/精度条款逐条落地，别只复制 happy path**。

### 待观察 / 下轮关注

- PR #10 的 CI 与 review 反馈。
- `make smoke`（真实行情 + AI 对话）仍未跑，合并前建议补一次端到端冒烟。
- 下轮 MS-05 计划编写直接套用：FR→实现 可追溯矩阵 + 覆盖率逐任务下沉 + 迁移版本断言同步 + 隔离/全量两段验证。
