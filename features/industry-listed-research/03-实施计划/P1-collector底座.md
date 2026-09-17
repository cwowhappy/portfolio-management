# P1 · collector 底座（income 并入 + 行业估值历史重算）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `stock_financial` 补营收绝对额列（tushare `income` 并入既有任务同源同行写入），并新增「行业估值历史重算」任务从库内数据幂等回填 `industry_valuation` 缺失日期的 PE/PB。

**Architecture:** 不新增采集管线形态——income 挂进 `StockFinancialSource` 逐股循环（同一 limiter、同一行 upsert，规避 writer 全列 SET 的 None 覆盖）；重算任务是**纯本地 DB→DB 源**（先例：`IndustryUniverseSource` 源内查库），走 标准管线（源 → converter 直通 → writer 既有 `industry_valuation` upsert），只产出缺失日期，与每日增量任务日期不相交。

**Tech Stack:** Python（pandas/psycopg/tushare pro）、pytest（pg 集成 fixture 回放后端 Flyway SQL）、Flyway V15（backend 侧 DDL）。

**Spec:** [01-需求规格](../01-需求规格/需求规格说明.md) §三.D | [02-设计规格](../02-设计规格/设计规格说明.md) §二 §三（本计划实现其 P1 切分）

## Global Constraints

- writer 全列 SET 语义：`stock_financial` 的两接口数据**必须同一次 fetch 合并成同一行 record**（`writer.py:104-128`，`r.get(c)` 缺值即 None 覆盖）；
- 任务 yaml 字段集是封闭契约（12 列，`jobs.py:60-72` `_validate_task_keys`），未知键 fail-fast；
- collector 测试 schema 与生产同源：conftest 回放后端 Flyway SQL（`tests/conftest.py:29-33` `FLYWAY_SQL_FILES`），**V15 必须追加进该元组**；
- `income` 的 report_type 过滤值与 revenue 单位**以 Task 1 实测探测为准**，禁止凭记忆写死（结论落调研报告，Task 4 引用）；
- 收尾门槛：`make collect-test` 全绿（ruff check + ruff format --check + lint-imports + pytest 覆盖率 ≥80%，`Makefile:98-101`）；改完代码跑 `cd collector && .venv/bin/ruff format` 保持格式。

---

### Task 1: income 接口实测校准（探测 + 调研报告）

**Files:**
- Create: `features/industry-listed-research/09-调研报告/2026-09-17-income接口校准.md`

**Interfaces:**
- Consumes: 环境变量 `TUSHARE_TOKEN`（`collector/config.py:22`）；
- Produces: 调研报告结论三要素——① 合并报表 `report_type` 过滤值；② `revenue` 单位（元 or 万元）与换算系数；③ `fields` 参数建议。Task 4 的常量 `INCOME_MERGED_REPORT_TYPE` / `INCOME_REVENUE_SCALE` 由此确定。

- [ ] **Step 1: 跑探测脚本**

```bash
cd /Users/lixiaoyi/GitRepository/portfolio-management/collector && .venv/bin/python -c "
import os, tushare as ts
pro = ts.pro_api(os.environ['TUSHARE_TOKEN'])
df = pro.income(ts_code='600519.SH', start_date='20240101', end_date='20260917',
                fields='ts_code,ann_date,end_date,report_type,revenue')
print(df.to_string())
"
```

Expected: 同一 `end_date` 多行（不同 `report_type`）。判定方法：贵州茅台 2024 年报营收 ≈ 1741 亿元——若 `revenue` ≈ 1.74e12 为**元**、≈ 1.74e8 为**万元**；取年报 `end_date=20241231` 行中与年报营收吻合的 `report_type` 值（tushare 文档称合并报表为 `1`，以实测为准）。

- [ ] **Step 2: 补一档交叉验证（近 12 季接口形态）**

同脚本改 `ts_code='601398.SH'`（工商银行，2024 年报营收 ≈ 6.58 万亿元）再跑一次，确认单位结论一致、逐股接口支持 `start_date` 限窗（`fina_indicator` 同款，见 `plugins.py:389,392`）。

- [ ] **Step 3: 写调研报告**

```markdown
# income 接口校准（MS-09 P1）

> 日期：2026-09-17 | 探测脚本：见 features 文档与提交记录 | 样本：600519.SH / 601398.SH

## 结论

| 项 | 值 | 依据 |
|---|---|---|
| 合并报表 report_type | `<实测值>` | 茅台/工行年报营收量级与该 report_type 行吻合 |
| revenue 单位 | `<元 或 万元>` | 600519 2024 年报 1741 亿量级比对 |
| 换算系数（→元） | `<1 或 10000>` | 由上一行推出，Task 4 常量 INCOME_REVENUE_SCALE |
| fields 建议 | `ts_code,end_date,report_type,revenue` | 减小逐股响应体 |

## 风险与备注

- 同 end_date 多 report_type 行确认存在；单季/调整报表行必须过滤，否则营收被低值行污染。
- （若发现）限流表现：与 fina_indicator 同级（200 次/分钟档），沿用 0.35s 间隔。
```

- [ ] **Step 4: Commit**

```bash
git add features/industry-listed-research/09-调研报告/
git commit -m "docs(industry-listed-research): income 接口实测校准报告（MS-09 P1 探测）"
```

---

### Task 2: Flyway V15 迁移 + conftest 回放清单

**Files:**
- Create: `backend/src/main/resources/db/migration/V15__stock_financial_revenue.sql`
- Modify: `collector/tests/conftest.py:29-33`（`FLYWAY_SQL_FILES` 元组追加）与 `:26-28` 注释

**Interfaces:**
- Produces: `stock_financial.revenue NUMERIC(20,4)`（可空，元，报告期累计）——Task 3/4/5 与 P2 后端全部依赖此列。

- [ ] **Step 1: 写迁移文件**

```sql
-- V15__stock_financial_revenue.sql
-- MS-09 行业研究·上市公司深化：个股财务季数据补营收绝对额（F03 行业内营收排名维度）。
-- 本 schema 为 Python 采集服务（collector）写入的跨服务契约：加列可空、纯增量，不改既有列
-- （V7 契约注释口径：表名与列类型不可随意变更）。
-- revenue：报告期累计营业收入，单位元；collector 由 tushare income.revenue 换算写入（单位见采集侧调研报告）。
ALTER TABLE stock_financial ADD COLUMN revenue NUMERIC(20,4);
```

- [ ] **Step 2: conftest 追加回放**

```python
# collector/tests/conftest.py —— FLYWAY_SQL_FILES 元组末尾（V13 之后）追加：
    "V13__analytics_close.sql",
    "V15__stock_financial_revenue.sql",
)
# 并在该元组上方注释补一行：
# V15 补 stock_financial.revenue 营收列（MS-09）。
```

- [ ] **Step 3: 验证 schema 生效（跑既有 writer 集成测试应仍全绿）**

Run: `cd collector && .venv/bin/pytest tests/test_writer_stock.py -v`
Expected: PASS（Docker 可用或 DATABASE_URL 注入时；无 Docker 则 skip——本步只验 schema 回放不炸）。

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/resources/db/migration/V15__stock_financial_revenue.sql collector/tests/conftest.py
git commit -m "feat(collector): V15 stock_financial 加 revenue 列（MS-09 营收底座）"
```

---

### Task 3: writer 扩 revenue 列（TDD）

**Files:**
- Modify: `collector/collector/store/writer.py:52-61`（`UPSERT_SQL["stock_financial"]`）与 `:88-98`（`TABLE_COLUMNS["stock_financial"]`）
- Test: `collector/tests/test_writer_stock.py`（追加用例，fixture 用法对齐该文件既有 `pg_conn` 用例）

**Interfaces:**
- Consumes: Task 2 的 `revenue` 列；
- Produces: `Store.upsert` 接受含 `revenue` 键的 `stock_financial` record（列序追加在 `netprofit_yoy` 之后）——Task 4 源输出、Task 5 converter 直通均以此列名为契约。

- [ ] **Step 1: 写失败测试**

```python
def test_upsert_stock_financial_with_revenue(pg_conn):
    """V15 后 revenue 列可写入并可回读（MS-09）。"""
    from collector.store.writer import Store

    store = Store()
    record = {
        "report_date": "20260630", "stock_code": "600519", "roe": 30.0, "roa": 20.0,
        "gross_margin": 91.0, "debt_to_assets": 20.0, "current_ratio": 4.0,
        "revenue_yoy": 15.0, "netprofit_yoy": 15.0, "revenue": 1_234_567_890.0,
    }
    written = store.upsert(pg_conn, "stock_financial", [record])
    assert written == 1
    with pg_conn.cursor() as cur:
        cur.execute("SELECT revenue FROM stock_financial WHERE stock_code = %s", ("600519",))
        assert cur.fetchone()[0] == pytest.approx(1_234_567_890.0)
```

（import 与 fixture 用法照 `tests/test_writer_stock.py:34-54` 既有用例；若该文件顶部已 import `Store`/`pytest` 则不重复。）

- [ ] **Step 2: 跑测试确认失败**

Run: `cd collector && .venv/bin/pytest tests/test_writer_stock.py::test_upsert_stock_financial_with_revenue -v`
Expected: FAIL——`revenue` 不在 `TABLE_COLUMNS` 白名单，`r.get()` 静默丢弃后 SELECT 得 None。

- [ ] **Step 3: 改 writer.py**

```python
# UPSERT_SQL["stock_financial"]（writer.py:52-61）改为：
    "stock_financial": """
        INSERT INTO stock_financial (
            report_date, stock_code, roe, roa, gross_margin, debt_to_assets, current_ratio, revenue_yoy, netprofit_yoy, revenue
        )
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
        ON CONFLICT (report_date, stock_code) DO UPDATE SET
          roe=EXCLUDED.roe, roa=EXCLUDED.roa, gross_margin=EXCLUDED.gross_margin,
          debt_to_assets=EXCLUDED.debt_to_assets, current_ratio=EXCLUDED.current_ratio,
          revenue_yoy=EXCLUDED.revenue_yoy, netprofit_yoy=EXCLUDED.netprofit_yoy, revenue=EXCLUDED.revenue
    """,
# TABLE_COLUMNS["stock_financial"]（writer.py:88-98）列表末尾追加 "revenue"。
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd collector && .venv/bin/pytest tests/test_writer_stock.py -v`
Expected: 全 PASS（含既有幂等用例）。

- [ ] **Step 5: Commit**

```bash
git add collector/collector/store/writer.py collector/tests/test_writer_stock.py
git commit -m "feat(collector): stock_financial upsert 支持 revenue 列（MS-09）"
```

---

### Task 4: StockFinancialSource 并入 income（TDD）

**Files:**
- Modify: `collector/collector/sources/plugins.py:11-13`（常量区）与 `:367-448`（`StockFinancialSource`）
- Test: `collector/tests/test_sources_stock.py`（追加 FakePro.income 与用例，模式照 `:76-104`）

**Interfaces:**
- Consumes: Task 1 调研结论（`report_type` 过滤值、单位换算系数）；Task 3 的 writer 列；
- Produces: `StockFinancialSource.fetch({})` 返回 DataFrame 列集在原 9 列后追加 `revenue`（数值，**元**；无 income 匹配行为 NaN）——Task 5 converter 直通契约。

- [ ] **Step 1: 写失败测试**

```python
def _income_frame(ts_code):
    """income 探测样例：同一 end_date 含合并(1)与单季(2)两行，单位万元（以调研报告为准）。"""
    return pd.DataFrame(
        [
            {"ts_code": ts_code, "end_date": "20260630", "report_type": "1", "revenue": 8_000_000.0},
            {"ts_code": ts_code, "end_date": "20260630", "report_type": "2", "revenue": 3_000_000.0},
            {"ts_code": ts_code, "end_date": "20260331", "report_type": "1", "revenue": 4_000_000.0},
        ]
    )


def test_stock_financial_merges_income_revenue(monkeypatch):
    """income 并入：合并报表口径、单位换算为元、按 end_date 对齐同行（MS-09）。"""
    monkeypatch.setattr(plugins, "_last_n_periods", lambda n: ["20260331", "20260630"])

    class FakePro:
        def fina_indicator(self, ts_code=None):
            assert ts_code is not None
            return _financial_stock_frame(ts_code)

        def income(self, ts_code=None, start_date=None, end_date=None, fields=None):
            assert ts_code is not None
            return _income_frame(ts_code)

        def stock_basic(self, list_status=None, fields=None):
            return _financial_stock_basic()

    src = plugins.StockFinancialSource("sf", pro_factory=lambda: FakePro())
    df = src.fetch({})
    assert "revenue" in df.columns
    row = df[(df["stock_code"] == "600519") & (df["report_date"] == "20260630")].iloc[0]
    # 合并报表 800 万万元 → 8.0e9 元；单季 300 万行被过滤不污染
    assert row["revenue"] == pytest.approx(8_000_000.0 * plugins.INCOME_REVENUE_SCALE)
    # fina_indicator 有、income 无的匹配行：revenue 为 NaN（不阻断其余列）
    row_q1 = df[(df["stock_code"] == "600519") & (df["report_date"] == "20260331")].iloc[0]
    assert pd.isna(row_q1["revenue"]) or row_q1["revenue"] == pytest.approx(4_000_000.0 * plugins.INCOME_REVENUE_SCALE)
```

（`_financial_stock_frame` / `_financial_stock_basic` 为该测试文件既有工厂（`test_sources_stock.py:76-104` 引用），其造数含 600519/000858 两期；若其报告期与 income 样例不齐，往 `_income_frame` 补齐对应期即可。`INCOME_REVENUE_SCALE` 取调研结论——万元时为 `10_000.0`，元时为 `1.0`；上面断言写成跟随常量，常量值来自 Task 1。）

- [ ] **Step 2: 跑测试确认失败**

Run: `cd collector && .venv/bin/pytest tests/test_sources_stock.py::test_stock_financial_merges_income_revenue -v`
Expected: FAIL——`AttributeError: module 'collector.sources.plugins' has no attribute 'INCOME_REVENUE_SCALE'`（或 DataFrame 无 revenue 列）。

- [ ] **Step 3: 实现（plugins.py）**

```python
# 常量区（FINANCIAL_MIN_INTERVAL 旁，plugins.py:11-13 后）：
# income 合并报表 report_type 与 revenue 单位换算系数（→元），以 09-调研报告/2026-09-17 实测为准。
INCOME_MERGED_REPORT_TYPE = "1"      # 调研结论：合并报表
INCOME_REVENUE_SCALE = 10_000.0      # 调研结论：接口单位万元 → 元（若实测为元则改 1.0）
INCOME_FIELDS = "ts_code,end_date,report_type,revenue"

# StockFinancialSource._fetch_stock 内（plugins.py:386-395 附近），fina_indicator df 过滤 cutoff 之后追加：
            self.limiter.wait()
            income_df = pro.income(ts_code=ts_code, start_date=cutoff, end_date=None, fields=INCOME_FIELDS)
            if income_df is not None and not income_df.empty:
                income_df = income_df[income_df["report_type"] == INCOME_MERGED_REPORT_TYPE]
                income_df = income_df[~income_df.duplicated(subset=["end_date"], keep="first")]
                income_df = income_df.assign(revenue=income_df["revenue"] * INCOME_REVENUE_SCALE)
                df = df.merge(income_df[["end_date", "revenue"]], on="end_date", how="left")

# 末段选列（plugins.py:438-448）在 "netprofit_yoy" 之后追加 "revenue"：
        return result[
            [
                "report_date", "stock_code", "roe", "roa", "gross_margin",
                "debt_to_assets", "current_ratio", "revenue_yoy", "netprofit_yoy", "revenue",
            ]
        ]
```

（合并键用 `end_date`（merge 发生在改名为 `report_date` 之前）；income 在 cutoff 之前的期由 merge 自然丢弃；空帧守卫 `plugins.py:413-426` 的全列名列表同步加 `revenue`。）

- [ ] **Step 4: 跑测试确认通过（含既有用例不回归）**

Run: `cd collector && .venv/bin/pytest tests/test_sources_stock.py -v`
Expected: 全 PASS（既有 `test_stock_financial_normalizes_and_backfills` 的列集断言需同步加 `revenue`——该断言在 `test_sources_stock.py:91-97`，属本步骤一并修改）。

- [ ] **Step 5: Commit**

```bash
git add collector/collector/sources/plugins.py collector/tests/test_sources_stock.py
git commit -m "feat(collector): stock_financial 并入 income 营收（合并口径+单位换算，MS-09）"
```

---

### Task 5: converter 直通 revenue（TDD）

**Files:**
- Modify: `collector/collector/scheduler/jobs.py:225-237`（`field_mapping_stock_financial`）
- Test: `collector/tests/test_field_mapping_stock_financial.py`（新建，若已有 converter 测试文件则并入）

**Interfaces:**
- Consumes: Task 4 的 DataFrame `revenue` 列；
- Produces: converter 输出 record 含 `revenue` 数值键（executor 管线 `jobs.py` → `writer.py` 的最后一环）。

- [ ] **Step 1: 写失败测试**

```python
import pandas as pd

from collector.scheduler.jobs import _field_columns


def test_field_mapping_stock_financial_passes_revenue():
    conv = _field_columns()["field_mapping_stock_financial"]
    records = conv.convert(
        pd.DataFrame([{"report_date": "20260630", "stock_code": "600519", "revenue": 8.0e9}])
    )
    assert records[0]["revenue"] == 8.0e9
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd collector && .venv/bin/pytest tests/test_field_mapping_stock_financial.py -v`
Expected: FAIL——`KeyError: 'revenue'`（映射未含该键，record 无 revenue）。

- [ ] **Step 3: 改 jobs.py**

```python
# field_mapping_stock_financial（jobs.py:225-237）映射末尾追加一行：
                "revenue_yoy": {"from": "revenue_yoy", "type": "numeric"},
                "netprofit_yoy": {"from": "netprofit_yoy", "type": "numeric"},
                "revenue": {"from": "revenue", "type": "numeric"},
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd collector && .venv/bin/pytest tests/test_field_mapping_stock_financial.py -v`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add collector/collector/scheduler/jobs.py collector/tests/test_field_mapping_stock_financial.py
git commit -m "feat(collector): field_mapping_stock_financial 直通 revenue（MS-09）"
```

---

### Task 6: 行业估值历史重算源 + 任务 + Makefile（TDD）

**Files:**
- Modify: `collector/collector/sources/plugins.py`（新类 `IndustryValuationBackfillSource`）
- Modify: `collector/collector/scheduler/jobs.py:28-39`（import）、`:154-246`（`_field_columns` 加 `field_mapping_industry_history`）、`:256-275`（`build_registries` plugins 注册）
- Create: `collector/tasks/industry_valuation_backfill.yaml`
- Modify: `Makefile:92-95` 后（新增 `industry-stock-backfill` 目标）
- Test: `collector/tests/test_industry_valuation_backfill.py`（新建）

**Interfaces:**
- Consumes: 库内 `stock_valuation_daily`（P4 回填后的 5 年历史）与 `shenwan_industry_mapping`；`collector/calc/snapshot.py:22-67` `IndustryValuationCalc`（一致性对照）；
- Produces: ① `IndustryValuationBackfillSource(source_id, conn_factory)`，`fetch({})` → DataFrame（列 `trading_day/industry_code/industry_name/pe/pb`，仅缺失日期）；② 任务 `industry_valuation_backfill`（周更，幂等自愈）；③ `make industry-stock-backfill`（5 年分段回填封装）——P4 Task 1 直接调用。

- [ ] **Step 1: 写失败测试（两条，pg 集成）**

```python
"""行业估值历史重算源：只补缺失日期 + 与 IndustryValuationCalc 同口径（MS-09 设计 §3.3）。"""
import pytest

from collector.calc.snapshot import IndustryValuationCalc
from collector.scheduler.jobs import _field_columns
from collector.sources.plugins import IndustryValuationBackfillSource
from collector.store.writer import Store


def _seed_day(pg_conn, day, rows):
    """rows: [(stock_code, industry_code, industry_name, pe_ttm, pb, total_mv)]"""
    with pg_conn.cursor() as cur:
        for code, ind_code, ind_name, pe, pb, mv in rows:
            cur.execute(
                "INSERT INTO stock_valuation_daily (trading_day, stock_code, stock_name, pe_ttm, pb, total_mv)"
                " VALUES (%s, %s, %s, %s, %s, %s)"
                " ON CONFLICT (trading_day, stock_code) DO NOTHING",
                (day, code, "N" + code, pe, pb, mv),
            )
            cur.execute(
                "INSERT INTO shenwan_industry_mapping (stock_code, stock_name, industry_code, industry_name)"
                " VALUES (%s, %s, %s, %s) ON CONFLICT (stock_code) DO NOTHING",
                (code, "N" + code, ind_code, ind_name),
            )
    pg_conn.commit()


def test_backfill_source_only_missing_dates(pg_conn):
    """已有快照日（含 roe/股息率）不被重算触碰；仅产出更早的缺失日。"""
    _seed_day(pg_conn, "2026-01-01", [
        ("600519", "801780", "银行", 10.0, 1.5, 100.0),
        ("601398", "801780", "银行", 20.0, None, 100.0),
    ])
    _seed_day(pg_conn, "2026-01-02", [("600519", "801780", "银行", 11.0, 1.6, 100.0)])
    with pg_conn.cursor() as cur:  # 每日任务已写过 01-02（roe=15 非空）
        cur.execute(
            "INSERT INTO industry_valuation (trading_day, industry_code, industry_name, pe, pb, roe, dividend_yield)"
            " VALUES ('2026-01-02', '801780', '银行', 11.0, 1.6, 15.0, 4.0)"
            " ON CONFLICT (trading_day, industry_code) DO NOTHING"
        )
    pg_conn.commit()

    def conn_factory():
        return pg_conn

    src = IndustryValuationBackfillSource("industry_valuation_backfill", conn_factory=conn_factory)
    df = src.fetch({})
    assert set(df["trading_day"]) == {"2026-01-01"}          # 只补缺失日，不含已有 01-02

    records = _field_columns()["field_mapping_industry_history"].convert(df)
    Store().upsert(pg_conn, "industry_valuation", records)
    with pg_conn.cursor() as cur:
        cur.execute("SELECT pe, roe, dividend_yield FROM industry_valuation"
                    " WHERE trading_day = '2026-01-02' AND industry_code = '801780'")
        assert cur.fetchone() == (11.0, 15.0, 4.0)            # 既有行原值未动
        cur.execute("SELECT pe, roe FROM industry_valuation"
                    " WHERE trading_day = '2026-01-01' AND industry_code = '801780'")
        pe, roe = cur.fetchone()
        assert pe == pytest.approx(15.0)                       # Σ(pe×mv)/Σmv = (10×100+20×100)/200
        assert roe is None                                     # 历史行 roe 置 NULL（设计口径）

    assert src.fetch({}).empty                                 # 幂等：重跑无缺失即 0 行


def test_backfill_source_matches_calc(pg_conn):
    """SQL 重算与 IndustryValuationCalc 同输入逐值相等（双实现一致性，含 pb 条件加权边界）。"""
    rows = [
        ("600519", "801780", "银行", 10.0, 1.5, 100.0),
        ("601398", "801780", "银行", 20.0, None, 100.0),   # pb 缺失：pb 加权分母只计 100
        ("000001", "801780", "银行", -5.0, 1.0, 50.0),     # pe<=0：整行剔除（calc 口径 snapshot.py:25-27）
    ]
    _seed_day(pg_conn, "2026-02-02", rows)

    def conn_factory():
        return pg_conn

    df = IndustryValuationBackfillSource("iv", conn_factory=conn_factory).fetch({})
    calc_rows = [
        {"industry_code": c, "industry_name": n, "pe": pe, "pb": pb, "market_cap": mv}
        for _, c, n, pe, pb, mv in rows
    ]
    calc_out = {r["industry_code"]: r for r in IndustryValuationCalc().compute(calc_rows)}
    sql_row = df[df["industry_code"] == "801780"].iloc[0]
    assert sql_row["pe"] == pytest.approx(calc_out["801780"]["pe"], rel=1e-4)
    assert sql_row["pb"] == pytest.approx(calc_out["801780"]["pb"], rel=1e-4)
```

（`pg_conn` fixture 用法对齐 `tests/conftest.py` 会话级 schema + 既有 `test_writer_stock.py`；跨用例数据隔离：两个用例用不同交易日。）

- [ ] **Step 2: 跑测试确认失败**

Run: `cd collector && .venv/bin/pytest tests/test_industry_valuation_backfill.py -v`
Expected: FAIL——`ImportError: cannot import name 'IndustryValuationBackfillSource'`。

- [ ] **Step 3: 实现源（plugins.py，文件末尾新类）**

```python
class IndustryValuationBackfillSource(Source):
    """行业估值历史重算：从库内 stock_valuation_daily × 申万映射重算缺失日期的行业加权 PE/PB。

    仅产出早于 industry_valuation 现存最早快照日（且早于当日）的日期——与每日增量任务
    日期不相交，writer upsert 的 SET 不会触碰既有行的 roe/股息率；稳态（无缺失）返回空帧。
    口径与 IndustryValuationCalc 一致（记录级过滤 pe>0 且市值非空；pb 条件加权），
    由 tests/test_industry_valuation_backfill.py 双实现一致性测试锚定。
    supports_range=False：常规调度即自愈，不走 backfill CLI（backfill.py D3 会拒绝）。
    """

    supports_range = False

    _SQL = """
        SELECT d.trading_day, m.industry_code, MAX(m.industry_name) AS industry_name,
               SUM(d.pe_ttm * d.total_mv) / NULLIF(SUM(d.total_mv), 0) AS pe,
               SUM(d.pb * d.total_mv) / NULLIF(SUM(CASE WHEN d.pb IS NOT NULL THEN d.total_mv END), 0) AS pb
        FROM stock_valuation_daily d
        JOIN shenwan_industry_mapping m ON m.stock_code = d.stock_code
        WHERE d.pe_ttm > 0 AND d.total_mv IS NOT NULL
          AND d.trading_day < CURRENT_DATE
          AND d.trading_day < COALESCE((SELECT MIN(trading_day) FROM industry_valuation), '2999-12-31')
        GROUP BY d.trading_day, m.industry_code
        ORDER BY d.trading_day
    """

    def __init__(self, source_id, conn_factory):
        self.source_id = source_id
        self.conn_factory = conn_factory

    def fetch(self, params):
        with self.conn_factory() as conn, conn.cursor() as cur:
            cur.execute(self._SQL)
            rows = cur.fetchall()
        return pd.DataFrame(
            rows, columns=["trading_day", "industry_code", "industry_name", "pe", "pb"]
        )
```

（`conn_factory` 返回的是连接或上下文管理器均可——`IndustryUniverseSource` 同款用法见 `plugins.py:133-171`；若其用法是 `conn = self.conn_factory()` 后显式 close，照抄那种。）

- [ ] **Step 4: 注册（jobs.py 三处）**

```python
# 1) import（jobs.py:28-39 元组内按字母序插入）：
    IndustryValuationBackfillSource,

# 2) _field_columns() 加直通 converter（field_mapping_industry_history，from==to）：
        "field_mapping_industry_history": FieldMappingConverter(
            {
                "trading_day": {"from": "trading_day", "type": "str"},
                "industry_code": {"from": "industry_code", "type": "str"},
                "industry_name": {"from": "industry_name", "type": "str"},
                "pe": {"from": "pe", "type": "numeric"},
                "pb": {"from": "pb", "type": "numeric"},
            }
        ),

# 3) build_registries 的 plugins（jobs.py:256-275，conn_factory 已在作用域内）：
            "industry_valuation_backfill": IndustryValuationBackfillSource(
                "industry_valuation_backfill", conn_factory=conn_factory
            ),
```

- [ ] **Step 5: 任务 yaml（新建 collector/tasks/industry_valuation_backfill.yaml）**

```yaml
task_code: industry_valuation_backfill
task_name: 行业估值历史重算
# 纯本地 DB→DB 重算（无外部调用），幂等自愈：只补缺失日期，稳态 0 行属正常——
# 故 validator 置 null（min_rows hard 会在第二次运行起必失败）。
source_ids:
  - { source_id: industry_valuation_backfill, type: plugin, class: industry_valuation_backfill }
converter: field_mapping_industry_history
calc: null
validator: null
target_table: industry_valuation
schedule: { type: interval, days: 7 }
enabled: true
trading_day_gated: false
retry_max: 3
retry_backoff: exponential
```

- [ ] **Step 6: Makefile 目标（`collect-backfill` 目标之后追加）**

```make
## MS-09：5 年个股估值分段回填（半年一段 × 10 次调用 collect-backfill，规避单事务全量写）
industry-stock-backfill:
	python3 -c "import subprocess, datetime as dt; end = dt.date.today(); [subprocess.run(['make', 'collect-backfill', 'TASK=stock_valuation_daily', 'START=' + (s := (end - dt.timedelta(days=183 * (i + 1)))).isoformat(), 'END=' + (e := (end - dt.timedelta(days=183 * i))).isoformat()], check=True) for i in range(10)]"
```

（变量名 `TASK/START/END` 对齐 `Makefile:92-95` 既有 `collect-backfill`。）

- [ ] **Step 7: 跑测试确认通过**

Run: `cd collector && .venv/bin/pytest tests/test_industry_valuation_backfill.py -v`
Expected: 2 PASS。

- [ ] **Step 8: Commit**

```bash
git add collector/collector/sources/plugins.py collector/collector/scheduler/jobs.py \
        collector/tasks/industry_valuation_backfill.yaml collector/tests/test_industry_valuation_backfill.py Makefile
git commit -m "feat(collector): 行业估值历史重算任务 + 5 年分段回填封装（MS-09 F04 底座）"
```

---

### Task 7: P1 收尾——全量质量门槛

**Files:** 无新增（验证 + 格式化）。

**Interfaces:**
- Produces: P1 完成态——P2 后端可与本阶段并行启动（仅依赖 V15 已合入）。

- [ ] **Step 1: 格式化 + 静态检查**

Run: `cd collector && .venv/bin/ruff format . && .venv/bin/ruff check . && .venv/bin/lint-imports`
Expected: 无错误。

- [ ] **Step 2: 全量测试（含覆盖率门槛）**

Run: `cd /Users/lixiaoyi/GitRepository/portfolio-management && make collect-test`
Expected: pytest 全绿、覆盖率 ≥80%。

- [ ] **Step 3: 后端编译不破坏（V15 只是加列）**

Run: `cd backend && ./gradlew compileJava -q`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: Commit（如有格式化改动）**

```bash
git add -A collector/ && git commit -m "chore(collector): P1 收尾格式化（MS-09）"
```
