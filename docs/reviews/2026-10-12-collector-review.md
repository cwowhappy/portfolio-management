# collector 采集服务深度代码审查报告

- 日期：2026-10-12；审查人：架构评审（AI 辅助）；范围：`collector/`（96 个 py 文件，16,076 行，含测试）
- 方法：通读规范（docs/technology/conventions/04-采集服务架构与代码规范.md）后，按 source → converter → validator → calc → store 数据流精读核心模块，量化扫描（TODO/吞异常/print/行数分布），并实测 `ruff check`（All checks passed）、`lint-imports`（1 contract KEPT）、`pytest`（495 passed in 20.43s）。

## 1. 架构总览

**数据流**（单向，import-linter 合约强制，实测 KEPT）：

```
YAML 任务定义 (tasks/*.yaml, 30 个)
  → seed_tasks 键校验 (scheduler/jobs.py:82) → collector_task 表
  → build_registries 四注册表装配 (scheduler/jobs.py:396)
  → APScheduler (BlockingScheduler, jobs.py:184)
    → TaskRunner (scheduler/runner.py:50)：交易日门控 → depends_on 前置检查
      → PG advisory lock (runner.py:18) → start_run 落 running 行
      → Executor (executor/executor.py:113)：SourceSelector 熔断/评分选源
        → Source.fetch → pd.DataFrame（原生异常包 SourceError，executor.py:149）
        → Converter.convert → list[dict]（字段映射 + 强转）
        → Validator.validate → (records, issues)（hard 抛错/soft 剔行）
        → Calc.compute（聚合，异常包 SourceError，executor.py:163）
        → Store.upsert（UPSERT ON CONFLICT，store/writer.py:207）
      → finish_run 回填终态 + 告警（可选 DedupAlerter 装饰链）
backfill：自然月分片 + 进度表，幂等兜底仍是源端 upsert（backfill.py:20-85）
```

**代码量分布**（源码 6,583 行 / 测试 9,493 行，测试:源码 ≈ 1.44:1，495 用例）：

| 模块 | 文件数 | 行数 | 职责 |
|---|---|---|---|
| sources/ | 11 | 3,883 | 行情源客户端（tushare/akshare/HTTP 直采/DB→DB JOIN 源） |
| scheduler/ | 6 | 1,080 | 调度装配、runner、交易日历、巡检、告警 |
| repositories/ | 5 | 360 | 纯 SQL（task/run/health/backfill 运维表） |
| executor/ | 3 | 288 | 选源熔断、执行编排、fetch 超时 |
| store/ | 2 | 231 | UPSERT 写入（13 张目标表） |
| validators/ + calc/ + converters/ | 12 | 296 | 纯逻辑三层 |
| model/ | 5 | 61 | 纯 dataclass，零依赖 |

最大 5 个源文件：`collector/collector/sources/plugins.py`（1,354）、`collector/collector/sources/macro.py`（703）、`collector/collector/sources/announcements.py`（578）、`collector/collector/sources/policy.py`（573）、`collector/collector/scheduler/jobs.py`（545）。

## 2. 做得好的地方

1. **事务纪律是真正的工程文化而非口号**。`collector/collector/store/writer.py:226-230` 捕获 `psycopg.Error` 后显式 `rollback()` 并注释说明 P0 教训（aborted transaction 会让后续 failed run 落库也失败）；`collector/collector/scheduler/runner.py:78-81` 每次重试尝试新建连接、用完即弃，注释解释复用 aborted 连接的风险；集成测试 schema 由真实迁移构建（`collector/tests/conftest.py:88-116`：alembic upgrade head + 回放后端 Flyway V1/V2/V3），杜绝手工 DDL 副本漂移。
2. **写库幂等体系完整**。`collector/collector/store/writer.py:5-123` 13 张表全部 ON CONFLICT upsert，冲突键注释逐表说明语义（如 `intelligence_news_raw` 的 published_at 保首见、fetched_at 刷观测时刻）；`index_constituent` 采用同事务"先删后插"处理成分股快照语义（writer.py:218-224）；每表有真实 PG 幂等回归（test_writer_idempotency.py）。
3. **降级与熔断语义严谨**。`collector/collector/executor/selector.py:5-44` 熔断阈值 3 次/冷却 600s/半开探针仅无健康候选时兜底，与规范 D2 逐条对应；`collector/collector/executor/executor.py:144-152` 第三方库原生异常统一包装为 SourceError 才能进入换源路径；calc 异常同样包 SourceError 防"无 failed run 记录"黑洞（executor.py:160-166）；备源有量级防御——总市值中位数超出 1e8~1e12 元口径直接拒写（`collector/collector/sources/plugins.py:1040-1047`），备源拒绝历史日期（plugins.py:1016-1018）。
4. **调度健壮性覆盖全面**。job 异常统一经 `_run_task_job` 兜底（`collector/collector/scheduler/jobs.py:137-142`，S3）；`build_scheduler` 显式 coalesce/max_instances=1/misfire_grace_time（jobs.py:193-219，S1）；`never_succeeded` 冷启动补跑（jobs.py:189-192，S2）；悬挂 running reaper 兜底进程被 kill 的场景（jobs.py:164-181）；advisory lock 获取/释放在同一连接生命周期（runner.py:81-113，S4）；depends_on 的"当日"口径钉死 Asia/Shanghai 并附时区分析注释（`collector/collector/repositories/runs.py:56-66`）。
5. **配置契约 fail-fast**。YAML 任务字段集封闭校验（`collector/collector/scheduler/jobs.py:79-90`），缺键/未知键都抛 ValueError；配套全量 YAML 装配冒烟测试（test_yaml_assembly.py：真实 tasks/ 目录走 load → build_registries → assemble 全链路）；`retry_max` 语义统一为"重试次数"（runner.py:78），熔断计数按任务运行而非尝试计（runner.py:93-94，防重试放大计数）。
6. **可观测性双通道且失败路径不留白**。run 记录采用 start_run 前置 running 行 + finish_run 回填终态的两段式（`collector/collector/repositories/runs.py:26-38`），时长可算、kill 可 reap；可预期跳过（非交易日/锁占用/依赖未满足）都落 SKIPPED 记录 + INFO 日志（runner.py:53-71）；软校验剔行与源侧观测警告合入 message 记 partial（executor.py:186-189）。
7. **告警链路生产级**。DedupAlerter 同签名 30 分钟窗口风暴抑制且"发送失败不记时间戳"（`collector/collector/scheduler/alerts.py:123-158`）；FeishuAlerter 校验 HTTP 200 之外的 body 业务码，防 secret 抄错静默假成功（alerts.py:98-112）；邮件 SMTP 兜底通道 + 发送侧 timeout=10 防持锁期间 SMTP 挂起拖死任务锁（alerts.py:204-212）；全程吞异常不影响任务语义。
8. **对外部 API 的限流与礼貌间隔成体系**。进程内线程安全 RateLimiter（`collector/collector/sources/ratelimit.py:12-28`），等待在锁外、调用可重叠；fina_indicator 逐股并发拉取用 ThreadPoolExecutor(4) + limiter（plugins.py:1102,1166），注释给出上游 200 次/分 → 0.35s 的推算依据；HTTP 源统一超时/重试/翻页间隔常量（`collector/collector/sources/constants.py:89-185`）且每个端点的钉住依据引用探测报告。
9. **测试是行为测试且有真实依赖分层**。外部 API 全部 mock（如 test_news_sources.py:133-225 mock urlopen 模拟分页/断连/502），涉事务/连接/锁/SQL 的行为用 testcontainers 真实 PG（test_writer_idempotency.py、test_migrations.py 等），`pg_conn` 只做 30 表 TRUNCATE 不维护 DDL 副本；validator 测试断言 records/issues 行为而非内部实现（test_validators.py:13-50）。
10. **工具链强制落地**。ruff（含 T201 禁 print，仅 cli.py 豁免）实测 All checks passed；import-linter 分层合约实测 KEPT，跨层破例（store→executor 异常定义）显式声明在 `collector/pyproject.toml:73-77` 而非放任。

## 3. 不足与风险

**高级：无。** 事务、幂等、调度兜底三条铁律均有代码 + 真实 PG 测试双证据，未发现可导致数据污染或静默丢调度的确定性缺陷。以下为中低级。

### 中

1. **停牌语义缺失，"缺席=停牌"只能靠下游推断**。证据：`collector/collector/sources/plugins.py:935-943`（daily_basic 只返回当日有交易的股票）+ writer upsert 只写存在行（writer.py:224）。停牌股在 `stock_valuation_daily` 中无行、无标志，连续停牌多日时该股的 PE/PB 分位消费方会把"旧数据"当"新数据"。建议：至少在表注释/模块文档写明该契约，或后续加 suspended 标志列（评估域消费前必须回答的问题）。
2. **规范 C3（按命令分级配置校验）未落实**。证据：`collector/collector/cli.py:54` 在任何子命令分发前无条件 `load()`，而 `collector/collector/config.py:28-37` 硬要求 `DATABASE_URL` + `TUSHARE_TOKEN` 同时存在——`collector list`/`history` 这类只读 DB 的命令也必须配置 tushare token，与规范 C3【决策】「list/history 等只读命令不强制数据源密钥」直接矛盾。建议：`load()` 增加 per-command 必需键参数，或拆 `load_db_only()`。
3. **fetch 超时依赖 daemon 线程，放弃线程可能累积**。证据：`collector/collector/executor/executor.py:32-55` 自注释"Python 无法强杀线程"，被放弃的 worker 依赖"上游 socket 断开自然消亡"；但 akshare/tushare 内部 socket 调用未见显式超时配置（HTTP 直采源有 NEWS/ANN/MACRO_HTTP_TIMEOUT，tushare 客户端依赖其自身默认）。若上游持续 hang 且任务按 cron 反复触发，daemon 线程与底层连接会累积。建议：为 tushare/akshare 调用设全局 socket default timeout，或对单源记录"超时放弃"计数入 health。
4. **writer 对缺列记录静默写 NULL**。证据：`collector/collector/store/writer.py:215` `tuple(r.get(c) for c in cols)`——converter/calc 改名漏列时该列全表写 NULL 而非报错；T5 一致性测试只保证 UPSERT_SQL 与 TABLE_COLUMNS 两字典互洽，不保证 records 携带这些键。建议：upsert 前校验首行 keys ⊇ cols（缺口径可空的列白名单），缺列直接 StoreError。
5. **价格/估值边界校验缺位**。证据：30 个 YAML 的 validator 规则统计——range 规则仅用于宏观 value 列（±1e8），`stock_valuation_daily` 只有 min_rows=1000（tasks/stock_valuation_daily.yaml:11），无 close>0、turnover≥0、pe_ttm 异常值的 range 校验（`collector/collector/validators/rules.py:66-77` 有现成能力但任务未用）；负 PE（亏损股）会原样落库。上游异常帧主要靠 min_rows 兜底。建议：为日频行情表补 close/turnover 的 soft range。

### 低

1. **plugins.py 是 1,354 行的 god module**：21 个类/函数混装（个股/ETF/指数/行业/国债/JOIN 源），占 sources 层行数 35%；常量虽已抽至 constants.py，源类未按域拆分。拆分为 `sources/stock.py`、`sources/etf.py`、`sources/index.py`、`sources/industry.py` 即可（import-linter 合约无需变动）。
2. **`SnapshotCalc` PE 中位数口径硬编码且无注释**：`collector/collector/calc/snapshot.py:8` `0 < pe <= 100` 上限 100 排除全部高 PE 与亏损股（负 PE），对 PE 中位数是实质性口径决策，代码无注释、规范文档未载。建议：注释或抽常量并写入口径出处。
3. **`make_open_day_check` fail-open 无留痕**：`collector/collector/sources/plugins.py:1060-1067` trade_cal 查询异常返回 True（视为开市），连 warning 都不记——备源本为 tushare 故障兜底，此时恰是 tushare 故障场景，静默放行会在节假日写入当日快照行。建议：except 分支 append warning 或 logger.warning。
4. **HTML 解析兜底静默返回空**：`collector/collector/sources/macro.py:244,387,412` 与 `collector/collector/sources/policy.py:212,226,435` `except Exception: return ""`/`[]` 共 6 处，正文抽干时多数路径有下游 min_rows/not_null 兜住，但列表解析（macro.py:387,412；policy.py:435）静默返回空列表可能导致"上游改版→当轮 0 新增"被误判为无新数据。建议：解析兜底统一记 warning 计数并挂 last_warnings。
5. **`retry_backoff` 无枚举校验**：`collector/collector/scheduler/runner.py:22-28` 非法值到运行时（backoff_delays）才 ValueError；seed 键校验（jobs.py:82-90）不覆盖值域。建议：seed 时校验 ∈ {exponential, fixed}。
6. **Dockerfile 无 HEALTHCHECK、无 ENV TZ**：单独 `docker run`（不走 docker-compose.yml:93 的 TZ）时 cron 按 UTC 解释，`collector/collector/Dockerfile:15` CMD 无健康探针。当前 compose 部署不受影响，列低。
7. **跨层异常定义**（已知债）：`collector/collector/store/writer.py:3` import `executor.executor.StoreError`，import-linter 显式豁免（pyproject.toml:73-77 有注释），移动异常定义属独立重构。
8. **`_ts_code` 命名误导**：`collector/collector/sources/plugins.py:57-58` `".SH" if code.startswith("0")` 是**指数代码**约定（000/399 段），命名与位置都像个股 ts_code 工具（个股规则是 6/9→SH、0/3→SZ），无 docstring，仅因全部调用点传指数码表才正确。建议：改名 `_index_ts_code` 并加一行注释。

## 4. 技术债清单（可量化）

| # | 项 | 量级 | 位置 |
|---|---|---|---|
| 1 | plugins.py god module | 1,354 行 / 21 个定义，sources 层 35% | collector/collector/sources/plugins.py |
| 2 | UPSERT_SQL 与 TABLE_COLUMNS 双份定义 | 13 表 ×2，靠一致性测试兜底（规范 T5 已决策接受） | collector/collector/store/writer.py:5-203 |
| 3 | 静默吞异常兜底（解析/判定降级无留痕） | 7 处 | macro.py:244,387,412；policy.py:212,226,435；plugins.py:1063 |
| 4 | C3 按命令分级配置未实现 | 1 处入口 | collector/collector/cli.py:54 |
| 5 | import-linter 显式豁免 | 1 条 | collector/pyproject.toml:73-77 |
| 6 | writer 缺列静默 NULL 的防御缺口 | 1 处 + 13 表影响面 | collector/collector/store/writer.py:215 |
| 7 | TODO/FIXME 残留 | 0（grep 全仓无匹配） | — |
| 8 | print 残留 | 仅 cli.py（T201 豁免，规范 O1 允许） | collector/collector/cli.py:62-109 |
| 9 | 测试体量 | 9,493 行 / 495 用例， mock 点 525 处，真实 PG 集成经 testcontainers | collector/tests/ |

## 5. 改进建议优先级

1. **P1 数据契约**：为 stock_valuation_daily 写明"缺席即停牌"契约（模块 docstring + 后端消费方文档），并补 close/turnover 的 soft range 校验（用现有 rules 能力，仅加 YAML 行 + 回归用例）。
2. **P1  writer 缺列 fail-fast**：upsert 前校验列集，把"静默 NULL"变成 StoreError（一处改动 + 一条真实 PG 用例）。
3. **P2 落实 C3**：cli.py 按子命令声明所需配置键，list/history/seed 只要求 DATABASE_URL。
4. **P2 plugins.py 按域拆分**：stock/etf/index/industry 四文件，纯移动不改行为（既有测试兜底）。
5. **P2 吞异常补留痕**：make_open_day_check 与 6 处 HTML 解析兜底统一挂 last_warnings/logger.warning，接入既有 run.message 通道。
6. **P3 小项合并**：retry_backoff 枚举入 seed 校验；SnapshotCalc 口径注释；`_ts_code` 改名；Dockerfile 补 HEALTHCHECK 与 ENV TZ；评估 tushare/akshare 全局 socket timeout 以收敛 fetch 超时线程模型。
