# CodeReview 问题修复优化计划（2026-08-29）

> 依据：`docs/reviews/code-review-2026-08-29.md`（审查报告）
> 分支：`chore/code-review-2026-08-29`
> 原则：按风险优先（P0 → P1 → P2），每个修复附回归测试；改动最小化，不顺手重构无关代码。
> 状态：全部方案已与作者逐项确认（2026-08-29），关键决策已内联标注【已确认】。
> 执行状态：**全部阶段已执行完毕（2026-08-29）**，`make test` 三端全绿（后端 242 / 前端 208 / collector 101，覆盖率门槛均过）。详见 `docs/reviews/code-review-2026-08-29.md` 修复状态一节。

---

## 阶段 0：P0 —— collector 事务完整性（最高优先）

### 0.1 修复 StoreError 后事务未回滚（C-P0-1）【已确认】
- `collector/collector/store/writer.py:61-66`：`upsert` 捕获 `psycopg.Error` 后先 `conn.rollback()` 再抛 `StoreError`。
- `collector/collector/scheduler/runner.py`：**每次任务运行新建连接、用完即弃**（`with psycopg.connect(...)` 按运行粒度），不复用已中止连接。
- 防御补强：`writer.py:58-59` 未知 `target_table` 的裸 `KeyError` 包装为 `StoreError`。
- **回归测试**：用 testcontainers（Python `testcontainers[postgres]`，与后端基建思路一致）跑窄端到端路径 runner → executor → store：构造违反约束的行制造 upsert 失败，断言 ①failed run 成功落库 ②重试在新连接上执行 ③后续合法写入不受中止事务影响。现有 MagicMock 测试保留。
- 验证：`cd collector && .venv/bin/pytest tests/ -q`。

---

## 阶段 1：P1 —— 静默断更与资源放大面

### 1.1 collector 数据断更隐患
- **C-P1-2 交易日历【已确认】**：`scheduler/jobs.py:150-167` 增加每月一次的定期刷新任务（upsert 最新交易日）；`is_trading_day` 判断改为按需查库（不再只读启动时的内存缓存），彻底消除内存日历过期问题；日历最新日期落后当前 >15 天时打 warning 留痕。测试：mock 源断言刷新任务被调度且幂等。
- **C-P1-3 冷启动缝隙【已确认】**：scheduler 注册 job 时查 `runs` 表，对"从未成功运行过"的任务设 `next_run_time=datetime.now()` 立即补跑；机制做成通用，不只针对 shenwan_mapping。测试：断言新任务首次运行时间为启动时刻。
- **C-P1-1 死配置【已确认：方案 A，配置生效】**：runner 消费 `task.retry_max`；`retry_backoff` 定义为策略枚举（`exponential` = 30×2ⁿ 秒、`fixed` = 固定 30s）由策略生成退避序列。测试：`retry_max: 1` 的任务只重试 1 次；退避间隔用注入时钟/mock sleep 断言，不烧真实时间。
- **C-P1-4 日期格式【已确认】**：`sources/plugins.py:15-23` 的 `_date_param` 对 `date`/`start`/`end` 三键统一归一化为 `YYYYMMDD`；兼容已规范化的 `YYYYMMDD` 直通；非法格式抛清晰 ValueError；同步修正 `tests/test_backfill.py:16` 的反向断言并补区间转换用例。
- **C-P1-5 纳入 CI【已确认】**：`Makefile:24` `test` 目标追加 `collect-test`；`.github/workflows/ci.yml` 增加 collector job（pytest + 80% 门槛，CI 注入 `DATABASE_URL`/docker 供集成测试真实运行，不再静默 skip）。

### 1.2 后端匿名端点资源放大
- **B-P1-1 健康检查【已确认：方案 B，拆分端点】**：
  - `/api/agent/health` 改为纯 liveness（只报进程存活 + LLM keyConfigured，零外呼），供 docker healthcheck（`docker-compose.yml:32`）与 Playwright（`playwright.config.ts:38`）使用；
  - 行情探活挪到 `/api/agent/status`（保留现有返回结构），探活结果加 ~30s TTL 缓存兜底防刷；
  - 前端 `fetchHealth`（`lib/api.ts:54`）改打 status 端点；HealthController 测试、前端 routes 测试同步调整。
- **B-P1-2 valuation 缓存【已确认】**：`ValuationApplicationService` 的 overview/history 按交易日为 key 加短 TTL 缓存（复用 `TtlCache`，不引入新机制），同交易日重复请求命中缓存；`erp()` 单次加载复用，删除 `:99-101` 死变量与重复查询；维持 permitAll 不变。测试：同交易日内多次 overview 仅查询一次快照表。
- **B-P1-3 会话消息边界【已确认】**：在 application 层入口（`ChatMessageWire` → domain 转换处）统一校验：role 白名单（`user`/`assistant`）、messageId 非空 ≤64、content 非空 ≤100KB、单次请求条数 ≤500；校验失败 → 400 `INVALID_MESSAGE`；`GlobalExceptionHandler` 补 `DataIntegrityViolationException` → 400 兜底。测试：超长字段返回 400 而非 500。

### 1.3 前端正确性
- **F-P1-1 反代收口【已确认】**：`lib/proxy.ts` 的 `relay()` 统一加 `AbortSignal.timeout(15_000)` 与 catch → 502 JSON；auth/admin/conversations 手写 fetch 路由收编到 `relay()`（有特殊逻辑无法收编的保留手写但补同样兜底）；copilotkit SSE 路由不动。测试：proxy.test.ts 补后端不可达/超时 → 502 JSON 用例。
- **F-P1-2 防抖丢尾部【已确认】**：`ThreadArea.tsx:344-362` 监听 isRunning true→false 立即 flush；effect cleanup 里 pending 写入 best-effort flush（PUT 加 `keepalive: true`）。测试：fake timers 断言停止后尾部内容落库、卸载前 flush 被调用。
- **F-P1-3 渲染放大【已确认】**：`useAgent` 传 `throttleMs: 150`（已确认 `@copilotkit/react-core@1.68.1` 原生支持，leading+trailing 合并）；`AssistantMessage` 加 `React.memo` 隔离历史消息重渲染。验证：人工跑长回答确认观感无卡顿。
- **F-P1-4 乐观更新【已确认】**：`RuntimeProvider.tsx` persistMessages 保存成功后本地更新对应 session 的 updatedAt/title，仅新会话首次保存后 `refresh()` 一次；确认 Sidebar 按 updatedAt 排序在本地更新后仍正确。测试：流式保存期间不发起 GET 列表请求；新会话首次保存后发起一次。

---

## 阶段 2：P2 —— 低成本批量修复【全部已确认】

后端（7 条）：
1. 年报 PE 加 `eps>0` 守卫（`OrchestratingMarketDataService.java:91-92`），对齐 TTM 口径
2. 注册并发唯一冲突 → 400 USERNAME_TAKEN（`AuthApplicationService.java:34-41`）
3. USER_NOT_FOUND 400 改 404（`GlobalExceptionHandler.java:32-36`）
4. 重置密码时清理该用户 remember-me token（`UserAdminApplicationService.java:48-52`）
5. remember-me key 配置化（`SecurityConfig.java:28`）
6. kline 默认口径统一为 120（`InvestTools.java:71` 向 `MarketController.java:42` 对齐）
7. 会话固定补回归测试：断言登录前后 session id 轮换

前端（8 条）：
1. `lib/adminApi.ts` / `lib/auth.tsx` 补 zod schema（AuthUserSchema/AdminUserViewSchema），消除 `any`
2. `AuthProvider` context value 加 `useMemo`（`lib/auth.tsx:83`）
3. `logout` 改 try/finally 保证 `setUser(null)` 一定执行
4. 管理员重置密码 `window.prompt` 改弹窗表单（复用 checkPassword + 二次确认）（`app/admin/page.tsx:58-62`）
5. 删除 `app/api/auth/login/route.ts:21-27` GET 死代码
6. `FeedbackBar` localStorage 键加容量上限，超出清最旧（`ThreadArea.tsx:66-77`）
7. `next.config.ts` 加安全响应头（X-Content-Type-Options / Referrer-Policy / frame-ancestors）
8. `app/error.tsx` 生产环境只显示通用文案 + digest；`vitest.config.ts` 覆盖 include 纳入 `app/api/**`

collector（12 条）：
1. selector 健康候选优先于半开探针（`selector.py:43`）
2. 熔断按任务运行计数而非尝试次数（`executor.py:81`）
3. advisory lock 键 crc32 改 `hashtextextended`（`runner.py:11-12`）
4. `finished_at` 写入 + record 对未知任务记 warning（`runs.py:25-41`）
5. Dockerfile 与 main() 重复迁移去重（留 jobs.main 一处）
6. TreasuryCurveSource 改增量拉取 + 显式转 date（`plugins.py:109-118`）
7. `field_mapping_sw` 的 stock_name 映射修正（`jobs.py:107`）
8. `_coerce` 用 `pd.isna` 通判（`converters/field_mapping.py:8`）
9. Config 报错友好化、`load_dotenv` 移出 import 时
10. `--date` 与交易日门控一致化 + 非法日期友好报错
11. 不可区间源的 backfill 直接拒绝并提示
12. 补 logging、APScheduler 设 `misfire_grace_time`、`snapshot.py` KeyError 纳入 SourceError 捕获；Dockerfile 加非 root USER

---

## 验证标准

- 每阶段完成后：`make test` 全绿（阶段 1.1 后含 collector），覆盖率门槛不下降。
- collector 集成测试不再默认静默 skip（CI 注入 `DATABASE_URL` 或用 testcontainers）。
- 每个 P0/P1 修复必须附失败在先的回归测试（红-绿）。
- 完成后更新 `docs/reviews/code-review-2026-08-29.md` 条目状态，并同步 AGENTS.md（若涉及流程变更，如 make test 范围）。
