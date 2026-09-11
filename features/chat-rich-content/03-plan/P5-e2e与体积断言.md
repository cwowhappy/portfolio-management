# e2e 与体积断言（真实浏览器图表卡 + bundle 守卫） 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 真实浏览器 e2e 钉住聊天流图表卡全链路（浏览器 → Next 反代 → /agui/run SSE → ChartSpec → ECharts canvas）与 500 根 K 线体量；建立 chart 载荷 gzip 体积断言（≤240KB，实测 218KB + 余量）防树摇回归；修复 e2e 复用陈旧 `.next` 的既有坑。

**Architecture:** 新 spec `frontend/e2e/chat-chart.spec.ts` 复用既有三 webServer 基建（MCP/后端/前端）与 `registerAndApprove` 助手，走真实 UI 输入驱动真实模型调用 `get_kline`（DEEPSEEK_API_KEY 门控），断言 `.tool-card` 内出现 ECharts canvas（沿用仓库 CSS class 断言惯例，不引 data-testid）；`scripts/e2e-frontend.sh` 加 `E2E_FRESH_BUILD=1` 新鲜度守卫（既有教训：复用陈旧 .next 会用旧代码跑新断言）；`scripts/assert-chart-bundle.mjs` 用 esbuild+gzip 独立实测 chart 入口体积并接 CI。

**Tech Stack:** Playwright 1.62（既有）· esbuild（新增 devDep，仅体积断言用）· 真实后端 + Postgres（既有 e2e 模式，非 mock）

**Spec:** `features/chat-rich-content/02-design/设计规格说明.md`（§六 P5）与 `docs/technology/research/05-富文本场景技术方案.md`（§六.5、§7.2 树摇风险）。执行者须先读 05 §六/§7.2。

## Global Constraints

- 断言选择器沿用仓库 e2e 惯例：CSS class（`div.tool-card`）+ canvas 存在性，**不引入 data-testid**（与 hitl.spec.ts/chat.spec.ts 一致；05 §六.5 的 data-testid 措辞以此为准修正）。
- e2e 走真实链路：真实模型（DEEPSEEK_API_KEY，.env 由 playwright.config.ts 载入）+ 真实 `get_kline`（akshare 行情）。不可用网络的日子本地可跳过（test.skip 门控），CI 保留。
- 500 根 K 线的**数据体量断言在 P3 集成测试**（SSE payload 与序列化形态）；e2e 只断言「500 根请求下图表卡正常渲染 + 时限内可见」（canvas 内容不可读，不断言根数）。
- 体积断言上限 **245_760 字节（240KB）gzip**：echarts 按需四图+dataZoom 实测 218KB + ~10% 余量；超限即失败（树摇回归的唯一硬门）。
- e2e 前置：本地跑 `E2E_FRESH_BUILD=1`（Task 1 落地后）或手动 `rm -rf frontend/.next`——**陈旧 .next 是既有坑**（旧构建无 ChartCard 会让新断言假失败）。
- 提交信息 conventional + 中文，一个 Task 一个 commit。

## P4 终审携带项（2026-09-12 P4 终审裁定，执行时顺带处理）

1. **DataTable 空列守卫**（一行）：`chart-spec.ts` 的 table columns 加 `.min(1)`（后端契约已保证 ≥1 列，钉进 zod）或 DataTable 用 `table.getHeaderGroups()[0]?.headers ?? []`——当前不可达，防御「聊天流不崩」红线。
2. **`defaultPageSize` 注释过时**：chart-spec.ts 该字段注释改为「预留：卡片内表格可视高度（本期固定 360px 未消费）」。

---

### Task 1: e2e-frontend.sh 新鲜度守卫

**Files:**
- Modify: `scripts/e2e-frontend.sh`

**Interfaces:**
- Produces: env `E2E_FRESH_BUILD=1` → 清 `.next` 全新构建；默认行为不变（有 BUILD_ID 就复用，CI 天然全新）。

- [ ] **Step 1: 修改脚本**

现有 10 行脚本改为：

```bash
#!/usr/bin/env bash
# e2e 前端服务：默认复用 .next 产物；E2E_FRESH_BUILD=1 强制清产物重建。
# 既有教训（mcp-hitl）：复用陈旧 .next 会用旧代码跑新断言 → 图表/渲染器类改动后必须带 E2E_FRESH_BUILD=1。
set -euo pipefail
cd "$(dirname "$0")/../frontend"

if [ "${E2E_FRESH_BUILD:-0}" = "1" ] || [ ! -f .next/BUILD_ID ]; then
  rm -rf .next
  CI=true ./node_modules/.bin/next build
fi
exec ./node_modules/.bin/next start -p 3000
```

（保留脚本原有的 set/cd 行为；若原脚本无 `set -euo pipefail` 与 `cd` 定位则按现状最小增量补 `E2E_FRESH_BUILD` 分支。）

- [ ] **Step 2: 验证两种模式**

```bash
bash -n scripts/e2e-frontend.sh && E2E_FRESH_BUILD=0 bash -c 'grep -q "E2E_FRESH_BUILD" scripts/e2e-frontend.sh' && echo OK
```

（完整构建验证并入 Task 2 的 e2e 运行：首次本地跑 e2e 时显式 `E2E_FRESH_BUILD=1 make test-e2e`。）

- [ ] **Step 3: Commit**

```bash
git add scripts/e2e-frontend.sh
git commit -m "chore(e2e): e2e-frontend.sh 支持 E2E_FRESH_BUILD 强制重建（陈旧 .next 防坑）"
```

---

### Task 2: 图表卡 e2e（K 线 + 500 根体量）

**Files:**
- Test: `frontend/e2e/chat-chart.spec.ts`

**Interfaces:**
- Consumes: `frontend/e2e/helpers.ts` 的 `registerAndApprove`/`uniqueUsername`（**必须用 `page.request`**——与浏览器共享 cookie）；既有 UI 驱动惯例（占位符 `/问行情、看走势/` 定位输入框、`发送` 按钮、`■ 停止` 判运行态）；playwright.config 三 webServer 自动拉起
- Produces: 图表卡全链路守护；P2-P4 全部前端行为的端到端证据。

- [ ] **Step 1: 写 spec**

```ts
import { test, expect } from "@playwright/test";
import { registerAndApprove } from "./helpers";

// 真实模型门控（chat.spec 同款；.env 由 playwright.config.ts 载入）
test.skip(!process.env.DEEPSEEK_API_KEY, "未配置 DEEPSEEK_API_KEY，跳过图表 e2e");

test.setTimeout(180_000);

test.describe("聊天流图表卡（chat-rich-content）", () => {
  test.beforeEach(async ({ page }) => {
    await registerAndApprove(page);
  });

  async function ask(page: import("@playwright/test").Page, question: string) {
    const input = page.getByPlaceholder(/问行情、看走势/);
    await input.fill(question);
    const send = page.getByRole("button", { name: "发送" });
    await expect(send).toBeEnabled({ timeout: 30_000 });
    await send.click();
  }

  const chartCanvas = (page: import("@playwright/test").Page) =>
    // ChartCard 外壳 .tool-card，内部 EChart 容器 data-testid=chart-card-chart，echarts init 产出 canvas
    page.locator(".tool-card [data-testid='chart-card-chart'] canvas");

  test("问个股走势 → K 线烛台卡渲染出 canvas", async ({ page }) => {
    await ask(page, "用工具查一下贵州茅台最近的日K走势");
    await expect(chartCanvas(page).first()).toBeVisible({ timeout: 120_000 });
    // 工具完成后卡片脱离 running 态（FR-9）
    await expect(page.locator(".tool-card.running")).toHaveCount(0, { timeout: 30_000 });
  });

  test("500 根 K 线体量下图表卡仍正常渲染", async ({ page }) => {
    await ask(page, "用工具查贵州茅台最近500天的日K线，画出图表");
    await expect(chartCanvas(page).first()).toBeVisible({ timeout: 120_000 });
  });

  test("问市场估值 → PE/PB 折线卡渲染出 canvas", async ({ page }) => {
    await ask(page, "现在全市场估值什么水平？查一下估值并给我看走势图");
    await expect(chartCanvas(page).first()).toBeVisible({ timeout: 120_000 });
  });

  test("问财务指标 → 交互表格卡（th 表头 + 行）", async ({ page }) => {
    await ask(page, "用工具查贵州茅台的财务指标");
    const card = page.locator(".tool-card", { hasText: "财务指标" });
    await card.waitFor({ state: "visible", timeout: 120_000 });
    await expect(card.locator("table thead th").first()).toBeVisible();
    expect(await card.locator("table tbody tr").count()).toBeGreaterThan(0);
  });
});
```

（模型是否调工具取决于提示词——问题里明确「用工具查」提高命中率；若稳定率不足，加 `test.retry` 或把断言超时上调，**不得**改成直发 SSE 绕过 UI。）

- [ ] **Step 2: 本地跑（先清产物，钉住新渲染代码）**

```bash
cd frontend && E2E_FRESH_BUILD=1 CI=true pnpm test:e2e -- --grep "聊天流图表卡"
```

预期：4 用例 PASS（真实模型 + 真实行情；首轮跑通后观察稳定性，flaky 的用例加 `test.fail` 注记并单独修，不整体放宽）。

- [ ] **Step 3: Commit**

```bash
git add frontend/e2e/chat-chart.spec.ts
git commit -m "test(e2e): 真实浏览器钉住聊天流图表卡——K线/500根/折线/表格（chat-rich-content）"
```

---

### Task 3: chart 载荷 gzip 体积断言 + CI 接线

**Files:**
- Create: `frontend/scripts/chart-bundle-entry.ts`（断言入口，非业务代码）
- Create: `frontend/scripts/assert-chart-bundle.mjs`
- Modify: `frontend/package.json`（devDep esbuild + script `test:bundle`）
- Modify: `.github/workflows/ci.yml`（frontend job 追加一步）

**Interfaces:**
- Produces: `pnpm -C frontend test:bundle` → 失败（exit 1）当 chart 入口 gzip > 245_760 字节；CI 防树摇回归（05 §7.2「CI bundle 体积断言」落地）。

- [ ] **Step 1: 安装 esbuild + 写断言脚本（TDD：脚本自身先跑一遍红/绿）**

```bash
cd frontend && pnpm add -D esbuild
```

`frontend/scripts/chart-bundle-entry.ts`：

```ts
// 体积断言入口（非业务代码）：把 chart 管线的全部运行时依赖钉进一个 bundle。
import { echarts } from "../lib/echarts-setup";
import * as builders from "../components/charts/optionBuilders";
// 引用防树摇：esbuild 对未被使用的 import 会保留模块副作用，此处显式引用双保险
if (typeof echarts.use !== "function" || Object.keys(builders).length < 4) {
  throw new Error("chart-bundle-entry 引用不完整");
}
```

`frontend/scripts/assert-chart-bundle.mjs`：

```js
#!/usr/bin/env node
// chart 载荷体积断言：esbuild minify bundle + gzip（与 05 §5.2 的 218KB 实测同方法）。
// 上限 240KB = 实测 218KB + ~10% 余量；超限 = 树摇回归（全量入口 379KB）。
import { build } from "esbuild";
import { gzipSync } from "node:zlib";

const LIMIT_BYTES = 245_760; // 240 * 1024

const result = await build({
  entryPoints: ["scripts/chart-bundle-entry.ts"],
  bundle: true,
  minify: true,
  write: false,
  format: "esm",
  target: "es2020",
  logLevel: "silent",
});
const js = result.outputFiles[0].text;
const gzipBytes = gzipSync(Buffer.from(js), { level: 9 }).length;
const kb = (gzipBytes / 1024).toFixed(1);
if (gzipBytes > LIMIT_BYTES) {
  console.error(`chart bundle gzip ${kb}KB > 上限 240KB（按需实测基线 218KB）——疑似全量入口/树摇回归，检查 no-restricted-imports 守卫`);
  process.exit(1);
}
console.log(`chart bundle gzip ${kb}KB ≤ 240KB ✓`);
```

`package.json` scripts 追加：`"test:bundle": "node scripts/assert-chart-bundle.mjs"`。

- [ ] **Step 2: 跑断言（应绿；人为破坏性验证守卫可选）**

```bash
cd frontend && pnpm test:bundle
```

预期：输出 `chart bundle gzip ≤ 240KB ✓`（数值应在 218KB 附近）。可选项验证守卫有效：临时在 entry 加 `import "echarts"` → 跑一次应红（379KB 量级）→ 撤销（eslint 也会拦，但断言是最后一道门）。

- [ ] **Step 3: CI 接线**

`.github/workflows/ci.yml` 的 frontend job（`pnpm test` 之后、e2e 之前）追加：

```yaml
      - name: chart bundle 体积断言
        run: pnpm -C frontend test:bundle
```

（以 ci.yml 既有 step 风格为准插入；若 frontend 测试 job 用 working-directory 写法则对齐。）

- [ ] **Step 4: Commit**

```bash
git add frontend/scripts/chart-bundle-entry.ts frontend/scripts/assert-chart-bundle.mjs frontend/package.json frontend/pnpm-lock.yaml .github/workflows/ci.yml
git commit -m "test(bundle): chart 载荷 gzip ≤240KB 断言并接 CI（树摇守卫最后一道门）"
```

---

### Task 4: 全量回归 + 文档回填

- [ ] **Step 1: 三端全量**

```bash
cd backend && ./gradlew check
cd ../frontend && pnpm test && pnpm test:bundle && pnpm eslint . && pnpm build
E2E_FRESH_BUILD=1 CI=true pnpm test:e2e
```

预期：全绿（e2e 含既有 14 spec + chat-chart.spec.ts 不回归）。

- [ ] **Step 2: 回填设计规格「验证与文档」节**

`features/chat-rich-content/02-design/设计规格说明.md` §六「真机验收记录：实施后回填本节」替换为实测记录：e2e 用例数与结果、bundle 实测 gzip 数值、视觉 QA 结论（P1 Task 10 清单 + 图表卡在暗/亮两主题下的截图结论）、已知限制复核（主题切换已挂图表不重绘、历史回放不做）。

- [ ] **Step 3: Commit**

```bash
git add features/chat-rich-content/02-design/设计规格说明.md
git commit -m "docs(chat-rich-content): 回填真机验收记录（P5 e2e/体积/视觉 QA）"
```

---

## 自查记录（Self-Review）

1. **Spec 覆盖**：设计规格 §六 P5（真实浏览器图表卡 + 500 根压测 + 体积断言 + 先清 .next）→ Task 1（清产物）/Task 2（图表卡 e2e）/Task 3（体积断言）；05 §7.2 树摇风险「ESLint + CI 体积断言」双门 → ESLint（P1 Task 1 已落）+ Task 3；05 §六.5 e2e 策略 → Task 2（data-testid 措辞已按仓库惯例修正为 CSS class 断言，偏差记录在约束节）。
2. **占位符扫描**：无 TBD/TODO；spec/脚本/CI 片段完整（CI step 以既有风格为准的指引是引用性约束非占位）。
3. **类型一致性**：断言选择器 `.tool-card [data-testid='chart-card-chart'] canvas` 与 P2 Task 5 的 EChart `testid="chart-card-chart"` 及 `.tool-card` 外壳一致；表格卡断言 `hasText: "财务指标"` 与 P3 `ChartSpecs.financialsTable` 的 title「%s %s 财务指标」一致；`E2E_FRESH_BUILD` 在 Task 1 定义、Task 2/4 使用。
4. **已知偏差**：500 根根数断言移至 P3 集成测试（canvas 不可读）；hitl 式 CSS class 断言替代 05 的 data-testid 措辞——两处均已在约束节注明理由。
