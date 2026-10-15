# 前端深度代码审查报告（2026-10-15 · 第四轮）

> 范围：`frontend/`（Next.js 15 + React 19 + CopilotKit + Tailwind 4 + echarts 6）。
> 方法：上轮 5 条目修复逐项复发检查（读码 + `git show ec0412fe` 新旧实现 diff 比对）+ 上轮 §5 e2e 专项 + 新角度抽查（admin / intelligence / industry / 图表层 / 包体积）+ 全量量化复扫 + 工具链实测。
> 对照基准：`docs/technology/conventions/03-前端架构与代码规范.md`（下称「规范」）。
> 上轮输入：`docs/reviews/2026-10-12-frontend-review.md`、`features/code-review-2026-10/08-复盘总结/P3-第三批复盘总结.md`。

## 一、架构总览与上轮修复复审结论

**总评：上轮 5 条目（F1~F5）修复质量高，五项全部复审通过，无复发。** `pnpm typecheck` / `pnpm lint`（0 errors 0 warnings）/ `pnpm vitest run`（159 文件 1359 用例全过）/ `pnpm test:bundle`（234.0KB ≤ 240KB）四项实测全绿。逐项结论：

**F1 research 下沉 + useDraftSync——收敛干净。**
- 三个 page 回归薄壳（`frontend/app/research/page.tsx:1` 13 行 / `[id]/page.tsx:1` 15 行 / `new/page.tsx:1` 17 行），逻辑全部入 `components/research/`（ProjectDetail 315 / ResearchBoard 161 / NewProjectForm 115 行），进入 vitest 覆盖网。
- `frontend/components/research/useDraftSync.ts:13-26`（27 行）：`prevDetail` 引用比较守卫正确（PATCH 回填/归档重载/首载均产生新引用，触发草稿重置；用户编辑期间 detail 引用不变则草稿不被打扰）；detail 为 null 时不重置草稿但此时组件渲染加载/错误视图、草稿不可见，无实际窗口；hook 无 effect/订阅，卸载无清理负担——上轮「hooks 灰色实践」已收敛为可评审的派生状态范式，注释引 React 官方「You Might Not Need an Effect」。
- 唯一残留（低、非回归）：PATCH 在途期间标题输入框未禁用（`frontend/components/research/ProjectDetail.tsx:150-155` 无 disabled，保存按钮 `:157` 有），在途继续输入会在回填时被覆盖丢失。下沉前同模式，行为未变。

**F2 z.infer 收敛——等价性成立，但清出死代码。**
- `frontend/lib/types.ts` 609→333 行、84 个 `z.infer` 派生重导出，消费方 import 路径不变；保留项（ChatMessage、请求入参、命令体）注释逐类说明理由，口径清晰（`frontend/lib/types.ts:1-4`）。
- 4 个手写枚举别名逐一比对 schemas.ts 内联 `z.enum` 字面量：GroupType="ACCOUNT"|"TAG" ↔ `frontend/lib/schemas.ts:159`、TradeType ↔ `:172`、CashTransactionType ↔ `:177`，全等；DividendType="CASH"|"STOCK" 与后端 `backend/src/main/java/com/portfolio/invest/domain/portfolio/DividendType.java:3` 一致。
- 抽 2 域对比后端 DTO 字段：行情 StockHit（`frontend/lib/schemas.ts:33-38` 四字段 ↔ `backend/src/main/java/com/portfolio/invest/domain/market/StockHit.java:4` record 四字段完全一致）；情报 NewsItem（`frontend/lib/intelligenceApi.ts:40-49` 八字段 ↔ `backend/src/main/java/com/portfolio/invest/application/intelligence/IntelligenceQueryService.java:206-214` NewsItemView 完全一致）。运行时 schema 与后端契约对齐，无漂移。
- **新发现（低）**：4 个枚举别名 + DividendView（`frontend/lib/types.ts:130-149`）在全仓（components/lib/app/tests/e2e）grep 零消费方——「为兼容性保留」过度保守，实为死代码。

**F3 http.ts withStatus 收敛——语义等价，3 条风险差异逐条复核可接受。**
- 三个私有 request（adminApi/memoryApi/conversations）全部迁入 `frontend/lib/http.ts:25-81` 的 `request()`，新增 `withStatus`/`init` 两可选参数，缺省语义与原 http.ts 完全一致；传输形态差异（admin 全员 credentials+GET 也带 Content-Type）经 `init` 注入且被 `frontend/tests/lib/adminApi.test.ts` 钉死。
- P3 文档未单列 3 条风险，本轮基于 diff 自行枚举全部候选语义差异并逐条验证：①传输层失败由裸抛 TypeError 变为 `Error+status:0`（仅 conversations/memoryApi 启用 withStatus）——消费方 `frontend/components/chat/RuntimeProvider.tsx:283` 与 `frontend/components/chat/ThreadArea.tsx:322` 走 `errorStatus` 属性读取、`frontend/components/memory/MemorySettingsPage.tsx:200` 走 `instanceof MemoryApiError`（wrapper 照常翻译 status>0），全仓无 `instanceof TypeError` 依赖该两域（`frontend/lib/useSaveAction.ts:28` 的 TypeError 判定服务 research/journalApi，均未启用 withStatus）——无消费方破裂；②conversations 的 schema 失败由裸抛 ZodError 变为「数据格式异常」——无消费方 catch ZodError，且更一致；③requestNoContent 等价物（setMcpProviderToken/setEvalBaseline/setPromptAssetNote）对非 204 的 2xx 现在会 `res.json()`——已核后端三端点均 `ResponseEntity.noContent()`（`backend/src/main/java/com/portfolio/invest/web/McpAdminTokenController.java:40`、`EvalTriggerController.java:68,76`），空体 200 路径实际不可达。三条均可接受。
- 附带说明：旧 conversations `createConversation` 的 wire format 从「string body 无 Content-Type（浏览器默认 text/plain）」变为显式 application/json——对 @RequestBody 端点属潜在修正而非风险。

**F4 ThreadArea 拆分——props 接口干净，无 drilling。**
- `frontend/components/chat/ThreadArea.tsx:1-511`：剩余部分为错误翻译（toSendErrorText/isUnauthorizedError）+ 通配工具渲染 + 空态 + 会话编排主体（send/回灌/防抖/三处 flush/409 合并重试），职责单一。复盘偏差 6（511>500）成立但剩余确为编排主体。
- `frontend/components/chat/composer.tsx:11-17` props 仅 4 个（isRunning/ready/onSend/onStop）；`messageRow.tsx:108` 导出 UserMessage/ReasoningMessage/AssistantMessage/buildToolMessageMap，FeedbackBar 随 AssistantMessage 内聚其中（反馈本就属助手消息）；`toolMessageByCallId` Map 一次 memo 构建逐行传入（ThreadArea.tsx:407,461）是数据下推而非 drilling。toolRenderers.tsx 650→119 行，卡片入 `toolCards/` 目录。

**F5 杂项清零——属实。** 上轮 2 个 lint warning 已清（本轮 lint 0 warnings）；confirm 保留有复盘偏差 5 的裁定理由。

**16 个新单测断言质量——行为级，mock 浅。**
- `frontend/tests/components/research/ProjectDetail.test.tsx:71-150`：断言全部是 DOM 态/API 调用参数/错误文案/非法 id 不发请求；mock 仅打 `@/lib/researchApi` 边界（子面板打桩合理，各有独立测试）；`:78-91` 直接钉住 useDraftSync 的 PATCH 回填语义（草稿镜像去空白后的已存值而非原始输入）。
- `frontend/tests/components/research/ResearchBoard.test.tsx:107-130`：seq 守卫用 deferred promise 驱动过期响应后到达，真测并发语义（规范 7.5 的可注入时序范式），非 sleep。
- `frontend/tests/components/research/NewProjectForm.test.tsx:53-90`：searchParams 预填、缺省标题推导、withTemplate 开关全走 API 调用参数断言。无「测实现」嫌疑。

## 二、上轮 §5 遗留 → research e2e 覆盖专项

**结论：覆盖存在且对下沉后组件行为等价，无缺口。** `frontend/e2e/research.spec.ts`（238 行，2 条主链路用例）+ `frontend/e2e/research-report.spec.ts`（490 行）在 F1 commit（ec0412fe）中零改动（git diff 实测）；下沉为纯搬移、DOM/testid 全保留——抽查 e2e 依赖的 10 个 testid（research-empty / research-new / linked-notes / stage-progress / review-list / reflux-confirm / reports-panel / report-content / report-limitations / project-list）全部在新组件中解析存在（如 `frontend/components/research/ResearchBoard.tsx:133`、`frontend/components/research/ProjectDetail.tsx:267`、`frontend/components/research/StageProgress.tsx`）。e2e 断言面对下沉后组件行为等价，且 F1 新增 16 单测补上了组件层直达覆盖，原「覆盖网外」问题闭环。

## 三、做得好的地方（本轮新观察）

1. **admin 可观测性四组件竞态守卫全覆盖**：TraceTable（`frontend/components/admin/observability/TraceTable.tsx:54,60,71,76`）、CostCharts/LatencyCharts（:18-30）、EvalRunsPanel（:48-60）均有 requestSeqRef；EvalRunsPanel 的 RUNNING 5s 轮询 setInterval 有 cleanup（`:71-75`），轮询与手动刷新交错有注释说明守卫口径。
2. **迁移精细度**：`frontend/lib/http.ts:39-47` 把「headers/body 恒为显式键、无 body 时值为 undefined」的测试契约（objectContaining 钉住）写成行内注释，防止后人「顺手简化」破契约。
3. **industry 域取消守卫零缺**：IndustryDrilldown/UnlistedPanel/LandscapeChart/ChainEditorDialog/ChainPanel 六个异步 effect 全部 cancelled 模式（`grep cancelled components/industry/` 全中）。
4. **e2e 与组件层 testid 契约在纯搬移中零破坏**——F1 把「DOM 稳定」当隐形契约维护，16 单测与既有 e2e 因此同时成立。

## 四、不足与风险

### 高

无（正确性/安全面无新高危；上轮修复未引入回归）。

### 中

无。

### 低

1. **types.ts 死代码残留**（F2 副产物）：4 个枚举别名（GroupType/TradeType/DividendType/CashTransactionType）+ DividendView 全仓零消费方（`frontend/lib/types.ts:130-149`，约 20 行）。收敛时为兼容性保留过度，下轮可直接删。
2. **IntelligenceSettingsPage 错误收窄口径不一致**：7 处 `(e as Error).message`（`frontend/components/intelligence/IntelligenceSettingsPage.tsx:79,108,118,145,163,171,183`），非 Error 抛出时渲染 undefined 空文案；与全站 `e instanceof Error ? e.message : "…"` 口径（ProjectDetail/NewsFeedPanel/industry 诸组件均遵守）相悖，规范 5.1 禁 as 断言精神未落实到此文件。同文件挂载 effect（`:70-85`）亦缺 cancelled 守卫（设置页挂载一次、实际风险近零，但属规范 3.1 口径缺口）。
3. **AdminBoard.refresh() 无序号守卫**：`frontend/components/admin/AdminBoard.tsx:221-235`——busyId 按用户行隔离，不同用户行的 act() 可并发触发两个 refresh 交叠，后完成者胜、列表可能闪回非最新快照。admin 后台低频页，实际影响小；规范 3.1「同一资源并发须序号守卫」口径在此缺位（同域 observability 四组件反而都有）。
4. **原生 confirm() 全站 10 处**：journal/EntryList、ProjectDetail、ReportsPanel、ResearchBoard、MemorySettingsPage、Sidebar、IntelligenceSettingsPage、RulePanel、NotePanel、ChainEditorDialog。复盘偏差 5 已裁定「无通用确认弹窗组件，保留并注释」，但站点数较上轮报告口径（2 处）多——均为同类质，若未来做通用确认组件可一揽子替换。
5. **ProjectDetail 编辑在途竞态**（非回归）：PATCH 在途期间标题/阶段输入未禁用（见 §一 F1），回填会静默覆盖在途输入。
6. **包体积余量收紧**：实测 234.0KB ≤ 240KB（`scripts/assert-chart-bundle.mjs`），但距基线 218KB 已涨 16KB，余量仅 2.5%。门槛未破，趋势值得盯——再涨一个 echarts 组件即可能触线。
7. 杂项：`frontend/components/charts/optionBuilders.ts:24` `style.seriesColors!.length` 非空断言（空数组时 i%0=NaN→取下标 undefined 恰好安全，但写法粗糙）；`frontend/app/research/[id]/page.tsx:2` 头注释「研究项目列表页」复制自列表页笔误；`AdminBoard.tsx` 无模块头注释（规范 8.3 精神）。

## 五、技术债清单（可量化）

| # | 条目 | 量化 | 位置 |
|---|---|---|---|
| 1 | ThreadArea 511 行 >500 口径 | 1 文件（复盘偏差 6 已录） | `frontend/components/chat/ThreadArea.tsx` |
| 2 | 原生 confirm | 10 处（偏差 5 裁定保留） | 见 §四-低4 |
| 3 | types.ts 死代码（F2 副产物） | 4 别名 + 1 interface ≈20 行，零消费方 | `frontend/lib/types.ts:130-149` |
| 4 | IntelligenceSettingsPage as-Error ×7 + 挂载 effect 无取消守卫 | 1 文件 | `frontend/components/intelligence/IntelligenceSettingsPage.tsx` |
| 5 | AdminBoard refresh 无序号守卫 | 1 处 | `frontend/components/admin/AdminBoard.tsx:221` |
| 6 | ProjectDetail 在途编辑可被回填覆盖 | 2 输入框（标题/阶段） | `frontend/components/research/ProjectDetail.tsx:150-164` |
| 7 | 图表包体积余量 | 234.0KB/240KB（2.5%） | `frontend/scripts/assert-chart-bundle.mjs` 实测 |
| 8 | 400~500 行组件 | 3 个（AdminBoard 434 / StrategyPanel 415 / MarketBoard 413） | `frontend/components/**` |
| 9 | any 残留（源码）/ fetch 违规 / TODO / console.log | **0 / 0 / 0 / 0**（全量 grep 正向确认） | — |

## 六、改进建议（按优先级）

1. **死代码与口径清零（P1）**：删 types.ts 的 4 个枚举别名 + DividendView（零消费方，删前再跑一次全仓 grep 确认）；IntelligenceSettingsPage 的 7 处 `as Error` 改 `instanceof` 收窄并补挂载 effect 的 cancelled 守卫。
2. **并发守卫补位（P2）**：AdminBoard.refresh() 加 requestSeqRef（照 observability 同域先例）；ProjectDetail 的标题/阶段输入在 acting 期间禁用（消掉在途覆盖竞态）。
3. **结构债（P3）**：ThreadArea 再把「flush 持久化」抽成 `usePersistFlush`（debounce + 三处 flush + 409 合并重试是一完整内聚语义，抽出后编排主体可降约 150 行）；confirm 通用组件化评估（10 处一次换）；optionBuilders 的 `seriesColors!` 改为显式长度判断；包体积若续涨，重测基线并同步 240KB 门槛的设定依据。

---

**审查验证记录**：`pnpm typecheck` 通过；`pnpm lint` 0 errors / 0 warnings；`pnpm vitest run` 159 文件 1359 用例全过；`node scripts/assert-chart-bundle.mjs` 234.0KB ≤ 240KB。§四-低1 死代码结论、§五 any/fetch/TODO 零残留均为全仓 grep 实测（非抽样）；F3 三条风险差异与后端端点逐一对码核验。
