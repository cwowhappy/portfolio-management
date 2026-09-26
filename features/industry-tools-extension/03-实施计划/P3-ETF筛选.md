# P3 · ETF 筛选（M09-F06，仅场内）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `/screener` 第三 tab「基金」——ETF 四维筛选（费率/规模/跟踪误差/指数类别）+ ⭐ 加自选 + 导出；collector 建全套数据面（目录/费率/规模/日线/跟踪误差自算）。

**Architecture:** Task 0 探测先行（不可得维度降级拍板）；数据面 = `etf_basic` 新表（V18）+ ETF/指数日线复用 `index_close_history`（照 518880 先例）+ 跟踪误差 collector DB→DB 日更重算（照 industry_valuation_backfill 先例）；后端并入 `domain/screening`（FundScreeningCriteria 平移个股模式）；watchlist add 校验扩展命中 ETF 目录。

**Tech Stack:** 同 P1 + akshare 1.16.72 / tushare 1.4.21（collector 依赖已在）/ pytest。

**Spec:** [01-需求规格](../01-需求规格/需求规格说明.md)（决策 #2/#3/#6/#12/#13）+ [02-设计规格](../02-设计规格/设计规格说明.md) §3。

## Global Constraints

- 继承 P1/P2 全部约束。
- **Task 0 是 Task 13~15 的闸门**：探测结论（接口/字段/限频）回填本计划对应任务再开工；主源不可得的维度走降级列「—」，降级须在 09-调研报告留痕（重大降级拍板照 MS-13 先例）。
- ETF 代码口径：6 位无后缀（`518880`），与 `index_close_history.index_code` / `watchlist_item.stock_code` 同格式；ETF 日线行 `index_name`=基金简称（照 518880「华安黄金ETF」先例）。
- 跟踪误差口径（设计规格 §3.1）：收盘价口径，窗口近 1 年，`std(ETF日收益 − 指数日收益) × √252`，有效样本 <120 置 null；两序列按 trading_day inner join，不插值。
- fund_daily 调用间保守 sleep 0.3~0.5s（MS-13 探测建议）；目录/费率任务周更、日线/误差日更。

---

### Task 0: ETF 数据源探测（非 TDD，调研任务）

**Files:**
- Create: `features/industry-tools-extension/09-调研报告/2026-09-25-ETF数据源探测.md`

**Interfaces:**
- Consumes: `collector/.env` token（`collector.config.load()` 读取方式）；本机出口（预研已实证 fund.eastmoney/fundf10/fundgz 200、push2his WAF 拒答）。
- Produces: 四项结论——①目录+跟踪指数+类别主源（akshare `fund_etf_category_sina` vs 东财 ETF 列表）②费率/规模接口与字段 ③`fund_daily(trade_date=...)` 全市场模式行数/限频（vs 单代码循环）④行情 quoteBatch 对 ETF 代码支持——决定 Task 13/14/15 的源选择与 Task 17/18 的展示口径。

- [ ] **Step 1: 目录/费率/规模探测（akshare + 天天基金）**

```bash
cd collector && python3 -c "
import akshare as ak
df = ak.fund_etf_category_sina(symbol='ETF基金')   # 新浪 ETF 列表（含代码/名称）
print(df.shape, df.columns.tolist()); print(df.head(3))
# 费率/规模候选：ak.fund_etf_fund_daily_em() / 东财 ETF 列表 ak.fund_etf_spot_em()（含规模?）
# 逐个探测字段，记录：能否取到 管理费/托管费/规模/跟踪指数
"
# 天天基金费率页直采验证（预研 200 的落地确认）：
curl -s 'https://fundf10.eastmoney.com/jjfl_518880.html' | grep -o '管理费率[^<]*' | head -3
```

- [ ] **Step 2: fund_daily 两种模式探测**

```bash
cd collector && python3 -c "
import tushare as ts; from collector.config import load_config
pro = ts.pro_api(load_config().tushare_token)
df1 = pro.fund_daily(trade_date='20260924')          # 全市场当日模式
print('trade_date 模式:', df1.shape); print(df1.head(2))
df2 = pro.fund_daily(ts_code='510300.SH', start_date='20250901', end_date='20260924')  # 单代码区间
print('区间模式:', df2.shape)
"
```

- [ ] **Step 3: quoteBatch ETF 支持探测（决定自选列表现价列口径）**

```bash
# 起后端后 curl 既有行情端点带 ETF 代码（如 /api/market/quote?code=518880 或批量端点——按 MarketDataController 实际路由）
```

- [ ] **Step 4: 结论写报告**（表格：数据/主源/字段/样本行数/限频表现/裁决；不可得项列降级方案；照 MS-13 三源探测报告格式）+ Commit（`docs(ms14): ETF 数据源探测报告`）

---

### Task 13: V18 etf_basic 表 + collector etf_basic 任务（目录/费率/规模）

**Files:**
- Create: `backend/src/main/resources/db/migration/V18__etf_screening.sql`（DDL 见设计规格 §3.1）
- Modify: `collector/collector/sources/plugins.py`（`EtfBasicSource`——按 Task 0 主源结论实现）
- Modify: `collector/collector/scheduler/jobs.py`（plugins dict 注册一行，照 :290 gold_etf_close 位置）
- Create: `collector/tasks/etf_basic.yaml`（周更 `cron: "30 9 * * 1"`、validator min_rows=100、converter 按 Task 0 字段映射）
- Test: `collector/tests/test_plugins.py`（追加，FakeAk/FakePro 套路照 test_gold_etf_close 先例）

**Interfaces:**
- Consumes: Task 0 结论。
- Produces: `etf_basic` 表数据（fund_code/fund_name/fee_rate/scale/tracking_index_code/tracking_index_name/category）——Task 14/15/16 消费。

- [ ] **Step 1: 写失败测试（FakePro/FakeAk 字段契约 + 缺字段容错）**

```python
def test_etf_basic_maps_columns(mocker):
    from collector.collector.sources.plugins import EtfBasicSource
    class FakeAk:
        def fund_etf_category_sina(self, symbol):   # 以 Task 0 主源为准替换
            return pd.DataFrame({...三行样例：宽基/行业/商品 ETF，含跟踪指数与类别推断输入...})
    df = EtfBasicSource("etf_basic", ak=lambda: FakeAk()).fetch({})
    assert set(df.columns) == {"fund_code", "fund_name", "fee_rate", "scale",
                               "tracking_index_code", "tracking_index_name", "category"}
# 附加：费率/规模缺失行 → NaN（不阻断）；fund_code 6 位化（去后缀）
```

- [ ] **Step 2: 确认失败 → Step 3: 实现源类+任务+注册（category 推断规则：跟踪指数名含行业词→行业/含债→债券/含黄金/商品→商品/QDII 关键词→QDII/其余宽基——规则常量化可测）**
- [ ] **Step 4: `cd collector && python -m pytest tests/test_plugins.py -k etf_basic` 通过 + 手跑一次任务真实落库抽查 → Step 5: Commit**（`feat(collector): etf_basic 目录/费率/规模采集任务`）

---

### Task 14: collector etf_close + tracking_index_close 日线任务

**Files:**
- Modify: `collector/collector/sources/plugins.py`（`EtfCloseSource`——fund_daily **trade_date 全市场模式**（Task 0 确认）；`TrackingIndexCloseSource`——index_daily 按 etf_basic.tracking_index_code 去重循环）
- Modify: `collector/collector/scheduler/jobs.py`（注册两行）
- Create: `collector/tasks/etf_close.yaml`（日更 `cron: "50 15 * * 1-5"`、trading_day_gated、converter=field_mapping_index_close、validator min_rows=500——按 Task 0 实测全市场行数调整）、`collector/tasks/tracking_index_close.yaml`（日更、validator min_rows=50）
- Test: `collector/tests/test_plugins.py`（追加）

**Interfaces:**
- Produces: `index_close_history` 新增两类行——ETF（index_code=6 位基金码，index_name=简称）与跟踪指数（index_code=6 位指数码）——Task 15 重算消费、Task 16 展示 tracking_index_name。

- [ ] **Step 1: 失败测试（两源字段契约：EtfCloseSource 单次 fetch 返回全市场当日四列 trading_day/index_code/index_name/close；TrackingIndexCloseSource 读 etf_basic 去重循环 index_daily，FakePro 断言循环代码集）**
- [ ] **Step 2~4: 实现 + 通过 + 手跑回填（近 1 年窗口 `start/end` 参数 supports_range 模式，照 gold_etf_close）**
- [ ] **Step 5: Commit**（`feat(collector): ETF 与跟踪指数日线采集（index_close_history 复用）`）

---

### Task 15: collector etf_tracking_error 日更重算（DB→DB）

**Files:**
- Modify: `collector/collector/sources/plugins.py`（`EtfTrackingErrorSource`——读 index_close_history 两序列对齐算 `std(diff)×√252`，**upsert etf_basic.tracking_error_1y**；任务模式照 industry_valuation_backfill：validator null、interval 日更——schedule `{ type: cron, cron: "30 16 * * 1-5" }`）
- Modify: `collector/collector/scheduler/jobs.py`（注册）
- Create: `collector/tasks/etf_tracking_error.yaml`
- Test: `collector/tests/test_plugins.py`（追加）

**Interfaces:**
- Consumes: Task 13 etf_basic.tracking_index_code 映射 + Task 14 两类日线。
- Produces: `etf_basic.tracking_error_1y`（null=样本<120）——Task 16 排序/筛选消费。

- [ ] **Step 1: 失败测试（已知答案：构造 250 日两序列，ETF 恒等于指数 → 误差 0；ETF 每日多 0.1% → 误差≈0.1%×√252；样本 119 日 → null 不回写）**
- [ ] **Step 2~4: 实现（pandas merge on trading_day → pct_change → diff.std(ddof=1)×√252 → UPDATE etf_basic）+ 通过 + 手跑抽查 510300/518880 实测值合理（宽基~1%内）**
- [ ] **Step 5: Commit**（`feat(collector): ETF 跟踪误差近1年自算重算任务`）

---

### Task 16: 后端基金筛选（domain/screening 扩展 + 端点）

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/domain/screening/FundScreeningCriteria.java`（record：`feeRateMax, scaleMin, trackingErrorMax, category, sortBy, sortDirection, limit`；`SORTABLE_FIELDS={fee_rate, scale, tracking_error_1y}`；`CATEGORIES={宽基,行业,商品,债券,QDII,其他}` 白名单；`hasAnyCondition()`——照 `ScreeningCriteria` 紧凑构造器白名单模式）
- Create: `backend/src/main/java/com/portfolio/invest/domain/screening/FundScreeningResult.java`（record：fundCode/fundName/feeRate/scale/trackingIndexName/category/trackingError1y——null 字段=「—」）
- Modify: `backend/src/main/java/com/portfolio/invest/domain/screening/ScreeningRepository.java`（+`List<FundScreeningResult> findFunds(FundScreeningCriteria c)`）
- Modify: `backend/src/main/java/com/portfolio/invest/infrastructure/persistence/ScreeningRepositoryImpl.java`（etf_basic 查询；nullable 列直接 LEAST/GREATEST 语义：`fee_rate <= ?` 自然过滤 null——文档化「未知值不命中数值条件」）
- Modify: `backend/src/main/java/com/portfolio/invest/application/screening/ScreeningApplicationService.java`（+`funds(FundScreeningCriteria)`：NO_CONDITION/limit 1-200/缓存照个股 :42-58 模式）
- Modify: `backend/src/main/java/com/portfolio/invest/web/ScreeningController.java`（+`GET /api/screening/funds` + `GET /api/screening/funds/export`——照 :39-63/:78 逐参数模式；CSV 复用 `ScreeningCsv`）
- Test: domain/application/web 三层各一测试文件（照个股同名测试结构）

**Interfaces:**
- Consumes: `etf_basic` 表（Task 13）；`ScreeningCsv` 转义口径。
- Produces（Task 17/18 依赖）: `GET /api/screening/funds?feeRateMax=&scaleMin=&trackingErrorMax=&category=&sortBy=&sortDirection=&limit=`（公开端点，落 /api/screening/** 公开前缀）与 export（attachment `fund-screening-*.csv`）。

- [ ] **Step 1: 写失败测试（Criteria 白名单/无条件拒绝；repository SQL 数值过滤与 null 语义；service NO_CONDITION+limit；controller 参数透传+export Content-Disposition）**
- [ ] **Step 2: 确认失败 → Step 3: 实现 → Step 4: 通过**
- [ ] **Step 5: Commit**（`feat(screening): ETF 四维筛选端点与导出（并入个股筛选域）`）

---

### Task 17: watchlist add 校验扩展（ETF 可加自选，决策 #13）

**Files:**
- Modify: `backend/src/main/java/com/portfolio/invest/application/screening/WatchlistApplicationService.java`（add :60-72 存在性校验扩展）
- Modify: `backend/src/main/java/com/portfolio/invest/domain/screening/ScreeningRepository.java`（+`boolean existsFund(String fundCode)`——本任务自备，Task 16 不产它）
- Modify: `backend/src/main/java/com/portfolio/invest/infrastructure/persistence/ScreeningRepositoryImpl.java`（existsFund 实现：etf_basic COUNT>0）
- Test: `backend/src/test/java/com/portfolio/invest/application/screening/WatchlistApplicationServiceTest.java`（追加）

**Interfaces:**
- Consumes: `etf_basic` 表（Task 13）。
- Produces: ETF 代码 add 通过（列表 View 的股票快照字段 null——row==null 分支 :47-55 已容忍；实时价按 Task 0 结论，不可得 null 前端「—」）。

- [ ] **Step 1: 失败测试（givenEtfCodeNotInStockSnapshotButInEtfBasic_whenAdd_thenSaved；givenUnknownEverywhere_whenAdd_thenInvalidStock）**
- [ ] **Step 2~4: 实现（`if (findStocksByCodes(...).isEmpty() && !screeningRepository.existsFund(code)) throw INVALID_STOCK`）+ 通过**
- [ ] **Step 5: Commit**（`feat(screening): 自选支持 ETF 代码（存在性校验并集）`）

---

### Task 18: 前端基金 tab（表单+结果表+⭐+导出）

**Files:**
- Modify: `frontend/components/screening/ScreenerBoard.tsx`（tab 联合 `"screener" | "fund" | "watchlist"`；fund 状态与提交）
- Create: `frontend/components/screening/FundScreeningForm.tsx`（三数值+类别下拉，GROUPS 常量模式平移）
- Create: `frontend/components/screening/FundResultsTable.tsx`（7 列+⭐+导出 `<a download>` 照个股表模式）
- Create: `frontend/lib/fundScreeningApi.ts`（buildQuery/fetchFundScreening/buildFundExportHref 照 `screeningApi.ts` 模式）
- Modify: `frontend/lib/schemas.ts`（+FundScreeningResult View schema）
- Test: 三组件 + API 封装 vitest 各一（照个股同名测试结构）

**Interfaces:**
- Consumes: Task 16 端点；Task 17 ⭐；`watchlistCodes` Set 直接含 ETF 代码。
- Produces: F06 完整交互（无下游）。

- [ ] **Step 1: 失败测试（表单三数值+下拉、至少一条件校验、结果列渲染 null→「—」、⭐ toggle、导出 href 参数拼接）**
- [ ] **Step 2~4: 实现 + vitest 全绿**
- [ ] **Step 5: Commit**（`feat(screening): /screener 基金 tab（ETF 四维筛选+自选+导出）`）

---

### Task 19: e2e + 里程碑文档收尾（MS-14 全量收口）

**Files:**
- Modify: `frontend/e2e/screener.spec.ts`（追加基金 tab 用例——空库门控范式：无 etf_basic 数据时 skip 或空态断言）
- Modify: `docs/plans/2026-08-27-产品落地计划.md`（MS-14 状态 ⏳→✅ 3/4 注记 + 变更记录行）
- Modify: `docs/function/modules/08-持仓组合管理.md` / `09-价值投资筛选器.md` / `10-行业研究中心.md`（F12/F06/F12 ⏳→✅ + 交付说明）
- Modify: `docs/function/00-功能模块概览.md` + `01-产品概览.md`（计数 105→108）
- Modify: `docs/technology/conventions/01-后端DDD分包规范.md`（说明性更新：screening 扩 ETF、industry 扩 watch，无新包）
- Create: `features/industry-tools-extension/08-复盘总结/`（复盘文档）

**Interfaces:**
- Consumes: P1~P3 全部交付。

- [ ] **Step 1: e2e（门控）**

```ts
// 1. /screener → 基金 tab 渲染表单四控件；空库断言空态文案（不依赖采集数据）
// 2. 库有数据（本地真库）→ 类别选"宽基"+费率≤0.6 → 结果行渲染 + ⭐ 加入自选 → 自选 tab 出现 ETF 行
// 3. 导出 CSV 文件名 fund-screening-YYYYMMDD-HHMMSS.csv
```

- [ ] **Step 2: 全量验证**

Run: `make test` + `E2E_FRESH_BUILD=1 npx playwright test`（全量）+ `make smoke`（无新实时外源，不新增段，确认 §1-§5 仍全绿）。

- [ ] **Step 3: 文档收尾（上列 docs 六处）+ 复盘总结（含 Task 0 探测结论回填、DEFER 项留痕）**
- [ ] **Step 4: Commit**（`docs(ms14): MS-14 三点交付收尾（3/4，F11 随 MS-10）`）
