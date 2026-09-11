import { test, expect, type Page } from "@playwright/test";
import { registerAndApprove, TEST_PASSWORD, uniqueUsername } from "./helpers";

// chat-rich-content 收官 e2e：真实浏览器 → Next 反代 → /agui/run SSE → ChartSpec → ECharts canvas
// 全链路，真实模型（DeepSeek）驱动真实行情工具。对既有模式校准点：
// - registerAndApprove 实际签名为 (page, username, password)（helpers.ts），非单参；
//   且依赖种子管理员（ADMIN_USERNAME/ADMIN_PASSWORD），缺失时与 chat.spec 同款整组跳过。
// - 真实模型门控：根 .env 由 playwright.config.ts 载入，未配置 DEEPSEEK_API_KEY 跳过。
test.describe("聊天流图表卡（chat-rich-content）", () => {
  test.skip(!process.env.DEEPSEEK_API_KEY, "未配置 DEEPSEEK_API_KEY，跳过图表 e2e");
  test.skip(
    !(process.env.ADMIN_USERNAME && process.env.ADMIN_PASSWORD),
    "未配置 ADMIN_USERNAME/ADMIN_PASSWORD（无种子管理员），跳过图表 e2e",
  );

  test.setTimeout(180_000);

  test.beforeEach(async ({ page }) => {
    await registerAndApprove(page, uniqueUsername("chart"), TEST_PASSWORD);
  });

  // chat.spec 同款发送惯例：先 fill 再等发送按钮可用（isReady；disabled={!ready || !draft}）
  async function ask(page: Page, question: string) {
    await page.getByPlaceholder(/问行情、看走势/).fill(question);
    const send = page.getByRole("button", { name: "发送" });
    await expect(send).toBeEnabled({ timeout: 30_000 });
    await send.click();
  }

  // ChartCard complete 图表分支渲染 EChart 裸容器（data-testid，无 .tool-card 外壳——
  // running/table/degrade 分支才有该类，ChartCard.tsx 源码核实），echarts init 在其中创建 canvas。
  // 该 testid 为 ChartCard 专属，不与行情/估值页图表冲突。
  const chartCanvas = (page: Page) => page.locator("[data-testid='chart-card-chart'] canvas");

  test("问个股走势 → K 线烛台卡渲染出 canvas", async ({ page }) => {
    await ask(page, "用工具查一下贵州茅台最近的日K走势，画出K线图");
    await expect(chartCanvas(page).first()).toBeVisible({ timeout: 120_000 });
    // 工具完成后卡片脱离 running 态（FR-9）。chart 卡 complete 即切换为 canvas 分支，
    // .tool-card.running 骨架与之互斥；全局归零同时覆盖同轮 search_stock 等兜底卡。
    // toHaveCount 自带重试——模型链式再调工具会等它结束，30s 内未归零才判失败。
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
    // 表格卡 title div 文本来自 Java 端 ChartSpecs：「%s %s 财务指标」
    const card = page.locator(".tool-card", { hasText: "财务指标" }).first();
    await card.waitFor({ state: "visible", timeout: 120_000 });
    await expect(card.locator("table thead th").first()).toBeVisible();
    expect(await card.locator("table tbody tr").count()).toBeGreaterThan(0);
  });
});
