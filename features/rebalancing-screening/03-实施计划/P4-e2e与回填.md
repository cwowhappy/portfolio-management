# MS-08 P4 e2e 与文档回填 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Playwright e2e 覆盖再平衡全链路与筛选增强交互；全量回归（`make test` + e2e）；按设计规格 §七清单回填全部文档并收尾里程碑。

**Architecture:** e2e 环境是**空库**（CI 仅 Flyway + 种子管理员，无行情/成分股数据，见 `.github/workflows/ci.yml` e2e job）——因此：再平衡 e2e 走**纯用户数据**路径（现金转入 + 模板方案，零外部数据依赖，数值可精确断言）；筛选增强 e2e 走**管线级**断言（组件渲染/交互/下载事件，与既有 `screener.spec.ts` 同口径），数据正确性已由 P2 Testcontainers 集成测试覆盖。

**Tech Stack:** Playwright（chromium）、Makefile 质量门槛、docs/ 文档体系。

**Spec:** [需求规格说明](../01-需求规格/需求规格说明.md) | [设计规格说明](../02-设计规格/设计规格说明.md) §五验收标准 / §六测试策略 / §七文档同步清单。

## Global Constraints

- e2e 依赖 `ADMIN_USERNAME/ADMIN_PASSWORD`（`registerAndApprove` 需管理员审核，照 `allocation.spec.ts` 的 `test.skip(!hasAdminSeed, ...)` 模式）。
- e2e 不得依赖 `stock_valuation_daily` / `index_constituent` / 实时行情数据（空库 + CI 网络不可控）。
- 全程质量门槛：`make test`（JaCoCo/V8 ≥80%）+ `pnpm test:e2e` 全绿；`make smoke` 无新增段（无新外部数据源，口径同 MS-07，不重跑）。
- 文档回填清单以设计规格 §七为准，逐项勾选。
- 每任务以提交结尾；commit scope `e2e`/`docs`。

---

### Task 1: e2e——再平衡提醒全链路（确定性数值断言）

**Files:**
- Modify: `frontend/e2e/allocation.spec.ts`（追加用例）

**Interfaces:**
- Consumes: P3 的 `data-testid="rebalance-card" / "rebalance-alert" / "rebalance-row-*"`、按钮「已完成再平衡」；`GroupManager` 现金录入控件（`aria-label` 现金账户/转入转出/金额/日期，按钮名「录入」——`GroupManager.tsx:124-164` 已核实）。

- [ ] **Step 1: 写用例（空库可跑：现金 10000 + 永久组合 25×4 → 数值全可手算）**

```typescript
test("再平衡：现金转入后提醒触发、金额建议正确、ack 刷新锚点", async ({ page }) => {
  await registerAndApprove(page, uniqueUsername("rb"), TEST_PASSWORD);

  // 造数据：主账户现金转入 10000（总资产 T=10000，无持仓 → 无行情依赖）
  await page.getByRole("link", { name: "持仓" }).click();
  await expect(page).toHaveURL(/\/portfolio/, { timeout: 15_000 });
  await page.getByPlaceholder("分组名（如 华泰）").fill("主账户");
  await page.getByRole("button", { name: "新建" }).click();
  await expect(page.getByTestId("group-tabs").getByRole("button", { name: "主账户" })).toBeVisible();
  await page.getByLabel("现金账户").selectOption({ label: "主账户" });
  await page.getByLabel("转入转出").selectOption("DEPOSIT");
  await page.getByLabel("金额").fill("10000");
  await page.getByLabel("日期").fill("2026-09-16"); // 日期控件若无默认值则必填，显式填避免提交被拦
  await page.getByRole("button", { name: "录入" }).click();
  await expect(page.getByText(/10,000|10000/)).toBeVisible({ timeout: 15_000 }); // 现金余额出现

  // 配置页：套用永久组合（25×4）并激活
  await page.getByRole("link", { name: "配置" }).click();
  await expect(page).toHaveURL(/\/allocation/, { timeout: 15_000 });
  await page.getByRole("button", { name: "永久组合" }).click();
  await page.getByRole("button", { name: "保存方案" }).click();
  await page.getByTestId("plan-list").getByText("永久组合").waitFor();
  const planRow = page.getByTestId("plan-list").locator("div,li", { hasText: "永久组合" }).first();
  await planRow.getByRole("button", { name: "激活" }).click();

  // 提醒卡：阈值触发（股票实际 0 vs 目标 25）+ 金额断言（T=10000）
  await expect(page.getByTestId("rebalance-alert")).toBeVisible({ timeout: 15_000 });
  await expect(page.getByTestId("rebalance-alert").textContent()).resolves.toContain("股票");
  const stockRow = page.getByTestId("rebalance-row-STOCK");
  await expect(stockRow).toContainText("买入");
  await expect(stockRow).toContainText("2,500");   // 25% × 10000
  const cashRow = page.getByTestId("rebalance-row-CASH");
  await expect(cashRow).toContainText("卖出");
  await expect(cashRow).toContainText("7,500");    // 100% − 25% × 10000

  // 导航红点
  await expect(page.getByTestId("allocation-alert-dot")).toBeVisible();

  // ack：锚点刷新提示出现（卡片显示「上次再平衡」时间），提醒（阈值类）仍在
  await page.getByRole("button", { name: "已完成再平衡" }).click();
  await expect(page.getByTestId("rebalance-card").getByText(/上次再平衡/)).toBeVisible({ timeout: 15_000 });
  await expect(page.getByTestId("rebalance-alert")).toBeVisible(); // 偏离未变，阈值提醒不因 ack 消失
});
```

注意：激活按钮的精确文案/结构以 `PlanList.tsx` 现状为准（执行时 Read 一次校准选择器，`getByRole("button", { name: "激活" })`）；现金余额展示格式以 `GroupManager`/总览实现为准，断言放宽为 `/10,?000/`。

- [ ] **Step 2: 本地跑该用例（后端+前端 dev 起服）**

Run: `cd frontend && pnpm exec playwright test e2e/allocation.spec.ts -g "再平衡"`
Expected: PASS。失败先按 systematic-debugging 排查（多为选择器漂移，禁改产品代码凑断言——除非确认是产品缺陷）。

- [ ] **Step 3: Commit**

```bash
git add frontend/e2e/allocation.spec.ts
git commit -m "test(e2e): 再平衡全链路——现金造数/提醒金额断言/ack/导航红点"
```

---

### Task 2: e2e——筛选增强交互（管线级）

**Files:**
- Modify: `frontend/e2e/screener.spec.ts`（追加用例）

**Interfaces:**
- Consumes: P3 的 `data-testid="tab-watchlist" / "watchlist-panel" / "export-csv"`、`aria-label="指数范围"`、⭐ `aria-label="加自选 *"`。

- [ ] **Step 1: 写用例（空库可跑：不依赖行情/成分股数据）**

```typescript
test("自选标签页与登录引导", async ({ page }) => {
  await page.goto("/screener");
  await page.getByTestId("tab-watchlist").click();
  // 匿名：登录引导态（P3 WatchlistPanel 空库/未登录文案）
  await expect(page.getByTestId("watchlist-panel")).toBeVisible();
  await expect(page.getByTestId("watchlist-panel")).toContainText(/登录/);
});

test("登录后自选标签页渲染搜索与空列表", async ({ page }) => {
  await registerAndApprove(page, uniqueUsername("wl"), TEST_PASSWORD);
  await page.goto("/screener");
  await page.getByTestId("tab-watchlist").click();
  await expect(page.getByTestId("watchlist-panel")).toBeVisible({ timeout: 15_000 });
  await expect(page.getByPlaceholder(/搜索代码或名称/)).toBeVisible();
  await expect(page.getByTestId("watchlist-panel")).toContainText(/暂无自选/); // 空库无行情数据也成立：列表为空
});

test("指数范围下拉与导出按钮（空结果仅表头 CSV 也能下载）", async ({ page }) => {
  await page.goto("/screener");
  await page.getByLabel("指数范围").selectOption("000300");
  // PE<999999 —— 保证至少一个条件（空库下结果为空，仍能导出仅表头 CSV）
  await page.getByLabel("PE-TTM 小于").fill("999999"); // 选择器以 ScreeningForm 实际 label/aria 为准，执行时校准
  await page.getByRole("button", { name: "筛选" }).click();
  const download = page.waitForEvent("download");
  await page.getByTestId("export-csv").click();
  const d = await download;
  expect(d.suggestedFilename()).toMatch(/^screening-\d{8}-\d{6}\.csv$/);
});
```

执行时校准两处选择器：PE 输入与筛选按钮的真实可达名（Read `ScreeningForm.tsx` 后替换注释处）。若导出在空库下因 `NO_CONDITION` 被 400 拒绝——不会：PE 条件已填，`screen()` 校验通过、结果空集仍吐表头行 CSV。

- [ ] **Step 2: 本地跑该 spec**

Run: `cd frontend && pnpm exec playwright test e2e/screener.spec.ts`
Expected: PASS（含既有「公开访问并渲染表单」不回归）。

- [ ] **Step 3: Commit**

```bash
git add frontend/e2e/screener.spec.ts
git commit -m "test(e2e): 筛选增强——自选标签页/登录引导/指数下拉/CSV 下载"
```

---

### Task 3: 全量回归

**Files:** 无新增（验证任务）。

- [ ] **Step 1: 后端全量（含集成/BDD/覆盖率门槛）**

Run: `make test-backend`
Expected: 全绿，JaCoCo ≥80% 门槛通过。

- [ ] **Step 2: 前端全量 + collector（P1/P2 无 collector 改动，跑一遍防意外）**

Run: `make test-frontend && make collect-test`
Expected: 全绿，V8 ≥80%。

- [ ] **Step 3: e2e 全量（前端构建从干净 .next 起跑——见既有教训：e2e 复用旧构建会假绿/假红）**

Run: `cd frontend && rm -rf .next && CI=true pnpm test:e2e`
Expected: 全部 spec 通过（既有 1 例 flaky 重试即过的口径不变）。

- [ ] **Step 4: `make test` 总口径确认**

Run: `make test`
Expected: PASS。若失败，修到绿再进 Task 4（不满足质量门槛不进入里程碑收尾——产品落地计划「一、如何使用本文档」节奏约定）。

- [ ] **Step 5: Commit（若回归中出现修复）**

```bash
git add -A && git commit -m "fix(ms08): 全量回归修复"   # 仅当有修复；无修复跳过本步
```

---

### Task 4: 文档回填与里程碑收尾

**Files（设计规格 §七清单全量）:**
- Modify: `docs/plans/2026-08-27-产品落地计划.md`
- Modify: `docs/function/modules/07-资产组合配置.md`、`docs/function/modules/09-价值投资筛选器.md`
- Modify: `docs/function/00-功能模块概览.md`
- Modify: `features/README.md`（索引行状态改已交付 + PR 号）

- [ ] **Step 1: 模块文档**

`07-资产组合配置.md`：F04/F05 状态 ✅ + 各补「交付说明」段（F04：5pp 固定阈值/方案级周期/页内卡+导航红点/读侧纯函数；F05：T=权益+现金、建议=目标−当前、守恒、BOND/GOLD/REITS 场外口径）；状态行进度 3/6 → 5/6。
`09-价值投资筛选器.md`：F03/F04/F05 状态 ✅ + 交付说明（F03：/api/watchlist 登录、⭐+搜索、上限 100、现价实时混合口径；F04：后端同源+BOM；F05：**口径修正**——「内置成分股静态表」→「collector index_weight 落库（index_constituent 表，180 天全量刷新），本特性纯消费」）；进度 2/6 → 5/6。

- [ ] **Step 2: 看板与落地计划**

`00-功能模块概览.md`：看板 M07/M09 对应功能点置 ✅，全系统已完成 85 → 90（待开发 30 → 25）。
`2026-08-27-产品落地计划.md`：
- MS-08 节：五复选框全勾、状态 ⏳→✅、补交付说明（参照 MS-07 节格式：架构口径、PR 号、质量门槛结果、smoke 口径「无新增段未重跑，既有第 2 段环境红与本里程碑无关」）；
- 「二、当前基线」表：已完成/待开发/整体进度数值更新（≈74% → ≈78%）；
- 「七、里程碑变更记录」追加一行（日期、交付内容、PR 号）；
- 「六、风险与决策点」表：「指数成分股/申万行业静态表更新机制」行改为 ✅ 已解决（成分股 index_constituent 任务 180 天自动刷新；申万映射周更任务——两者均 collector 自动化，无人工静态表）。

- [ ] **Step 3: features/README.md 索引**

`rebalancing-screening` 行：MS-08（进行中）→ MS-08（已交付 YYYY-MM-DD，PR #N）。

- [ ] **Step 4: 提交**

```bash
git add docs/ features/README.md
git commit -m "docs(plans): MS-08 交付回填——五个功能点收口"
```

- [ ] **Step 5: 里程碑状态对账**

对照产品落地计划 MS-08 验收标准逐条勾验并在提交信息或 PR 描述记录：① 偏离度/金额抽验（P1 Task 2/4 已知答案用例）② 提醒触发测试覆盖（同）③ 自选行为（P2 Task 4）④ CSV 同源（P2 Task 6）⑤ 指数限定（P2 Task 1 集成）⑥ make test + e2e（Task 3）。全部满足后 MS-08 收口，可走 finishing-a-development-branch 合并流程。
