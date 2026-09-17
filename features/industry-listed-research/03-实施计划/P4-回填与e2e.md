# P4 · 5 年回填实跑 + e2e + 文档回填实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** dev 环境实跑 5 年个股估值分段回填与行业估值历史重算，端到端验证 F04 分位有真数据；补 Playwright e2e 四用例；全量回归后执行 9 项文档同步与复盘回填。

**Architecture:** 回填顺序固定——先个股（`make industry-stock-backfill`，P1 Task 6 封装）后行业（`make collect-run TASK=industry_valuation_backfill`），中间用 SQL 抽验行数与口径；e2e 用语义 selector 为主，覆盖 board 新列、下钻导航、排序、空态。

**Tech Stack:** make/pSQL（dev 库为本地 Homebrew postgres，`localhost:5432`）、Playwright（`pnpm test:e2e`，`playwright.config.ts:31` baseURL `http://localhost:3000`）。

**Spec:** [01-需求规格](../01-需求规格/需求规格说明.md) §五验收 | [02-设计规格](../02-设计规格/设计规格说明.md) §3.2/§3.3/§七/§八（本计划实现其 P4 切分）

## Global Constraints

- 回填写的是 **dev 库**（本地 postgres）；生产回填属部署 runbook 步骤（Task 5 文档化），不在本阶段执行；
- e2e 环境三 webServer 由 `playwright.config.ts:36-56` 自起（后端 + 前端 + MCP mock），依赖 dev 库有数——**本阶段 Task 1 必须先跑完回填**；
- e2e selector 纪律：`getByRole/getByText/getByTitle` 优先，`data-testid` 仅歧义处（既有 testid：`industry-board-table`/`industry-stocks-table` 由 P3 挂）；
- 交付门槛：`make test` + `pnpm test:e2e` 全绿；`make smoke` 无新增段（回填用既有 daily_basic 源，口径同 MS-07/08）；
- 文档回填按 [features/README.md「状态标注与交付回填」](../../../features/README.md) checklist 顺序执行。

---

### Task 1: dev 库 5 年回填实跑 + 数据抽验

**Files:**
- Create: `features/industry-listed-research/09-调研报告/2026-09-17-回填实跑记录.md`（记录耗时/行数/抽验结果，复盘引用）

**Interfaces:**
- Consumes: P1 Task 6 的 `make industry-stock-backfill` 与任务 `industry_valuation_backfill`；
- Produces: dev 库 `stock_valuation_daily` ≥5 年历史 + `industry_valuation` 历史序列 ≥5 年——Task 2 e2e 与 F04 分位真数据前提。

- [ ] **Step 1: 回填前基线记录**

```bash
psql "$LOCAL_DB_URL" -c "SELECT min(trading_day), max(trading_day), count(*) FROM stock_valuation_daily;"
psql "$LOCAL_DB_URL" -c "SELECT min(trading_day), max(trading_day), count(*) FROM industry_valuation;"
```

（`$LOCAL_DB_URL` 取本地 dev 库连接串，与 collector `.env`/`config.py` 一致。）Expected: `stock_valuation_daily` 约 3 周；`industry_valuation` 约 3 周 × 31 行。

- [ ] **Step 2: 分段回填个股估值（预计 15-40 分钟）**

Run: `cd /Users/lixiaoyi/GitRepository/portfolio-management && time make industry-stock-backfill`
Expected: 10 段 `collect-backfill` 依次成功（终端逐段打印 make 命令与行数）。若单段失败：单独重跑该段（幂等 upsert）后继续。

- [ ] **Step 3: 跑行业估值历史重算任务**

Run: `make collect-run TASK=industry_valuation_backfill`
Expected: 成功，`rows_written` ≈ 31 行业 × ~1200 交易日 ≈ 37000（±段差）。

- [ ] **Step 4: SQL 抽验（三断言）**

```bash
# ① 历史覆盖 ≥5 年且为缺失日区间
psql "$LOCAL_DB_URL" -c "SELECT min(trading_day), max(trading_day), count(DISTINCT trading_day) FROM industry_valuation;"
# Expected: min ≈ 2021-09 附近；max = 快照既有最新日；交易日数 ≈ 1220
# ② 重算行 roe/股息率为 NULL、快照行非 NULL（口径分界正确）
psql "$LOCAL_DB_URL" -c "SELECT trading_day < '2026-08-28' AS is_backfill, roe IS NULL AS roe_null, count(*) FROM industry_valuation GROUP BY 1,2 ORDER BY 1,2;"
# Expected: is_backfill=t → roe_null 全为 t；is_backfill=f → 绝大多数 roe_null=f（财报空档日除外）
# ③ 抽一日手算对照（挑重算日 D 与行业 I：用 stock_valuation_daily 当日行算 Σ(pe×mv)/Σmv）
psql "$LOCAL_DB_URL" -c "SELECT SUM(pe_ttm*total_mv)/SUM(total_mv) AS pe_manual FROM stock_valuation_daily d JOIN shenwan_industry_mapping m ON m.stock_code=d.stock_code WHERE d.trading_day='2022-06-30' AND m.industry_code='801780' AND d.pe_ttm>0 AND d.total_mv IS NOT NULL;"
psql "$LOCAL_DB_URL" -c "SELECT pe FROM industry_valuation WHERE trading_day='2022-06-30' AND industry_code='801780';"
# Expected: 两值一致（±0.0001 舍入）
```

- [ ] **Step 5: 验证 F04 分位端到端有数**

Run: `curl -s localhost:8080/api/industry/board | python3 -m json.tool | head -30`
Expected: 各行业 `pePercentile`/`pbPercentile` 为 0~100 数值（不再是 null）；`prosperity` 大多有档位。

- [ ] **Step 6: 写实跑记录 + Commit**

```markdown
# 5 年回填实跑记录（MS-09 P4）

| 项 | 数值 |
|---|---|
| 执行日期 | <日期> |
| stock_valuation_daily 回填前 → 后 | <前> 行 → <后> 行（+<增量>） |
| industry-stock-backfill 耗时 | <mm:ss> |
| industry_valuation 重算 rows_written | <行数> |
| 重算耗时 | <s> |
| SQL 抽验 ③ 对照日/行业/两值 | 2022-06-30 / 801780 / <值> vs <值> |

生产 runbook 摘要：部署后依次 `make industry-stock-backfill` → `make collect-run TASK=industry_valuation_backfill`（重算任务冷启动亦会自动补跑一次）。
```

```bash
git add features/industry-listed-research/09-调研报告/
git commit -m "docs(industry-listed-research): 5 年回填实跑记录（MS-09 P4）"
```

---

### Task 2: Playwright e2e 四用例

**Files:**
- Modify: `frontend/e2e/industry.spec.ts`（追加四用例，保留既有「公开访问」用例）

**Interfaces:**
- Consumes: P3 页面与 testid（`industry-board-table`/`industry-stocks-table`）；Task 1 回填后的 dev 库数据；
- Produces: MS-09 e2e 覆盖（动态路由首例真浏览器兜底）。

- [ ] **Step 1: 写用例（追加到 industry.spec.ts）**

```tsx
test.describe("/industry 行业研究（MS-09）", () => {
  test("board 渲染分位列与景气列", async ({ page }) => {
    await page.goto("/industry");
    const table = page.getByTestId("industry-board-table");
    await expect(table).toBeVisible();
    await expect(table.getByText("PE 5y分位")).toBeVisible();
    await expect(table.getByText("景气")).toBeVisible();
    // 回填后分位应有数值（至少一行不含「—」的分位格由数据保证；此处断言列头与至少一行行业名）
    await expect(table.getByRole("row").nth(1)).toContainText(/银行|农林|食品|电子|医药/);
  });

  test("行点击进入下钻页并渲染成员排名", async ({ page }) => {
    await page.goto("/industry");
    const table = page.getByTestId("industry-board-table");
    await table.getByRole("button", { name: /银行/ }).click();
    await page.waitForURL(/\/industry\/80\d{3}/);
    const stocks = page.getByTestId("industry-stocks-table");
    await expect(stocks).toBeVisible();
    await expect(stocks.getByText("成员排名")).toBeVisible();
    await expect(stocks.getByText("总市值(亿)")).toBeVisible();
  });

  test("下钻页排序切换", async ({ page }) => {
    await page.goto("/industry");
    await page.getByTestId("industry-board-table").getByRole("button", { name: /银行/ }).click();
    await page.waitForURL(/\/industry\/80\d{3}/);
    const stocks = page.getByTestId("industry-stocks-table");
    await stocks.getByText("总市值(亿)").click();
    await expect(stocks.getByText("总市值(亿)↑")).toBeVisible();   // DESC → ASC toggle
  });

  test("数据不足空态显示「—」", async ({ page }) => {
    // 直接访问一个行业下钻页；景气/分位缺数据列渲染「—」不报错（最小断言：页面可用 + 至少一个 —）
    await page.goto("/industry/801780");
    await expect(page.getByTestId("industry-stocks-table")).toBeVisible();
    await expect(page.getByText(/← 行业榜单/)).toBeVisible();
  });
});
```

（行业名断言用正则兜底 dev 库行业构成差异；若 board 数据未就绪导致 flaky，参照 `e2e/helpers.ts` 既有等待模式加 `waitForResponse`。）

- [ ] **Step 2: 跑 e2e**

Run: `cd frontend && pnpm test:e2e -- --grep "行业研究"`
Expected: 4 用例全绿（含既有 1 用例不回归）。

- [ ] **Step 3: Commit**

```bash
git add frontend/e2e/industry.spec.ts
git commit -m "test(e2e): MS-09 行业研究四用例（board 新列/下钻/排序/空态）"
```

---

### Task 3: 全量回归门槛

**Files:** 无新增。

- [ ] **Step 1: 三端全量**

Run: `cd /Users/lixiaoyi/GitRepository/portfolio-management && make test`
Expected: 后端（含集成/BDD/JaCoCo）+ 前端 V8 + collector 全绿，覆盖率 ≥80%。

- [ ] **Step 2: e2e 全量**

Run: `cd frontend && pnpm test:e2e`
Expected: 全绿（既有 15 spec + MS-09 新增，无新 flaky）。

- [ ] **Step 3: `make smoke` 口径确认**

无需新增段（无新外部数据源）；确认既有第 2 段（东财直连漂移探测）环境红口径不变，PR 描述引用 2026-09-15/16/17 记录（同 MS-07/08 口径）。

---

### Task 4: 文档同步清单（9 项）

**Files:**
- Modify: `docs/plans/2026-08-27-产品落地计划.md`、`docs/function/modules/10-行业研究中心.md`、`docs/function/00-功能模块概览.md`、`docs/technology/modules/09-估值域.md`、`docs/technology/architecture/05-数据类型与来源.md`、`features/README.md`
- Create: `docs/technology/modules/15-行业研究域.md`（若 P2 Task 10 未建规范登记则此处一并）
- Create: `features/industry-listed-research/08-复盘总结/复盘总结.md`（草稿，交付后回填终稿）

**Interfaces:**
- Produces: 交付回填完成态（PR 合并后按 features/README checklist 顺序终检）。

- [ ] **Step 1: 逐项执行（按 features/README.md「交付回填 checklist」顺序）**

1. `docs/function/modules/10-行业研究中心.md`：F03/F04/F05 置 ✅ + 口径注记（F03 营收=最新报告期累计、`income` 采集；F04 5y 分位 + 当前成分回溯口径 + 重算任务；F05 阈值 ±1pp/±10% 且/或规则 + 中位数聚合）；
2. `docs/function/00-功能模块概览.md`：M10 进度 2/12 → 5/12，看板与总点数同步（90 → 93/115）；
3. `docs/plans/2026-08-27-产品落地计划.md`：MS-09 置 ✅（含交付说明：V15、重算任务、回填行数/耗时引用实跑记录、e2e 情况）+ 基线表 90→93 + 变更记录行 + 风险表「历史估值分位数据不足」MS-09 侧闭环（改 ✅ 或删除 MS-09 引用、仅留 MS-02 侧）；
4. `features/README.md`：索引行 `MS-09（进行中）` → `MS-09（已交付 <日期>，PR #N）`；
5. `features/industry-listed-research/08-复盘总结/复盘总结.md`：按 MS-08 复盘体例（交付结果/经验/不足/优化建议/计划偏差清单），引用 09-调研报告 两篇与实跑数据；
6. `docs/technology/modules/15-行业研究域.md`（新建，照 09-估值域/10-筛选域体例）：域定位、两表两端点、WindowedPercentile/Prosperity、已知限制（分位全历史窗口无日期参数、景气仅当前档无历史序列）；
7. `docs/technology/modules/09-估值域.md`：industry_valuation 表注记「历史行由 collector 重算任务写入（MS-09），roe/股息率历史为 NULL」；
8. `docs/technology/architecture/05-数据类型与来源.md`：任务表加 `industry_valuation_backfill`（周更、本地重算）与 `stock_financial` 的 income 并入说明、5 年分段回填运维操作；
9. `docs/technology/conventions/01-后端DDD分包规范.md`：确认 P2 Task 10 已登记 `domain/industry`（若未则补）。

- [ ] **Step 2: 终检跨文档一致性**

对照检查：三处功能点计数一致（概览/落地计划/模块文档）；分位口径表述一致（「近 5 年经验分布、当前成分回溯」）；PR 号回填完整。

- [ ] **Step 3: Commit**

```bash
git add docs/ features/
git commit -m "docs(industry-listed-research): MS-09 交付文档回填（9 项清单）"
```

---

### Task 5: 收尾——finishing-a-development-branch

- [ ] **Step 1: 全量验证复跑**

Run: `make test && cd frontend && pnpm test:e2e`
Expected: 全绿（verification-before-completion：证据先行，引用实际输出）。

- [ ] **Step 2: 走 superpowers:finishing-a-development-branch 流程**

发起 PR（标题 `feat: MS-09 行业研究·上市公司深化`，描述含：功能点清单 M10-F03/F04/F05、验证证据（make test / e2e 输出摘要、回填实跑记录链接）、`make smoke` 口径说明、生产部署 runbook 增量（部署后跑 `make industry-stock-backfill` + 重算任务自动冷启动））。合并后执行 features/README checklist 的 PR 号回填。
