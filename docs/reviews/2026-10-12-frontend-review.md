# 前端深度代码审查报告（2026-10-12）

> 范围：`frontend/`（Next.js 15 + React 19 + CopilotKit + Tailwind 4 + echarts 6 + TanStack Table 9）。
> 方法：精读反代/数据层/聊天流核心文件 + 全量量化扫描（grep/wc）+ 工具链实测（`pnpm typecheck`、`pnpm lint`）。
> 对照基准：`docs/technology/conventions/03-前端架构与代码规范.md`（下称「规范」）。

## 1. 架构总览

**代码量分布**（不含 node_modules/.next/coverage）：

| 目录 | 文件数 | 行数 | 占比说明 |
|---|---|---|---|
| `components/` | 128 | 16,989 | 业务主体（chat 2796 / research 2623 / industry 1846 / intelligence 1637 / admin 1599 行居前五） |
| `lib/` | 43 | 5,121 | 数据访问层 + 37 个按域 Api 文件 |
| `app/`（含 api） | 53 | 1,777 | 26 个 tsx 中 20 个为 ≤41 行薄壳；`app/api/` 27 个 route 文件 |
| `tests/`（vitest） | 158 | 22,951 | 测试代码 ≈ 源码的 96% |
| `e2e/`（Playwright） | 27 | 3,839 | 26 个 spec |

**设计符合度总体评价：高。** 规范九大类（目录分层/数据获取/状态流式/组件 hooks/类型校验/安全/测试/命名/图表基建）绝大多数有代码级落地，且多数有 lint 或测试强制。27 个反代 route 文件全部经 `relay()`（grep `relay\|CopilotRuntime` 零例外），65 个方法导出（GET 23 / POST 17 / PUT 12 / DELETE 11 / PATCH 1 / OPTIONS 1）写法一致；全仓源码零 `TODO/FIXME`、零 `console.log`、零组件内直接 `fetch`、零 `echarts` 全量入口（四项均为 grep 实测，非抽样）。

**目录组织**：`app/`（admin/allocation/analytics/industry/intelligence/journal/market/portfolio/research/screener/settings/valuation/wiki 等 16 个业务页 + settings 4 子页）与 `components/` 按域分目录一一对应，`lib/` 一域一 Api 文件，三者命名对齐良好。

## 2. 做得好的地方

1. **反代收口干净且健壮**。`frontend/lib/proxy.ts:64-88` 统一处理多值 Set-Cookie 逐个 append（`:70-79`，getSetCookie 优先、单值头兜底）、204/205/304 置空 body（`:87`）、15s 超时、catch→502 JSON（`:89-92`）；catch-all 路径重建 `joinSegments` 拒 `..` 段防前缀逃逸（`frontend/lib/proxy.ts:102-105`），`frontend/app/api/portfolio/[...path]/route.ts:6-10` 正确接用并对非法路径返 400。
2. **CopilotKit route 按请求注入 Cookie，且有单测钉住**。`frontend/app/api/copilotkit/[[...slug]]/route.ts:87-96` 在 handler 内 `new CopilotRuntime` 并注入入站 Cookie（注释明确「模块级构建拿不到每个请求的 Cookie」），全部响应补 no-store（`:103-111`）；对消息体的两处外科手术（null content 归一化 `:13-35`、裁剪至最新 user 消息 `:45-73`）均带完整「为什么」注释。`frontend/tests/lib/copilotkit-route.test.ts:46-61` 断言 Cookie 注入与 no-store，防回归。
3. **lint 强制清单真实生效**。`frontend/eslint.config.mjs:29`（no-explicit-any=error）、`:44-56`（components 禁 fetch）、`:63-81`（禁 echarts 全量入口、禁 rehype-raw，且注释说明 flat config 同名规则不合并的坑）。实测：源码零 `any`（仅 tests 对 echarts option 用 `as any`，务实且可接受）；`pnpm lint` 0 errors。
4. **流式热点工程到位**。`frontend/components/chat/ThreadArea.tsx:219-260` 自定义 memo 比较器隔离历史消息重渲染（注释解释 Map 新引用会让默认浅比较失效）；`:404` `throttleMs: 150`；`:439-444` flush 并发序号**按线程隔离**（注释记录了全局序号误杀跨线程 flush 的审查教训）；`frontend/components/market/MarketBoard.tsx:54,101,114` requestSeqRef 丢弃过期响应；`ThreadArea.tsx:96-119` localStorage 反馈容量上限 200 清最旧——规范 3.1/3.2/3.3/3.5 全部有实证。
5. **统一 REST 客户端 + zod 边界校验**。`frontend/lib/http.ts:10-41` 收敛语义：非 2xx 取 message、204 返 undefined、schema 失败抛「数据格式异常」；头部注释坦承它是为消除 5 个 Api 文件的逐字重复而建。行情域 `frontend/lib/api.ts:23-33` 一行一校验，schema 与调用同框。
6. **图表基建唯一入口 + 体积回归断言**。`frontend/lib/echarts-setup.ts`（61 行）为全站唯一注册点；`components/charts/`（EChart 壳 20 行 + useECharts 53 行 + optionBuilders 277 行）页面与聊天流共用；数据契约 `frontend/lib/chart-spec.ts` 为 zod schema 与后端对齐；`frontend/scripts/assert-chart-bundle.mjs` 对 gzip 体积卡 ≤240KB 门槛——规范 9.1/9.2/9.3 三件套齐全。
7. **测试分层纪律好**。路由契约 `frontend/tests/lib/routes.test.ts:1-23` 一次性导入 23 个 route handler，逐域断言拼路径/Cookie 透传/状态码/204 空体；AG-UI 链路按规范 7.6 双层覆盖——`frontend/tests/agui-stream.test.ts:15-32` 用注入 fake fetch 的**真实** HttpAgent 驱动假 SSE 帧并钉住「RUN_ERROR resolve 不 reject」的客户端版本行为；`frontend/vitest.config.ts:38-42` 对 portfolio 域单设 80% 分支门槛。e2e `frontend/e2e/chat.spec.ts:16,29` 对 DEEPSEEK_API_KEY/管理员种子做环境门控跳过，CI 语义清晰。
8. **注释写「为什么」且引 ADR/审查条目**。`frontend/components/chat/RuntimeProvider.tsx:109` 引 ADR-0004 说明只持久化纯文本的取舍；`frontend/components/chat/ThreadArea.tsx:450-453` 记录 issue #27 就绪竞态闸门；`frontend/lib/conversations.ts:40-44` 解释传输层错误为何要包 status。

## 3. 不足与风险

### 高

无（安全与正确性未发现高危问题；反代会话、CSRF 前提、SSE 链路均经测试钉住）。

### 中

1. **research 域三个 page 违反薄壳规范且逃出单测覆盖网**。规范 1.1 要求 page 无逻辑薄壳（反面案例正是 admin page 塞 305 行），但 `frontend/app/research/[id]/page.tsx:40-336`（336 行：八分区编排 + 编辑草稿 + 归档 + 关联笔记/wiki 加载）、`frontend/app/research/page.tsx:31-168`（168 行：过滤/搜索/归档 + seq 守卫）、`frontend/app/research/new/page.tsx:30-125`（125 行表单）直接把业务写在 page 里。更关键的是 `frontend/vitest.config.ts:32` 的 coverage include 只含 `components|lib|app/api`，这批页面逻辑**只能被 e2e 间接覆盖**，单元测试够不着——正是规范 1.1 理由段预言的逃避路径。另 `frontend/app/research/[id]/page.tsx:76-80` 用「渲染期 setState 镜像」同步草稿（虽照 IndustryStockTable 先例、避免 effect 内同步 setState，但属 hooks 灰色实践，靠人工评审约束）。
2. **行情域类型双真源**。`frontend/lib/types.ts`（609 行、93 个导出 interface）手写 DTO，与 `frontend/lib/schemas.ts`（542 行、76 处 zod）平行维护，`frontend/lib/api.ts:9-17` 分别从两处取 schema 与类型——schema 与 interface drift 时编译期与运行期各说各话，恰是规范 5.1「类型用 z.infer 派生、禁止 as 断言」要防的。新域已做对（`frontend/lib/researchSchemas.ts:224-226` 27 处 `z.infer`），旧域（market/wiki/journal/intelligence 的 types.ts 消费方含 `JournalBoard.tsx`、`WikiBoard.tsx`、`IntelligenceSettingsPage.tsx` 等）未收敛。
3. **http.ts 收敛迁移半途而废**。`frontend/lib/http.ts:2-4` 自述为消除 5 个文件重复 request 而建，但 `frontend/lib/adminApi.ts:168`、`frontend/lib/conversations.ts:37`、`frontend/lib/memoryApi.ts:73` 仍各持私有 request。conversations 有正当理由（status 承载 + keepalive + 传输层错误分类，`:40-44`），adminApi/memoryApi 的差异点未见注释说明，语义与 http.ts 高度重叠，属可迁移残留。

### 低

1. **聊天域单文件偏大**。`frontend/components/chat/ThreadArea.tsx` 761 行含 9 个子组件（Reasoning/FeedbackBar/UserMessage/AssistantMessage/EmptyState/Composer/ToolCallRenderers 等），`frontend/components/chat/toolRenderers.tsx` 650 行含 6 张结果卡渲染器。分节注释清晰、无职责混乱，但已超「一屏导航」阈值，继续加功能会变危险。
2. **2 个 lint warning（unused import）**：`frontend/tests/ScreenerBoard.test.tsx:8`、`frontend/tests/lib/journalApi.test.ts:2`。零成本清理。
3. **原生 `confirm()` 对话框**：`frontend/app/research/page.tsx:60`、`frontend/components/research/ReportsPanel.tsx:185` 用 `window.confirm` 做归档/消耗算力确认。规范 6.4 仅禁 prompt 明文输口令，未越线，但与全站精致 UI 有质感落差，且阻塞主线程。
4. **`isUnauthorizedError` 对 message 文本做正则兜底**（`frontend/components/chat/ThreadArea.tsx:392` `\b401\b|unauthorized`）——无 status 结构时的最后手段，文本一变即失效；有注释但未标注对应后端契约版本，建议钉一个测试。

## 4. 技术债清单（可量化）

| # | 条目 | 量化 | 位置 |
|---|---|---|---|
| 1 | research 页面业务逻辑未下沉 | 3 个 page、629 行在覆盖网外 | `frontend/app/research/**/page.tsx` |
| 2 | 类型双真源（interface ∥ zod schema） | 609 行 types.ts、93 个 interface；旧域 5+ 消费方 | `frontend/lib/types.ts`、`frontend/lib/schemas.ts` |
| 3 | http.ts 收敛残留 | 3 个私有 request（≈40 行重复语义） | `frontend/lib/adminApi.ts:168`、`frontend/lib/conversations.ts:37`、`frontend/lib/memoryApi.ts:73` |
| 4 | 超大组件文件 | 1 个 >700 行、4 个 400–650 行（ThreadArea 761 / toolRenderers 650 / AdminBoard 434 / StrategyPanel 415 / MarketBoard 413） | `frontend/components/**` |
| 5 | lint warning | 2 个 unused import | 见 §3-低2 |
| 6 | 原生 confirm | 2 处 | 见 §3-低3 |
| 7 | TODO/FIXME/console.log 残留 | **0**（源码干净，此项为正向确认） | — |

## 5. 改进建议（按优先级）

1. **research 页面下沉 + 补单测（P1）**：把 `ProjectDetail`/`ResearchBoard`/`NewProjectForm` 迁入 `components/research/`（域内已有 14 文件的配套生态），page 回归 ≤40 行壳；同步把渲染期 setState 镜像收敛为「加载点统一初始化草稿」或提取 `useDraftSync` hook，并为迁移后组件补 vitest（顺手把覆盖 include 的口径在规范 7.2 里再钉一句「app/ 下不允许出现业务组件」）。
2. **types.ts 收敛为 z.infer 派生（P1）**：删掉 `frontend/lib/types.ts` 中可由 schema 派生的 interface，保留 ChatMessage 等纯前端模型；按 researchSchemas 模式逐域迁移，diff 最小化（类型断言一处不动，只换导出源）。
3. **完成 http.ts 收敛（P2）**：adminApi/memoryApi 迁到 `request()`；conversations 保留但把「status 承载错误」语义上提到 http.ts 作为可选行为（`request(path, method, body, schema, { withStatus: true })`），消除第三份实现。
4. **ThreadArea 按节拆文件（P2）**：拆出 `chat/composer.tsx`、`chat/messageRow.tsx`、`chat/feedbackBar.tsx`（纯搬移零行为变更），toolRenderers 按卡拆目录；拆完加一条「components/** 单文件 ≤500 行」的 lint/评审口径。
5. **杂项清零（P3）**：2 个 unused import、2 处原生 confirm 换现有确认弹窗组件、`isUnauthorizedError` 补契约测试。

---

**审查验证记录**：`pnpm typecheck` 通过（3.3s）；`pnpm lint` 0 errors / 2 warnings；§2 各项「零残留」声明均为全量 grep 结果（`components lib app` 范围），非抽样。
