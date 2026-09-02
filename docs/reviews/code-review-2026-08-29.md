# 代码深度 Review 报告（2026-08-29）

> 生成时间：2026-08-29 · 分支：`chore/code-review-2026-08-29`
> 范围：后端（Spring Boot/Java）、前端（Next.js/CopilotKit）、数据采集服务（collector/Python）
> 说明：本报告含两部分——对 2026-08-19 旧报告（`docs/reviews/code-review.md`）的逐条核验，以及本轮新发现问题。条目按 P0（高）/P1（中）/P2（低）分级，附 file:line 引用。优化计划见 `features/plans/2026-08-29-CodeReview问题修复优化计划.md`。

---

## 修复状态（2026-08-29 当日闭环）

**本报告全部新发现问题已在分支 `chore/code-review-2026-08-29` 上修复完毕**：P0 × 1、P1 × 9、P2 × 27（后端 7 / 前端 8 / collector 12）。每项修复均附回归测试。关键决策：C-P0-1 采用"每次任务运行新建连接"+ testcontainers 真实 PG 回归；C-P1-1 采方案 A（配置生效）；B-P1-1 采方案 B（拆 `/api/agent/health` liveness 与 `/api/agent/status` 探活）；F-P1-3 用 `useAgent` 原生 `throttleMs: 150`。

验证：`make test` 全绿 —— 后端 242 tests（含 ArchUnit + Testcontainers）BUILD SUCCESSFUL；前端 27 文件 208 tests 通过（语句 97.85% / 分支 88.19%）；collector 101 passed（覆盖率 88.96%）。`make test` 自此纳入 collector。

遗留（低优先）：TreasuryCurveSource 增量按 `max(trading_day)` 过滤，backfill 更早区间时该源仍只补新数据；`retry_max` 语义定为"重试次数"（总尝试 = retry_max+1）；CI collector job 待推送后观察首跑；admin 重置密码弹窗未配组件级测试。

---

## 总体评价

上轮（2026-08-19）报告的 **3 个 P0 全部修复**，后端 P1/P2 大部分闭环且附回归测试，修复质量高。前端竞态/串写类问题修得彻底（running 闸门 + abortRun + 防抖快照）。

本轮新增覆盖 **collector 采集服务**（上轮未审），发现 **1 个 P0**：DB 写失败后事务未回滚，导致失败记录写入与整任务重试同时失效——采集链路最关键的失败路径上，重试与运行日志实际都不工作。另有若干"静默断更"类隐患（交易日历永不更新、冷启动缝隙）。

本轮新发现问题：**P0 × 1（collector），P1 × 9（后端 3 / 前端 4 / collector 2+3），P2 若干**。

---

## 一、上轮问题核验结果（2026-08-19 报告）

### 后端条目 1–11

| # | 问题 | 结论 | 证据 |
|---|---|---|---|
| 1 | TtlCache 无界 | ✅ 已修复 | `TtlCache.java:32-37` 有界 LRU（maxEntries=10000） |
| 2 | 限流被 retry/降级绕过 | ✅ 已修复 | `HttpExecutor.java:46-51` 每次 HTTP 尝试前 tryAcquire，三客户端共享 RateLimiter |
| 3 | estimateValuation substring 崩溃 | ✅ 已修复 | `OrchestratingMarketDataService.java:109-114` 长度+格式守卫，有回归测试 |
| 4 | retry/降级逻辑重复 | ✅ 已修复 | 重试抽为 `HttpExecutor`，降级抽为 `withFallback`（:152-159） |
| 5 | 三个 HttpClient 各自建池 | ✅ 已修复 | `MarketConfig.java:15-23` 单一 HttpClient bean |
| 6 | RateLimiter 持锁 sleep | ✅ 已修复 | `RateLimiter.java:37-58` sleep 移出锁、统一 nanoTime、时钟可注入 |
| 7 | 解析健壮性 | ✅ 已修复 | `MarketDataParser.java:201`（isArray）、:30（f86）、:262/:285（时区）、:289-298（指数 code） |
| 8 | 异常处理一致性 | ⚠️ 部分修复 | 已修 AgentConfig hasText、4xx 不重试、未知 code 记日志；残留：`InvestTools.run` 仍 catch(Exception)、`DEEPSEEK_API_KEY` 散落两处 |
| 9 | magic number | ⚠️ 部分修复 | 大部分入配置；残留：`InvestTools.java:71` 默认 60 vs `MarketController.java:42` 默认 120 口径不一致 |
| 10 | probeQuoteLatencyMs 报缓存延迟 | ✅ 已修复 | `CachedMarketDataService.java:74-77` 绕过缓存（但引入新问题 B-P1-1） |
| 11 | news() keyword 空值 | ✅ 已修复 | `OrchestratingMarketDataService.java:126-132` isBlank 守卫 |

### 前端条目 12–21

| # | 问题 | 结论 | 证据 |
|---|---|---|---|
| 12 | MarketBoard 竞态 | ✅ 已修复 | `MarketBoard.tsx:50,93,106,119` requestSeqRef 序号守卫 |
| 13 | 流式写放大 | ⚠️ 部分修复 | 持久化已 400ms 防抖（`ThreadArea.tsx:344-362`）；渲染侧仍每 token 全树重渲染（见 F-P1-3） |
| 14 | 跨线程污染 | ✅ 已修复 | `RuntimeProvider.tsx:143-154` running 闸门 + `ThreadArea.tsx:332` abortRun |
| 15 | randomUUID 无 fallback | ✅ 已修复 | `lib/conversations.ts:21-24` |
| 16 | send 无错误处理 | ✅ 已修复 | `ThreadArea.tsx:309-322` try/catch + UI 错误条 |
| 17 | tool/reasoning 历史丢弃 | ➖ 已决策接受 | `RuntimeProvider.tsx:57-62` 注释引 ADR-0004 |
| 18 | 无 error boundary | ✅ 已修复 | `app/error.tsx` |
| 19 | get\<T\> 无运行时校验 | ⚠️ 部分修复 | api/conversations/valuationApi 已 zod；残留 `lib/adminApi.ts:18-20` cast、`lib/auth.tsx:31` `Promise<any>` |
| 20 | proxy 吞错误/Content-Type | ⚠️ 部分修复 | market/valuation/health 已修；残留 auth/admin/conversations 走 relay() 仍无超时无兜底（见 F-P1-1） |
| 21 | 杂项 | ✅ 大部分修复 | 残留：`timeAgo` 仅渲染期计算（`Sidebar.tsx:5-13,62`） |

### 测试与配置条目 22–25

| # | 问题 | 结论 | 证据 |
|---|---|---|---|
| 22 | app/ 层零测试 | ⚠️ 部分修复 | 已新增 route handler 测试（proxy/copilotkit/routes）+ Playwright e2e；但 `vitest.config.ts:20` 覆盖 include 仍不含 `app/**` |
| 23 | 时序依赖/慢测试 | ✅ 已修复 | RateLimiter/TtlCache 测试均注入假时钟 |
| 24 | 弱断言 | ✅ 已修复 | 后端 MockMvc + jsonPath；前端 getByTestId |
| 25 | 配置缺陷 | ✅ 大部分修复 | pnpm-workspace 占位符、backend Dockerfile curl、CI workflow、application-prod.yml 均已修；残留：前端无 lint 脚本（AGENTS.md 已如实记录） |

---

## 二、新发现问题

### 采集服务 collector（首次纳入审查）

**C-P0-1 StoreError 后事务未回滚，失败记录与整任务重试全部失效**
位置：`collector/collector/store/writer.py:61-66`、`collector/collector/executor/executor.py:70-72`、`collector/collector/scheduler/runner.py:38-45`。
`Store.upsert` 捕获 `psycopg.Error` 后不 `conn.rollback()`，连接停留在 aborted transaction 状态。executor 随后在 `except StoreError` 里 `run_repo.record(...)` 必抛 `InFailedSqlTransaction`，且该异常逃出 TaskRunner 的 except 捕获范围。后果：①失败 run 记录写不进库；②原始错误被吞；③重试复用同一已中止连接，第一次查询即失败。即"DB 写失败"这条最关键路径上重试和日志都不工作。现有测试全用 MagicMock 恰好掩盖此问题。
→ `upsert` 的 except 分支加 `conn.rollback()`；`TaskRunner` 每次重试新建连接；补真实 DB 回归测试。

**C-P1-1 任务的 retry_max/retry_backoff 是死配置**
位置：`collector/collector/model/task.py:16-17`、`collector/tasks/*.yaml`、`collector/collector/scheduler/runner.py:16`。TaskRunner 硬编码 `retry_max=3, backoff=(30,60,120)`，从不读 task 上的字段。
→ runner 内消费 task.retry_max/retry_backoff，或从 schema 删除。

**C-P1-2 交易日历只初始化、永不更新，跨年后所有任务静默停摆**
位置：`collector/collector/scheduler/jobs.py:150-167`。日历耗尽后 `is_trading_day` 恒 False，所有 trading_day_gated 任务静默跳过、无告警。
→ 日历刷新做成定期任务（如每月），load_calendar 周期重载。

**C-P1-3 冷启动缝隙：industry_valuation 前 7 天必失败**
位置：`collector/collector/sources/plugins.py:88-96`、`collector/tasks/shenwan_mapping.yaml`（interval 7 天）。IntervalTrigger 首次触发在启动后一个间隔，期间 join 为空 → min_rows hard → 每天 AllSourcesFailed。
→ 对"从未成功运行"的任务启动时立即补跑一次（next_run_time=now）。

**C-P1-4 backfill 日期参数格式不一致**
位置：`collector/collector/cli.py:37-38`、`collector/collector/backfill.py:5`、`collector/collector/sources/plugins.py:15-23`。`_date_param` 只归一化 `date` 键，`start`/`end` 带横杠原样传给 tushare（惯例 YYYYMMDD）；`tests/test_backfill.py:16` 反向固化了该 bug。
→ 三个键统一归一化，修正测试断言。

**C-P1-5 collector 不在主测试/CI 流程内**
位置：`Makefile:24`、`.github/workflows/ci.yml`。`make test` 不含 collector，CI 无 collector 步骤，80% 门槛只手动生效。
→ `make test` 纳入 collect-test，CI 加 collector job。

**C-P2（可维护性，摘选）**
- 半开源优先级倒挂：`executor/selector.py:43` probes 排在健康候选前
- 熔断计数被重试放大：`executor.py:81` 按尝试次数而非任务运行计
- advisory lock 键用 crc32（`runner.py:11-12`），建议 hashtextextended
- `finished_at` 列从不写入（`repositories/runs.py:25-41`）；record 对未知任务静默返回 None
- Store 双份表结构定义易漂移（`writer.py` UPSERT_SQL vs TABLE_COLUMNS）；未知 target_table 抛裸 KeyError
- 迁移重复执行：`Dockerfile:12` CMD 与 `jobs.py:172-174` main() 各跑一次
- TreasuryCurveSource 每日全量重写（`plugins.py:109-118`），建议按 max(trading_day) 增量
- `field_mapping_sw` 把 stock_name 映射自 code（`jobs.py:107`），语义绕过 NOT NULL
- `_coerce` 漏判 pd.NA/NaT（`converters/field_mapping.py:8`），可能把 `"<NA>"` 写库
- Config 僵化：缺 env 裸 KeyError、list/history 也强制 TUSHARE_TOKEN、import 时 load_dotenv 副作用
- `--date` 与交易日门控不一致（`runner.py:24` 用 today）；非法日期抛裸 ValueError
- 不可区间源 backfill 静默降级（`executor.py:65-67`），应拒绝
- 可观测性弱：无 logging、APScheduler 无 misfire_grace_time、`calc/snapshot.py:26` KeyError 逃逸无 failed run 记录
- Docker 镜像 root 运行、无 healthcheck

### 后端（新问题）

无 P0（认证/授权、会话隔离、越权接管、密码哈希、SQL 注入均未发现绕过）。

**B-P1-1 公开健康检查消耗真实上游配额**
位置：`HealthController.java:42-49` + `SecurityConfig.java:51`（permitAll）+ `OrchestratingMarketDataService.java:145-150`。匿名刷 `/api/agent/health` 即耗尽 5/s 令牌桶，全体用户报 429；docker healthcheck 每 20s 也在烧配额。
→ 探活结果加短 TTL 缓存（如 30s），或 health 端点降级为只报 LLM 配置状态。

**B-P1-2 `/api/valuation/**` 匿名开放 + 每请求多次全表扫描无缓存**
位置：`SecurityConfig.java:52`、`ValuationApplicationService.java:27-48,76-108,110-125`。估值按交易日更新却每请求重算全量历史+分位，匿名可触发，DB 放大型 DoS 面。
→ overview/history 加按交易日短 TTL 缓存，HS300/国债序列单次加载复用。

**B-P1-3 会话消息写入无边界校验，可 500 且可存储滥用**
位置：`ChatMessageWire.java:6`（record 零校验）、`ConversationApplicationService.java:48-56`、约束见 `V2__conversation.sql`（message_id 64 / role 16 / content NOT NULL）。超长字段落库报 DataIntegrityViolation→500；消息条数与 content 长度无上限。
→ wire→domain 边界校验 role 白名单/长度上限/条数上限，约束违例映射 400。

**B-P2（摘选）**
- 年报路径 PE 无 eps>0 守卫：`OrchestratingMarketDataService.java:91-92` eps=0 输出 Infinity，与 TTM 路径口径不一致
- `erpPercentile` 死变量 + 重复查询：`ValuationApplicationService.java:99-101` treasuryHistory 加载后未使用
- 注册并发唯一冲突 → 500：`AuthApplicationService.java:34-41` TOCTOU，未映射 DataIntegrityViolation→400 USERNAME_TAKEN
- USER_NOT_FOUND 映射为 400：`GlobalExceptionHandler.java:32-36`，语义应为 404
- 重置密码不失效旧 remember-me token：`UserAdminApplicationService.java:48-52`
- remember-me key 硬编码：`SecurityConfig.java:28`
- 登录响应可探测账号状态：`AuthController.java:57-65`（403 vs 401 区分，设计取舍，建议文档明确）
- 会话固定无回归测试：`AuthController.java:66-71` 未断言登录前后 session 轮换
- kline 默认口径不一致残留：`InvestTools.java:71`(60) vs `MarketController.java:42`(120)

### 前端（新问题）

无 P0（ReactMarkdown 未开 rehype-raw 无 XSS 面；cookie 透传方向正确）。

**F-P1-1 auth/admin/conversations/copilotkit 反代无超时、无错误兜底**
位置：`lib/proxy.ts:26-39`、`app/api/conversations/[[...path]]/route.ts:5-18`、`app/api/auth/*`、`app/api/admin/[...path]/route.ts`。后端挂起时请求无限挂起或冒泡成 Next.js 500 HTML，与 market 路由 502 JSON 行为不一致。
→ relay() 内统一加 `AbortSignal.timeout` 与 catch→502 JSON，手写 fetch 路由收口到 relay。

**F-P1-2 持久化防抖在卸载/停止时丢尾部内容**
位置：`components/chat/ThreadArea.tsx:344-362`。cleanup 只 clearTimeout 不 flush，停止后 400ms 窗口内切页/卸载 → 最后一段回复不落库。
→ cleanup 或 isRunning true→false 时立即 flush。

**F-P1-3 流式渲染放大残留**
位置：`components/chat/ThreadArea.tsx:296-299,375-392`。每 token 全树重渲染 + ReactMarkdown 全量重解析，O(n²)。
→ useAgent throttle（若支持）或 AssistantMessage React.memo 按消息隔离。

**F-P1-4 persistMessages 每次保存后全量 listConversations()**
位置：`components/chat/RuntimeProvider.tsx:170-181,123-125`。流式期间每 400ms PUT+GET 全量列表 → Sidebar 整体重渲染。
→ 本地乐观更新 updatedAt/title，仅新会话创建时 refresh()。

**F-P2（摘选）**
- 新增客户端层未走 zod 且出现显式 any：`lib/adminApi.ts:18-20`、`lib/auth.tsx:31`（补 AuthUserSchema/AdminUserViewSchema）
- AuthProvider context value 未 memo：`lib/auth.tsx:83`
- logout 失败路径状态不一致：`lib/auth.tsx:70-73`（try/finally setUser(null)）
- 管理员重置密码用 window.prompt 明文：`app/admin/page.tsx:58-62`
- Admin 面板小竞态：`app/admin/page.tsx:32-43` refresh 无 cancelled 守卫
- `app/api/auth/login/route.ts:21-27` GET handler 死代码
- FeedbackBar localStorage 键无界累积：`ThreadArea.tsx:66-77`
- 无安全响应头：`frontend/next.config.ts`
- `app/error.tsx:23` 直接展示 error.message 可能泄露内部细节
- app/ 覆盖门槛残留：`vitest.config.ts:20` include 不含 `app/api/**`

---

## 三、做得好的地方 ✅

- **collector**：源/转换/校验/计算/存储分层 + 注册表插件化 + YAML 声明式任务；SourceError/StoreError 异常分层语义得当；upsert 幂等性与 DB 唯一键逐表吻合；SQL 全参数化；近期 bug 均有回归测试且注释解释"为什么"。
- **后端**：越权防护有意识（`ConversationApplicationService.java:33-38` 主动防 merge 接管）；新增域严格守分层并在 ArchUnit 白名单登记；会话隔离测试扎实；Flyway 契约意识（V3/V4 头部注明跨服务写入契约）；ActiveUserFilter 位置与注释正确。
- **前端**：反代层细节到位（多值 Set-Cookie 逐个 append、204/304 置空 body，均有测试锁定）；会话串写修复彻底；认证/管理模块测试齐（单测 + Playwright e2e）。

---

## 四、Top 优先修复表

| 优先级 | 问题 | 风险/收益 |
|---|---|---|
| P0 | C-P0-1 collector 事务未回滚 | DB 写失败路径上重试与日志全部失效，故障时无迹可查 |
| P1 | C-P1-2 交易日历永不更新 | 跨年后全部任务静默断更 |
| P1 | B-P1-1 健康检查烧上游配额 | 匿名可致全站 429 |
| P1 | B-P1-2 valuation 匿名全表扫描 | DB 放大型 DoS 面 |
| P1 | F-P1-2 防抖丢尾部内容 | 回复末尾不落库，用户可见数据丢失 |
| P1 | C-P1-3 冷启动 7 天必失败 | 新部署 industry_valuation 天天报错 |
| P1 | F-P1-1 反代无超时 | 后端故障时请求无限挂起 |
| P1 | B-P1-3 会话消息无边界 | 约束违例 500 + 存储滥用 |
| P1 | C-P1-1/P1-4 死配置/日期格式 | 配置不可信、回填拉空且被测试固化 |
| P1 | C-P1-5 collector 脱离 CI | 80% 门槛形同虚设 |
