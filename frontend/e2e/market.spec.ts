import { test, expect } from "@playwright/test";

test.describe.serial("行情台", () => {
  // 串行跑避免并发打真实行情接口触发限流；抬超时让 60s 的 expect 可达（默认 30s 会提前掐断）
  test.setTimeout(120_000);
  test("大盘速览加载三大指数", async ({ page }) => {
    await page.goto("/market");
    // 真实东财/新浪指数接口，超时放宽
    await expect(page.getByText("上证指数")).toBeVisible({ timeout: 60_000 });
    await expect(page.getByText("深证成指")).toBeVisible({ timeout: 60_000 });
    await expect(page.getByText("创业板指")).toBeVisible({ timeout: 60_000 });
  });

  test("搜索股票并查看报价、K线、财务与新闻", async ({ page }) => {
    await page.goto("/market");
    const input = page.getByPlaceholder(/输入股票名称或代码搜索/);
    await input.fill("600519");

    // 等待搜索结果（真实东财搜索接口）
    const hit = page.getByRole("button", { name: /贵州茅台/ });
    await expect(hit).toBeVisible({ timeout: 60_000 });
    await hit.click();

    // 选中后：报价头 + K线 + 财务 + 新闻 四区依次渲染
    await expect(page.getByText("走势 · 前复权")).toBeVisible({ timeout: 60_000 });
    await expect(page.getByText("财务指标")).toBeVisible({ timeout: 60_000 });
    await expect(page.getByText("近期新闻")).toBeVisible({ timeout: 60_000 });
    // 报价头含股票名称与代码
    await expect(page.getByText(/600519/).first()).toBeVisible({ timeout: 60_000 });
  });

  test("切换K线周期", async ({ page }) => {
    await page.goto("/market");
    const input = page.getByPlaceholder(/输入股票名称或代码搜索/);
    await input.fill("600519");
    await page.getByRole("button", { name: /贵州茅台/ }).click();
    await expect(page.getByText("走势 · 前复权")).toBeVisible({ timeout: 60_000 });

    // 周K / 月K 切换不报错
    await page.getByRole("button", { name: "周K" }).click();
    await expect(page.getByRole("button", { name: "周K" })).toBeVisible();
    await page.getByRole("button", { name: "月K" }).click();
    await expect(page.getByRole("button", { name: "月K" })).toBeVisible();
  });
});
