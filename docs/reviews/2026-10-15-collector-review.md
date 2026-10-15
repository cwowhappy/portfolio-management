# collector 采集服务第四轮深度代码审查报告

- 日期：2026-10-15；审查人：架构评审（AI 辅助）；范围：`collector/`（分支 `chore/code-review-2026-10-15`，30 个 YAML 任务，源码 6,578 行 / 测试 9,784 行，513 用例）
- 方法：上轮修复逐条复发检查（读码 + 实测）→ L3 fetch 超时线程模型专项（含 .venv 库内部超时实证实验）→ scheduler/backfill 域深读 → 测试网假绿抽查 → 量化复扫。实测 `ruff check`（All checks passed）、`ruff format --check`（100 files）、`lint-imports`（1 contract KEPT）、`pytest`（513 passed in 17.77s）。

## 一、架构总览与上轮修复复审结论

数据流与第三轮一致（YAML → seed → 四注册表 → BlockingScheduler → runner 门控/锁 → executor 选源熔断 → fetch/converter/validator/calc → writer upsert），无结构性变动。上轮 6 条目复审结论：

1. **plugins.py 拆 5 模块——干净，无复发**。`sources/common.py:1-78` 只承载 4 个跨域件（`normalize_date`/`_date_param`/`_open_days_desc`/`_daily_basic_spot`），其中 `_daily_basic_spot` 被个股域（`sources/stock.py:358` AllASpotBackupSource）与行业域（`sources/industry.py:93` IndustryUniverseSource）真实复用，归 common 恰当，无「第二个 god module」迹象（78 行、零状态、纯函数）。import 面：common 只依赖 `sources/base`；stock/etf/index/industry 单向依赖 common，全仓无 `sources.plugins` 残留引用；import-linter 实测 KEPT，无循环依赖。拆分小遗漏：`sources/etf.py:308` 的 `_open_days` 与 `sources/common.py:40` 的 `_open_days_desc` 语义重叠（trade_cal 开市日，签名不同），无害。
2. **writer fail-fast——与全部 producer 键集对齐，无误炸面**。逐个核对 14 张目标表的 converter/calc 输出：`FieldMappingConverter.convert`（`converters/field_mapping.py:36-51`）对缺 src 列也产出 None 键，calc 输出同质 dict，`executor/executor.py:167-169` 统一注入 `trading_day`——**所有 producer 键集恒 ⊇ TABLE_COLUMNS**，白名单外缺键只可能来自未来的契约破坏（恰是该防的）。OPTIONAL_COLUMNS（`store/writer.py:216-221`）4 表 5 列均有口径出处注释，无「该收未收」（industry_valuation 的 roe/dividend_yield 实为 calc 恒产出键，白名单属防御冗余）。测试为真实 PG 断言缺列 StoreError 且不落行（`tests/test_writer.py:106`、`tests/test_writer_idempotency.py:355`）。残留理论缝：只校验 `records[0]`（`store/writer.py:236`），依赖「records 同质」约定。
3. **停牌契约 + soft range——边界安全，无误伤**。契约双写于 `sources/stock.py:4-8` docstring 与 `store/writer.py:42-44` 注记；range 含端点、None 放行（`validators/rules.py:70`）；回归测试锚定 close=0.01 保留 / close=0 剔除 / turnover=0 保留（`tests/test_yaml_assembly.py:223-242`）。真实数据分布核对：A 股最小价位 0.01 元（面值退市股最低价恰为 0.01，含端点安全）；上限 99999999 距茅台 ~1700 元五个量级；注册制新股首日换手 80%+ 距上限六个量级；ETF 0.001 报价精度不受影响（range 仅挂个股任务，ETF 走 `index_close_history` 无 range）；复牌首日无涨跌幅限制但价格恒为正，通过。
4. **C3 分级配置——覆盖面正确**。`cli.py:54-60`：run/backfill 需 database_url+tushare_token；seed/list/history 只需 database_url。backfill 无子命令（仅 --start/--end 旗标）；seed 只写 collector_task 表（`repositories/tasks.py:89-98`），不触数据源，判定正确。测试 `tests/test_config.py:22-36`。残余小项：`run`/`backfill` 对纯 akshare/HTTP 源任务（如 news_fast）也强制 tushare_token——保守可接受。
5. **7 处吞异常 warning——已补痕但有通道偏差（可辩护）**。全部 7 处均有 logger.warning + C5 注释（`sources/macro.py:244,390,417`、`sources/policy.py:212,229,440`、`sources/stock.py:195`）。偏差：上轮建议「接入 run.message 通道」，实现选 logger-only。解析层事件高频（每页一次），进 run.message 会刷屏，裁量合理；但 `make_open_day_check`（`sources/stock.py:189-196`）是「降级日整轮放行」级事件，signal 只存在于容器日志、不进 DB 运行记录，飞书链够不着——列低级观察项。

## 二、做得好的地方

1. **拆分测试网跟着源码走，纯移动零假绿**。`tests/test_plugins.py` 按新模块分节 import（index/industry/stock/common），45 个用例原样通过；`test_backfill.py:60-72` 的 build_registries 插件键集断言覆盖拆分后的全部注册键——拆分不是「文件搬家」，装配契约有测试锚。
2. **fail-fast 白名单带口径出处注释**（writer.py:209-215），每列为什么可缺指向具体源文件注记，是「注释写为什么」的范本（规范 Y3）。
3. **停牌契约写在数据流的两端**（源 docstring + writer 注记），消费方误读的概率显著降低；`executor.py:186-189` 的 partial message 通道仍是 soft range 剔行的落点，partial 状态可见。
4. **停牌语义本轮被验证为端到端自洽**：daily_basic 缺席即停牌 → 无行写入 → 下游「旧数据当新数据」的风险由契约文档+分位消费方自行负责，采集侧无隐式补零行为。
5. **种子校验把 retry_backoff 值域校验也补了**（`scheduler/jobs.py:99-100`），非法值在 seed 期即拒，比运行期 ValueError 早一个量级。
6. **C3 实现方式符合单入口纪律**：`config.load(required=...)` 签名扩展（`config.py:31`）而非拆第二个 loader，dotenv 仍只在 load 内调一次。

## 三、不足与风险

### 高

**H1（上轮 §3 中 3 的实锤，本轮专项）：fetch 超时包裹下的 4 个 akshare 调用点无 socket 超时，daemon 线程在静默故障下永久累积。** 实证枚举（逐个查 .venv 库内部）：

| 调用点 | 代码位置 | 库内部 | 超时 |
|---|---|---|---|
| `ak.fund_etf_category_sina`（etf_basic 目录） | `sources/etf.py:153` | `requests.get` 无 timeout（akshare/fund/fund_etf_sina.py:43） | ❌ 无限 |
| `ak.index_hist_sw`（行业指数收盘） | `sources/industry.py:44` | `requests.get` 无 timeout（akshare/index/index_research_sw.py:42） | ❌ 无限 |
| `ak.bond_china_yield`（国债曲线） | `sources/index.py:169` | `requests.post` 无 timeout（akshare/bond/bond_china.py:33） | ❌ 无限 |
| `ak.tool_trade_date_hist_sina`（日历刷新） | `scheduler/jobs.py:478` | `requests.get` 无 timeout（akshare/tool/trade_date_hist.py:27） | ❌ 无限 |

对照组（有界，无需动）：tushare 全接口 `requests.post timeout=30`（tushare/pro/client.py:23,41）；`stock_zh_a_spot_em` 走 aiohttp，`ClientTimeout(total=300, sock_connect=30)` 默认生效（aiohttp/client.py:253）；macro/policy/news/announcements/ETF-detail/告警 webhook 全部显式 timeout；YAML 仅 all_a_valuation 一个 akshare 直调源（`tasks/all_a_valuation.yaml:5`）。

**关键实证**：`socket.setdefaulttimeout` 对本问题**无效**——requests timeout 缺省时显式传 `None` 至 urllib3 → `sock.settimeout(None)` 覆盖全局默认（本环境实验：对永不响应的本地 server，全局默认 1.5s 下 requests.get(timeout=None) 挂起 40s+ 无输出）；psycopg 不读 `getdefaulttimeout`（inspect 源码证实）。

**风险量化**：增量模式全源套 1800s 超时（`executor/executor.py:29,140-143`），超时后放弃 daemon 线程，其消亡条件=底层调用返回。正常故障（RST/拒绝）秒级抛错无累积；**静默丢包场景下，上述 4 点线程永久存活**——treasury_curve/industry_index_close 每交易日各 1 次，最坏 ≈ 每日 2-3 个永久线程 + 各 1 fd，月级才显形。更敏感的是调度器线程池：APScheduler 默认池 max_workers=10（apscheduler/executors/pool.py:47），jobs.py:478 日历刷新与 patrol 的 psycopg.connect **不在 fetch 包裹内**，一次挂起永久占 1/10 池容量；psycopg `connect_timeout` 缺省 0=无限（7 处调用点：runner.py:81、jobs.py:182/501/532、cli.py:68、两处 conn_factory），PG 地址黑洞时 connect 同样永久挂起。

**H2（新发现）：被放弃线程的「僵尸写」窗口——EtfTrackingErrorSource 在 fetch 内做 UPDATE。** `sources/etf.py:528-564` 的 fetch 持有 `conn_factory()` 连接并 `executemany(UPDATE)`（etf.py:553），上下文退出即提交。该任务无 timeout_seconds 覆盖 → 套默认 1800s 包裹。若 DB 抖动致 fetch 线程被放弃、随后 DB 恢复，**线程会在线程内提交 UPDATE，而 run 记录已是 failed**——晚到的写无 run 记录对应，可覆盖备源/后续重试的语义。概率窄（需恰在 1800s 窗口内 DB 假死又复活），但属静默数据面事件。其余源 fetch 内只读（IndustryUniverseSource/etf_close/news/policy/macro/announcements），无此面。

### 中

1. **backfill 模式整体豁免 fetch 超时**（`executor/executor.py:140-141`）对「源端hang死」无界——backfill 是 CLI 人工触发有人看护，但 etf_close 250 交易日 × sleep 等长任务一旦挂起只能人杀进程。进程死在分片中途无妨（进度表语义正确，见下），但挂起态本身无自清机制。建议至少对 backfill 给一个超大上限（如 6h）。
2. **`make_open_day_check` 降级放行只打 logger**（`sources/stock.py:195`），见复审结论 5——节假日+tushare 故障双叠加时会写当日快照行，DB 无留痕。

### 低

1. **writer fail-fast 只校验首行**（`store/writer.py:236`），依赖 records 同质约定；现有 producer 全同质，理论缝。
2. **run/backfill 命令对非 tushare 任务也强制 token**（`cli.py:55-56`），保守可接受，报错文案可提示「该命令按任务装配全部源」。
3. **etf.py 的 `_open_days` 与 common.py `_open_days_desc` 语义重叠**（etf.py:308 / common.py:40），拆分遗漏，可后续收敛。
4. **「共享单例」源的状态重置依赖每个源自觉**（如 `sources/index.py:49` 重置 last_warnings、`sources/etf.py:254`）；当前各源都做到了且任务间不共享实例（每个插件仅被一个任务引用，经 YAML 核对），但无机制强制——新增源遗忘重置会在并发运行间串扰。

## 四、技术债清单（量化）

| # | 项 | 量级 | 位置 |
|---|---|---|---|
| 1 | requests 无超时 akshare 调用点 | 4 处（含 1 处调度器直跑） | etf.py:153；industry.py:44；index.py:169；jobs.py:478 |
| 2 | psycopg.connect 无 connect_timeout | 7 处 | runner.py:81,135；jobs.py:182,501,532；cli.py:68；sources conn_factory ×2 |
| 3 | fetch 内写库的僵尸写窗口 | 1 源 | sources/etf.py:553 |
| 4 | backfill 无超时上限 | 1 处豁免 | executor/executor.py:140-141 |
| 5 | OPTIONAL_COLUMNS 只校验首行 | 1 处 | store/writer.py:236 |
| 6 | import-linter 显式豁免（store→executor 异常定义） | 1 条 | pyproject.toml:73-77 |
| 7 | UPSERT_SQL 与 TABLE_COLUMNS 双份定义 | 13 表 ×2（T5 已决策接受） | store/writer.py:5-206 |
| 8 | make_open_day_check logger-only | 1 处 | sources/stock.py:195 |
| 9 | TODO/FIXME | 0（grep 全仓无） | — |
| 10 | print 残留 | 仅 cli.py（T201 豁免） | cli.py:73-121 |
| 11 | 静默吞异常（AST 扫描 43 命中人工复核） | 全部设计内：强转 None/重试收口/append 到 last_warnings | 例：field_mapping.py:15-27；announcements.py:144 |
| 12 | 测试网 | 513 用例 / mock 相关行 654 / 真实 PG 集成经 testcontainers | tests/ |

## 五、改进建议优先级

**P1（建议下轮排期）**

1. **L3 最小风险方案——requests 默认超时注入（收口 H1 主体）**：新建 `collector/sources/http_guard.py` 的 `install_default_http_timeout(seconds=90)`，包装 `requests.get/post` 在缺省时注入默认 timeout（显式传参不覆盖，tushare 30s 不受影响；进程级幂等可重复安装）；在 `cli.py:63` 与 `jobs.py:515` 两个入口各调一次（遵守 C1 单入口纪律）。测试三用例：缺省调用被注入 90 / 显式 timeout=5 不被覆盖 / 二次安装幂等。约 30 行代码 + 3 用例，行为变更面=4 个无界调用点获得 90s 上界，被放弃线程的存活从「永久」变为「≤90s」，H1 从累积型降为瞬态型。不推荐 socket.setdefaulttimeout（实证无效）、不推荐子进程隔离（DataFrame 序列化+契约破坏，风险收益不成比例）。
2. **psycopg.connect 补 `connect_timeout=10`**：7 处调用点逐处加参（或收敛一个 db 辅助），只约束 TCP connect、不触碰查询超时，零行为风险。收口调度器池被 PG connect 挂起永久占坑的面。

**P2**

3. **僵尸写收口（H2）**：EtfTrackingErrorSource 把 UPDATE 移出 fetch（fetch 返回待写行、由 writer/单独步骤执行），或该任务 YAML 显式 `timeout_seconds` 大到永不触发放弃（它是纯本地 DB 操作，本就不需要超时保护——直接禁用包裹更清晰）。
4. **backfill 超时上限**：`executor/executor.py:140-141` 豁免改为「backfill 用显式上限（如 21600s）而非 None」，防人工 backfill 挂起无自清。
5. **make_open_day_check 挂 last_warnings**（它有源实例句柄，一行即可接入 executor 的 message 通道），把「降级日放行」从容器日志提升到 DB 运行记录。

**P3**

6. writer 首行校验升级为全量键集并集校验（O(n) 一遍，消除理论缝）。
7. `_open_days` 收敛进 common.py（消除语义重叠）。
8. 源单例状态重置机制化：base.py 提供 `reset_observability()` 钩子由 executor 在 fetch 前统一调用，替代各源自觉。
9. C3 报错文案注明 run/backfill 装配全部源故需全部密钥。
