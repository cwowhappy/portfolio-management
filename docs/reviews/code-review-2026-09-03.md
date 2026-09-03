# 代码审查 2026-09-03（三端全量）

> 分支：`chore/code-review-2026-09-03` · 范围：backend / frontend / collector 全量（约 510 个源文件）· 方法：mattpocock 双轴（Standards 规范轴 + Spec 规格轴），6 个并行只读子代理。
>
> **核验说明**：全部 **13 处 P1 已逐条对照源码复验属实**（含 2 处两轴独立命中的收敛点）；P2/P3 为子代理报告（引用行号经抽查可信，未逐条复验）。无 P0。

---

## 一、Standards 轴（规范 + 代码坏味）

规范源：`docs/technology/conventions/01~04`、`AGENTS.md`、ADR（0003/0005~0009）、`docs/code-review-lessons.md`（问题模式清单）+ Fowler 坏味基线。

### backend（24 条：P1×3 / P2×21）

**P1**

- `[P1][hard]` `AllocationPlanJpaRepository.java:15-17` — `deactivateAllByUserId` 的 `@Modifying` 批量 UPDATE **缺 `clearAutomatically=true`**。`activatePlan`（`AllocationApplicationService.java:57-62`）先 `requirePlan` 载入实体（快照 active=true），再批量把 DB 行置 false，最后 `save(plan.activate())` 因脏检查认为 active 未变而**不发 UPDATE**。结果：对当前已生效方案再次点「激活」，API 返回已激活但 DB 里该行被置 inactive，用户变成无生效方案（偏离度空态）。确定性复现，违反幂等语义。✅已核验
- `[P1][judgement]` `PositionJpaEntity.java:18-21`（全仓无 `@Version`、无 version 列）— 持仓/组合/分组/方案/journal 都是「读-改-整行 merge 写」的钱账聚合且**无乐观锁**：并发双提交（双标签/同标的）两条 trade 都入库，但后写覆盖前写 delta → quantity/costBasis/netCashFlow 与 trade 历史静默分叉。违反 lessons「读路径依赖的不变量要在持久化边界强制」。✅已核验
- `[P1][judgement]` `PortfolioApplicationService.java:217-228` vs `:182-215` — 同一 Position 聚合存在**两条不一致的变更路径**：`addCashDividend` 用「当前实时数量」(`cashPerShare × position.quantity()`)，`editTrade` 走 `replay` 用「事件日当时数量」按时间序重放。补录一笔历史分红按当前数量记账，之后任意一次无关 editTrade 重放会用历史数量**静默改写**钱账数字。✅已核验

**P2**

- `[P2][hard]` `domain/screening/` 无 `ScreeningErrorCode`；`ScreeningApplicationService.java:22/25/28` 用裸字符串 `SCREENING_NO_CONDITION/INVALID_SORT/INVALID_LIMIT`（B2 违反，对比其余 7 域均有 `XxxErrorCode`）。
- `[P2][hard]` `BuyCommand.java:16`/`SellCommand.java:13`/`EditTradeCommand.java:12` — fee 仅 `@NotNull` 无 `@DecimalMin("0")`，负手续费可写坏成本与已实现盈亏；stockCode/stockName 无 `@Size` 对齐 DB 列（超长等到 DB 约束违例变 400）。
- `[P2][hard]` `Position.java:71` — 裸字符串 `"SELL_EXCEEDS_QUANTITY"`，`PortfolioErrorCode.java:8` 已有同值常量未引用（B2）。
- `[P2][hard]` `domain/valuation/Percentile.java:17` — 分位用 double 算（`below * 100.0 / history.size()`），违反 C3「分位属精确计算用 BigDecimal」。
- `[P2][hard]` `domain/valuation/ValuationException.java` — 死代码 + valuation 域无 `ValuationErrorCode`、无 GlobalExceptionHandler 映射，未来抛出落 500。
- `[P2][hard]` `ScreeningRepositoryImpl.java:30-43` — `/api/screening/**` 是 `permitAll`，每次匿名请求对「最新交易日全市场」执行 `LEFT JOIN (DISTINCT ON 全表)` 宽扫，**无缓存、无限流**（E2 违反）；同款 `/api/valuation/industries`（`ValuationApplicationService.java:65-81`）未缓存（overview/history 已缓存）。
- `[P2][hard]` `AllocationPlan.java:63-77` + `AllocationPlanWeightJpaEntity.java:29-30` + `V6__allocation.sql:19` — 域「权重和=100」用**全精度** `compareTo(HUNDRED)` 校验，但 DB `NUMERIC(18,4)` 静默四舍五入：`33.33333×3` 通过校验后入库变 99.9999，读回违反域不变量，下次编辑被误拒。
- `[P2][judgement]` `PortfolioApplicationService.java:385-391` — `quoteQuietly` catch `RuntimeException`（含 NPE 与 `RATE_LIMITED`）吞掉返回 null **且不记日志**；叠加共享行情限流 5/s，持仓多时 overview/allocation 静默当「无行情」，总资产/盈亏被低估无提示。
- `[P2][judgement]` `AllocationApplicationService.java:102-115` — 偏离度映射 `switch(slice.category())` 匹配中文文案「权益」/「现金」，跨服务以 UI 文案作契约 + `default→null` 静默丢未知类。
- `[P2][hard]` `ConversationController.java:49-54` + `ChatMessageWire.java:21-34` — `saveMessages` 裸 `@RequestBody List<ChatMessageWire>` 无 `@Valid`、wire 零 Bean Validation，唯一「结构性校验不走注解」的入口（H8）。
- `[P2][judgement]` `ActiveUserFilter.java:24-29` vs `SecurityConfig.java:50-53` — `shouldNotFilter` 放行名单（market/valuation/health）与 `permitAll` 集合不一致：screening、`/api/agent/status`、`/actuator/**` 同为公开端点却不在放行名单，停用用户访问 screening 得 401、market 得 200。
- `[P2][judgement]` `PortfolioApplicationService.java:79-86` + `V5__portfolio.sql:64` — `deleteGroup` 只拦「组内还有持仓」，不拦 cash_transaction 流水；`ON DELETE CASCADE` 静默删光整组现金流水，账户现金账本消失。
- `[P2][judgement]` `Position.java:110`（同 `AllocationPlan.java:47/52/56`、`JournalEntry.java:77`、`User.java:29`）— 域变更方法直接 `Instant.now()`，与 `Conversation.create(id,userId,now)` 可注入模式不一致，A3「时间可注入」未落实。
- `[P2][hard]` `CacheConfig.java:12-16` — `MAX_ENTRIES=1000` 硬编码不读 `InvestProperties`；TTL 调用侧硬编码（`ValuationApplicationService.java:24` 5min、`HealthController.java:26` 30s）。
- `[P2][judgement]` `MarketController.java:29-61`/`ScreeningController.java:26-47`/`ValuationOverviewView.java:10` — 直接以 `domain.*` 对象作 `@ResponseBody`（A1 边界）；8 个域异常 `getCode()`/`code()` 不统一、无公共接口。
- `[P2][judgement]` `JournalApplicationService.java:69-97` — `timeline()` 把全量 journal + positions + trades/dividends 整表载入内存再过滤，未下推日期范围（已有 `(user_id, event_date DESC)` 索引）。
- `[P2][judgement]` `PortfolioController.java:80-97` — `POST /positions/buy|sell|cash-dividend|stock-dividend` 返回 200，同类创建（group/plan/journal）返回 201，语义不一致。
- `[P2][judgement]` `InvestProperties.java:220` + `application.yml:63` — remember-me 签名 key 提供**已知硬编码兜底** `invest-agent-remember-me`（生产漏配则静默以公开 key 运行）；`AdminSeedRunner.java:33-38` 种子管理员密码不校验 PasswordPolicy。
- `[P2][judgement]` `Conversation.java:39` — 自动标题 `substring(0,24)` 按 UTF-16 码元截断，可能劈开代理对。
- `[P2][judgement]` `ChatMessage.java:7-11` + `ChatMessageWire.java:25` — `role` 存裸 String 无域枚举（Primitive Obsession）。
- `[P2][judgement]` `StockRef.java:17-34` vs `MarketDataParser.java:188-196` — 沪深/北交所市场判定规则两处重复实现且依据（代码前缀 vs MktNum）不完全一致。

### frontend（8 条：P1×2 / P2×6）

**P1**

- `[P1][judgement]` `ThreadArea.tsx:404-420`（配合 369-385、439-447）— 切线程时防抖持久化 effect 把旧线程 `agent.messages` 快照绑定**新** threadId；历史回灌慢于 400ms 防抖窗口或加载失败（catch 不再 setMessages）时，`flushPersist(newThreadId, oldMsgs)` 把旧会话内容 PUT 覆盖新线程服务端记录。注释（387-389）只防了反方向。✅已核验
- `[P1][judgement]` `IndustryBoard.tsx:31` + `ScreenerBoard.tsx:12` — 行业→筛选 drill-through 携带的 `industryCode` query 无人消费：`router.push('/screener?industryCode=…')` 但 `ScreenerBoard` 不读 URL/`useSearchParams`，跳转后落到空筛选器（点筛选还报「请至少填写一个筛选条件」）。功能链路断裂。✅已核验（与 Spec·frontend 印证）

**P2**

- `[P2][hard]` `app/api/portfolio/[...path]/route.ts:5-8`、`allocation`、`conversations` — 反代拼接上游路径丢弃 query string，与 journal/admin 不一致；`portfolioApi.fetchPositions(groupId?)` 的 `?groupId=` 被静默丢弃。
- `[P2][hard]` 5 条 GET-only 反代路由绕过 `relay()` 手写裸 fetch：`market`、`screening`、`valuation`、`agent/health`、`agent/status` — 重复实现 502 兜底/Content-Type/超时且已漂移（health 10s、其余 15s，错误文案各不同）。
- `[P2][judgement]` lib 层 `request`/`get` 帮助函数整段重复（Fowler Duplicated Code）：`portfolioApi`/`allocationApi`/`journalApi` 逐字相同；`screeningApi`/`valuationApi` 相同；`api.ts` 是另一变体。
- `[P2][judgement]` 三个看板 `reload` 无取消/序号守卫：`PortfolioBoard.tsx:24-48`、`AllocationBoard.tsx:17-23`、`JournalBoard.tsx:17-23` 并发 `Promise.all` 拉全量，旧响应可覆盖新状态。
- `[P2][judgement]` `BuyForm.tsx:14` — `groupId` 初始 state 取 `groups[0]?.id` 但 groups 异步加载，首次渲染固化为 `""`，未手选分组直接提交则 `Number("")=0` 无效 groupId。
- `[P2][judgement]` `MarketBoard.tsx:75-89` — 搜索请求无序号守卫（`select()` 有 `requestSeqRef`，`onQuery` 只有 300ms 防抖）。

### collector（8 条：P1×1 / P2×7）

**P1**

- `[P1][hard]` `repositories/tasks.py:18-27` — `UPSERT_TASK` 的 `ON CONFLICT DO UPDATE SET` **漏掉 `enabled`**，re-seed 永远无法同步 YAML 的启停；全仓对 `collector_task` 唯一写路径就是这条 upsert（无独立 UPDATE/DELETE），且调度器每次启动 `seed_tasks` 后 `list_enabled`。已入库任务改 `enabled:false`（或删除 YAML）后仍被继续调度。✅已核验

**P2**

- `[P2][hard]` `config.py:13-19` + `cli.py:51-54` — C3「按命令分级声明所需键」未实现，`list`/`history`/`seed` 等只读命令强制要求 `TUSHARE_TOKEN`。
- `[P2][hard]` `executor/executor.py:56-60` — converter/validator 原生异常未包装，逃出后无 failed-run 记录（只记 error 日志），违反 O3。
- `[P2][judgement]` 缺 `min_rows` 的任务在空数据下「0 行 SUCCESS」静默通过：`index_valuation`/`treasury_yield_curve`/`index_constituent` 无 min_rows，`required` 对空列表空洞通过。
- `[P2][judgement]` `executor.py:63-69,98-101` + `selector.py:9-14` — calc/validator 故障被当作源故障累计进熔断，确定性代码 bug 把健康源打到 open。
- `[P2][judgement]` `scheduler/jobs.py:125-132` — 所有 job 统一 `misfire_grace_time=3600`，7 天/180 天 interval 任务过小，宕机超 1h 静默跳过整周期。
- `[P2][judgement]` `jobs.py:268-285,303-305,134-142` — 日历陈旧告警粒度粗且会被刷新异常吞掉；`refresh_calendar` 放启动关键路径，akshare 不可达则进程起不来。
- `[P2][judgement]` `sources/plugins.py:74-89,205-219` — 日频 tushare 源只取「今天」，失败日不回补，叠加无 min_rows 形成静默数据缺口。

---

## 二、Spec 轴（需求符合度）

规格源：`features/*/01-requirement/需求规格说明.md` + `02-plan/`（或 `02-design`/`03-plan`）。

### backend（7 条：P1×1 / P2×4 / P3×2）

- `[P1][wrong]` `ValuationApplicationService.java:121-135` — `erpPercentile` 用**沪深300股息率分位**近似 ERP 分位，未对历史序列逐日做「股息率−10Y国债」；仓库已回填 `treasury_yield_curve` 历史却弃用。FR-B2「ERP 基于回填历史的分位」展示数据系统性失真（10Y 国债 2021–2024 下行超 1.5pct）。✅已核验
- `[P2][missing]` `EditTradeCommand.java:8-13` + `PortfolioController.java:110-114` — FR-A2「编辑持仓可改所属分组」缺失：只有 editTrade（改日期/价格/数量/手续费），无改分组端点/命令。
- `[P2][wrong]` `PortfolioApplicationService.java:280-299,305-326,381-391` — NFR「现价批量查询，禁止逐只串行调用行情」未遵守：逐持仓单点 `quote(code)`，无批量方法，仅靠 TTL 缓存与限流缓解。
- `[P2][wrong]` `AllocationPlan.java:74` — 需求「权重校验和=100%**（含容差）**」实现为精确 `compareTo(HUNDRED)!=0`，`33.33×3` 被拒（实现跟了 P1 计划而非需求）。
- `[P2][missing]` `JournalEntry.java:105-112` — FR-D1「复盘不绑定个股（stockCode 为空）」未强制；`resolveTrade` 传 tradeId 时回查注入 stockCode/stockName，复盘可被写成绑定个股/交易。
- `[P3][wrong]` `PortfolioApplicationService.java:261-270` + `PortfolioRepositoryImpl.java:71-80` — FR-A4「卖出全部后不再出现在持仓列表」，后端 `findPositionsByPortfolioId` 返回含 quantity=0 的清仓行（低置信，前端可能已过滤）。
- `[P3][missing]` `ValuationHistoryView.java:9-14` + `ValuationApplicationService.java:87-92` — FR-B6 要求 5 指数走势，`history()` 只返回沪深300 单指数序列（低置信，可能是刻意收窄）。

### frontend（9 条：P1×3 / P2×4 / P3×2）

- `[P1][missing]` `GroupManager.tsx:83-199` — FR-B1「分组支持增/删/改名；删分组前须先清空其下持仓」：`deleteGroup` 已在 `portfolioApi.ts:36` 导出、后端 `DELETE /groups/{id}` 已在，但**前端无任何组件调用**，删分组交互整体缺失。
- `[P1][wrong]` `ScreenerBoard.tsx:11-31` — FR-B3/FR-C3「从 /industry 点击行业 → /screener 自动带入行业条件」：跳转侧已 push `industryCode`，但 ScreenerBoard 从不读 URL，行业下拉仍「全部」，提交还报「请至少填写一个筛选条件」。✅已核验（与 Standards·frontend 印证）
- `[P1][missing]` `ValuationBoard.tsx:44-68` — FR-B8「估值页显著位置展示免责声明」：整页无 Disclaimer（共享 `Disclaimer.tsx` 只在 /screener、/industry 渲染）。
- `[P2][scope-creep]` `OverviewCards.tsx:13-16`（+`types.ts:190`/`schemas.ts:180-184`）— 额外渲染第 5 张「累计分红」卡并把 `totalCashDividend` 加进契约，超出 FR-C1（四卡）。
- `[P2][missing]` `ValuationBoard.tsx:55` — FR-B4「破净股数量与占比」：只显示占比，`netBreakerCount` 已在类型/schema 却未渲染数量。
- `[P2][missing]` `EntryEditor.tsx:30-55` — FR-B1「stockCode 必填（除非提供 tradeId）；targetPrice/stopLoss>0」、FR-D1「period 必填、periodStart≤periodEnd」前端校验缺失。
- `[P2][wrong]` `ThreadArea.tsx:353-366` — 401 处理：AI 对话请求遇 401（如使用中被停用）只 `setSendError` 展示错误，页面停留登录态不跳登录（`/api/auth/me` 401 已覆盖跳登录，但 AI 流未覆盖）。
- `[P3][scope-creep]` `ScreeningResultsTable.tsx:14` — 新增「流动比率」可排序列，超出 P3 计划 COLUMNS 契约。
- `[P3][missing]` `JournalBoard.tsx:17-23` — FR-E2「日期范围过滤 ?from=/?to=」：`journalApi.fetchTimeline(from,to)` 与后端均支持，但无任何日期过滤 UI。

### collector（9 条：P1×3 / P2×4 / P3×2）

- `[P1][wrong]` `sources/plugins.py:146-168` — FR-8「supports_range 源支持按区间回填」：`TreasuryCurveSource.fetch` 完全忽略 params start/end，只以 DB `max(trading_day)` 为下界；`backfill.py` 又因 `supports_range=True` 放行。空表时写入整段历史（远超区间），有数据时写 0 行，区间语义被静默破坏。✅已核验
- `[P1][missing]` `tasks/*.yaml` — 8 个任务**全部单源**（如 `all_a_valuation.yaml` 仅 akshare），需求 §六 E「主源故障→自动降级备源」+ FR-6 不可达（executor 的降级循环无任务可降级；且 tushare daily_basic 列结构与现有 field_mapping 也不兼容，非只改 YAML）。✅已核验
- `[P1][missing]` `scheduler/jobs.py:242` — `IndexValuationSource("index_valuation", pro_factory=pro)` 未传 `dividend_fetch` → `plugins.py:82-87` 每行 `dividend_yield` 恒 None；决策#8「指数股息率（补 ERP）」+ §六 C 一期验收核心项未实现。✅已核验
- `[P2][missing]` `validators/rules.py:13-35` — FR-7 内建规则 7 种只实现 3 种（min_rows/required/range），not_null/type/unique/allowed_values 缺失。
- `[P2][wrong]` `sources/plugins.py:275-276` — `StockFinancialSource` 用 `ThreadPoolExecutor(max_workers=4)` 并发拉 12 期无节流，FR-11「客户端限速」缺失（grep 确认 collector 内唯一 time.sleep 是 runner.py:77 重试退避）。
- `[P2][missing]` `scheduler/runner.py:51-53,65-67` — 非交易日/锁冲突 skipped 不落库；无 running 态、无时长（`started_at=finished_at`），FR-10「每次执行记录 task_run（起止/状态）」未完整落实。
- `[P2][wrong]` `sources/plugins.py:259` — `StockFinancialSource` 只剔北交所/老三板，未像 `StockValuationDailySource`（`:203` 含 ST/退过滤）那样剔除 ST/退市，违反 value-screening P1「仅沪深正常交易股」口径。
- `[P3][wrong]` `cli.py:67-70` — `list` 无条件调 `list_enabled()`（只查 `WHERE enabled`），`--enabled-only` 形同虚设，停用任务永远不可列出，且不展示「上次运行状态」列。
- `[P3][wrong]` `store/writer.py:36-41` + `plugins.py:179-188` — `index_constituent` 半年任务只 upsert 不删，被调出指数的股票永久残留旧权重行。

---

## 三、汇总

| 轴 | P0 | P1 | P2 | P3 | 小计 |
|---|---|---|---|---|---|
| Standards（backend/frontend/collector） | 0 | 6 | 34 | 0 | **40** |
| Spec（backend/frontend/collector） | 0 | 7 | 12 | 6 | **25** |
| **合计** | **0** | **13** | **46** | **6** | **65** |

- **Standards 轴最严重**：`AllocationPlanJpaRepository.deactivateAllByUserId` 缺 `clearAutomatically` 导致「对已生效方案重复激活会静默变成无生效方案」——确定性、幂等语义破坏。
- **Spec 轴最严重**：`ValuationApplicationService.erpPercentile` 用股息率分位冒充 ERP 分位、弃用已回填国债历史，使 FR-B2 的核心展示数据失真。
- **两轴印证**：`/industry → /screener` 的 `industryCode` 带入断裂被 Standards 与 Spec 两轴独立命中，且与「已上线的产品联动失效」直接相关，建议最高优先级修复。

> 上轮（2026-08-29）collector 高危模式（事务未回滚、手工 DDL 副本、纯 mock 装配、覆盖率仅手动）经核验**已闭环并纳入 CI/`make test`**；本轮发现集中在并发/幂等边界（backend）、流式竞态与反代契约（frontend）、声明式启停失效与区间回填（collector）。
