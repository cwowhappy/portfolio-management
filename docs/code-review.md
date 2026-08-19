# 代码深度 Review 报告

> 生成时间：2026-08-19 · 范围：后端（Spring Boot/Java）、前端（Next.js/CopilotKit）、测试与配置
> 说明：本报告为一次性深度 review 的结论留档，作为后续修复的实施依据。条目按 P0（高）/P1（中）/P2（低）分级，附 file:line 引用。

---

## 总体评价

这是一个结构清晰、防御性意识强、测试覆盖扎实的项目。后端按能力域分层并用 ArchUnit 强制约束，客户端/解析/编排分离干净；前端 TS 严格模式、无 `any`，hydration 与缓存头修复思路正确。主要问题集中在**资源上限（缓存无界、限流被绕过）**、**几处健壮性边界**、**前端并发竞态与流式写放大**，以及**`app/` 层测试缺失**。

---

## 一、做得好的地方 ✅

### 后端
- **分层与架构约束**：`web → agent → market` 单向依赖，`PackageConventionsTest` 用 ArchUnit 强制包方向、无环、DTO 零内部依赖，是真正的架构回归网。
- **职责分离**：HTTP 客户端（`EastmoneyClient`/`SinaClient`/`TencentClient`）只做传输；`MarketDataParser` 是纯 `JsonNode → DTO` 静态函数；`MarketDataService` 做编排（缓存 + 限流 + 降级）。三者可独立离线测试。
- **防御式编程**：`StockRef.from` 规范化大小写/空白/`.sh/.sz/.bj` 后缀并校验 `\d{6}`；解析用 `JsonNode.path(...)`（缺节点不抛 NPE）；`normalizeVolume` 用金额/价格交叉核对东财手/股单位不一致；kline/news 参数服务端 `Math.max/min` 钳制。
- **领域错误模型**：`MarketDataException` 携带机器可读 `code` 并保留 cause；`GlobalExceptionHandler` 映射 400/429/502，并对客户端 SSE 断连静默处理。
- **缓存/限流配置化**：各实体 TTL 走 `InvestProperties.Cache` + `application.yml`；令牌桶有 `2×` 突发上限与构造参数校验、中断恢复。
- **测试质量**：客户端测试用 `MockRestServiceServer` 绑定真实 `RestClient`（不碰外网）、fixture 驱动；`MarketDataParserEdgeCasesTest` 覆盖畸形输入；`InvestTools.run` 把失败转成结构化 JSON 而非把异常泄入 LLM 循环。

### 前端
- **类型严谨**：`strict: true`，全量 grep 无 `any`；工具调用参数 `unknown` 强制 `prettyArgs` 收窄。
- **React 模式**：`RuntimeProvider` 用完整依赖数组 memo 上下文；`useChatRuntime` 在 Provider 外 throw；`KlineChart` 用 `[bars]` 依赖 memo 整条 scale/path 模型，`ma()` 提到组件外。
- **hydration 修复思路正确**：服务端/首次客户端渲染状态归零，`useEffect` 里从 `localStorage` 加载并以 `ready` 门控子内容，正确消除 SSR/客户端不一致。
- **共享 agent 用法类型正确**：对照 `@copilotkit/react-core@1.68.1` 的类型契约，`useAgent({ agentId, updates })` 是合法的"共享"形态。
- **数据获取**：`lib/api.ts` 全局 `cache:"no-store"`；三个 route 均 `force-dynamic` + `AbortSignal.timeout`；CopilotKit 路由对全部动词强制 `Cache-Control: no-store`。
- **测试**：fetch 全 mock、需要处 `vi.useFakeTimers`、内存 `localStorage` mock + `randomUUID` polyfill，80% 语句/分支门槛并有意 scoped 到 `components/**`+`lib/**`。

### 测试与配置
- 后端 18 个测试文件 / 117 `@Test` + 11 条 ArchUnit 规则（128 项）；前端 11 文件 / 114 `it`。
- 无硬编码密钥：`DEEPSEEK_API_KEY` 仅从 env 读取，`.env` gitignore，`.env.example` 提交；`EastmoneyClient` 的 `SEARCH_TOKEN` 是公开匿名 token 且已注释说明。
- 后端 JaCoCo 80% 指令/分支门槛 + 前端 vitest 80% 门槛均在构建期强制。

---

## 二、不足与优化建议 ⚠️

### 后端 · P0

**1. `TtlCache` 无界，search 按原始 query 为 key → 内存耗尽风险**
`TtlCache.java:11` 是裸 `ConcurrentHashMap`，无大小上限/驱逐。类注释声称"条目规模受键空间限制"，但对 `search` 不成立：`MarketDataService` 用 `"s:" + query.trim()`（任意输入）为 key，任意新查询串都会生成一条 10 分钟条目。
→ 加 max-size + LRU 淘汰，search key 规范化/哈希并设上限。

**2. 限流被 retry + 降级绕过，实际 QPS 远超配置**
`acquire()` 每次"逻辑调用"只调一次，但 `EastmoneyClient.getText` 一次调用内最多 3 次 HTTP 重试，降级路径还会 `acquire()` 第二次并打第二个源。配置 `5` 时真实上游可达 ~30 req/s，违背 ADR-0003。
→ 在 HTTP 调用层限流（每次尝试都 `acquire()`），移除降级路径重复 `acquire()`。

**3. `estimateValuation` 在畸形 `REPORT_DATE` 上崩溃**
`MarketDataService.java:149-151` 直接 `Integer.parseInt(latest.reportDate().substring(0,4))`，而 `parseFinancialIndicators` 仅在 `length() >= 10` 才截断。年度条目存在而 `latest.reportDate()` 空/短时抛 `StringIndexOutOfBoundsException`，逃过降级变 500。
→ 对 `reportDate` 长度/格式守卫，畸形时返回 `Valuation(null, pb)`，补回归测试。

### 后端 · P1/P2

**4. 三客户端 retry/降级逻辑重复**：`EastmoneyClient.getText` 与 `SinaClient.fetch` 重试/退避/中断恢复/wrap 几乎逐行重复；`TencentClient.kline` 不重试。`MarketDataService` 三处降级也是复制粘贴。→ 抽 `withFallback`/`retry` 辅助。

**5. 三个 `HttpClient` 各自建池**：`RestClientFactory.builder` 每次 `newBuilder().build()`，三条池/selector 线程并存。→ 共享 `HttpClient` 的 `@Bean`。

**6. `RateLimiter` 持锁 sleep**：`tryAcquire` 是 `synchronized` 且循环内 `Thread.sleep(20)` 持有 monitor；`refill()` 用 `nanoTime`、deadline 用 `currentTimeMillis`（两套时钟）。→ sleep 移出锁、统一 `nanoTime`。

**7. 解析健壮性**：`parseFinancialIndicators` 不检查 `data.isArray()`；`f86 != null` 是死代码；`parseQuote` 用 `Asia/Shanghai` 而 overview 用服务器默认时区；`buildSinaOverview` 丢掉指数 code。

**8. 异常处理一致性**：`InvestTools.run` 的 `catch(Exception)` 会吞编程 bug；`AgentConfig` 用 `'${DEEPSEEK_API_KEY:}' != ''`（空白算已配置）与 `HealthController` 判定不一致；`DEEPSEEK_API_KEY` 散落三处；`getText` 对 4xx 也重试；`GlobalExceptionHandler.market` 对未知 code 静默归 502。

**9. magic number 遍布**：`end=20500101`、search `count`、`pageSize`、退避 `300ms`、`tryAcquire(2000)`、`MAX_LIMIT`、工具默认 60 vs 控制器默认 120 口径不一致。→ 移入 `InvestProperties`。

**10. `probeQuoteLatencyMs` 报缓存延迟**：`quote("600519")` 命中 TTL 缓存，第二次起返回近 0ms，健康检查 latency/ok 误导。→ 绕过缓存。

**11. `news()` keyword 空值**：`keyword = quote(ref.code()).name()` 无 blank 守卫，空串会 `eastmoney.news("")`。

### 前端 · P1

**12. `MarketBoard` 竞态（无取消）**：`select` 用 `Promise.all` 发 4 并行请求、resolve 写 state，无 request-id/AbortController；快速点 A→B 时 A 慢响应覆盖 B。`switchPeriod` 同理。→ 序号计数器守卫。

**13. 流式写放大 O(n²)**：`useAgent` 无 `throttleMs`，persist effect 每个 token delta 都重读+`JSON.parse` 整段历史 → `JSON.stringify` 重写 `messages`+`sessions` → `refresh()` 重渲染 Sidebar。→ `throttleMs` + debounce + 去冗余读。

**14. 共享 agent + 切换无 run 守卫 → 跨线程污染**：`switchThread`/`newThread` 在 `isRunning` 时仍可调，切换只 `setMessages` 不 `abortRun`，A 线程 SSE 继续写进 B 线程的 localStorage key。→ 运行中禁用切换或 `abortRun`。

### 前端 · P2

**15. `send` 直接 `crypto.randomUUID()`**：无 fallback，非安全上下文/旧浏览器抛错。→ 复用 `newThreadId()`。
**16. `send` 无错误处理**：`runAgent` 无 try/catch，失败被 `void send()` 吞，只 `console.error`。→ UI 错误条。
**17. tool/reasoning 历史被丢弃**：`agentMessagesToHistory` 只留 `role+content`，rehydrate 后多轮上下文退化为纯文本。→ 扩展 schema 回放或加注释。
**18. 无 error boundary**：无 `app/error.tsx`/`ErrorBoundary`，渲染异常整页白屏。
**19. `get<T>` 无运行时校验**：`res.json() as Promise<T>` 是 cast，`zod` 在依赖里未用。→ `lib/api` 边界 zod 校验。
**20. proxy 吞错误无日志**：`catch {}` 丢弃真实错误，后端宕机只见泛 502；`market` 路由无条件 `Content-Type: application/json`。
**21. 其它小问题**：`KlineChart` useMemo 在空数组早退前执行（`Math.min(...[])` 得 `Infinity`）；`MarketBoard` overview 轮询缺 `cancelled` 标志；`useAgent` 每次渲染新建 `updates` 数组、`upCls` 重算；Composer 发送后不重置高度；无语言 fenced code 退化成 inline code；Sidebar 删除按钮 `hidden group-hover:block` 键盘/触屏不可达；`timeAgo` 仅渲染期计算；无 `aria-live`/`aria-expanded`。

### 测试与配置

**22. `app/` 层零测试**：`vitest.config.ts` 主动排除 `app/**`，最近三个前端修复（cache header、共享 agent 去重、hydration）无专门回归测试。→ 纳入 route handler 测试与覆盖。
**23. 时序依赖/慢测试**：`RateLimiterTest` 靠真实墙钟 `tryAcquire(1500)`、`TtlCacheTest` `Thread.sleep(80)`（50ms TTL 仅 30ms 余量，易 flaky）；退避 300ms 不可注入共烧 ~3.6s sleep。→ 注入 Clock/退避置 0。
**24. 弱断言**：`MarketControllerTest` 只断言 mocked 返回值 `isSameAs`，应 `@WebMvcTest`；前端断言完整 Tailwind 类字符串/截断常量 53，应改 `data-*`/`aria-*`。
**25. 配置缺陷**：
- `pnpm-workspace.yaml:5` 字面占位符 `unrs-resolver: set this to true or false`。
- `docker-compose.yml:12` 用 `curl` 做 healthcheck，但镜像 `eclipse-temurin:21-jre` 无 curl → healthcheck 永远失败，前端 `depends_on: service_healthy` 卡死。
- 无 CI；无 `application-prod.yml` profile 分离，`show-details: always` 生产无条件开启。
- `package.json` `"lint": "next lint"` 已弃用；`frontend/Dockerfile` `--frozen-lockfile=false` 关闭锁文件强制。

---

## 三、Top 优先修复表

| 优先级 | 问题 | 风险/收益 |
|---|---|---|
| P0 | `TtlCache` 无界（search key） | 内存耗尽，可被对抗性输入触发 |
| P0 | 限流被 retry/fallback 绕过 | 真实 QPS 远超配置，接口被封风险 |
| P0 | `estimateValuation` `substring` 崩溃 | 未捕获异常变 500 |
| P1 | `MarketBoard` 竞态、流式写放大、跨线程污染 | 用户可见数据错乱/卡顿/串号 |
| P1 | docker healthcheck `curl` 不存在 | 前端容器永远起不来 |
| P1 | `app/` 层无回归测试 | 最近 3 个修复无保护 |
| P2 | 重复重试/降级、`probeQuoteLatencyMs` 误导、无 error boundary、proxy 无日志、弱断言/时序测试 | 可维护性 + 观测性 |
