import { test, expect, type APIRequestContext } from "@playwright/test";

test.describe("/industry 行业估值", () => {
  test("公开访问并渲染对比表与热力图", async ({ page }) => {
    await page.goto("/industry");
    await expect(page.getByRole("heading", { name: "行业估值" })).toBeVisible();
    await expect(page.getByText("行业估值对比")).toBeVisible();
    await expect(page.getByText("估值热力图")).toBeVisible();
  });
});

/**
 * MS-09 用例依赖回填数据（industry_valuation / stock_valuation_daily / 成分映射）。
 * CI e2e 起的是空库（ci.yml e2e job 仅 Flyway 迁移 + 种子管理员），板面为空时行点击/下钻
 * 无从发生——探测板面接口为空则跳过，与 ADMIN_USERNAME / DEEPSEEK_API_KEY 门控同范式。
 * 接口非 200 时不门控（返回 true），让用例以可见失败暴露真实回归，避免空转绿灯。
 */
async function boardHasData(request: APIRequestContext): Promise<boolean> {
  const res = await request.get("/api/industry/board");
  if (!res.ok()) return true;
  const body: unknown = await res.json();
  return !(Array.isArray(body) && body.length === 0);
}

const SKIP_NO_DATA = "库中无行业板面数据（CI 空库），跳过 MS-09 数据依赖用例";

test.describe("/industry 行业研究（MS-09）", () => {
  test("board 渲染分位列与景气列", async ({ page, request }) => {
    test.skip(!(await boardHasData(request)), SKIP_NO_DATA);
    await page.goto("/industry");
    const table = page.getByTestId("industry-board-table");
    await expect(table).toBeVisible({ timeout: 15_000 });
    await expect(table.getByText("PE 5y分位")).toBeVisible();
    await expect(table.getByText("PB 5y分位")).toBeVisible();
    await expect(table.getByText("景气")).toBeVisible();
    // 回填后至少一行行业名（申万一级固定含银行），且分位格有数值（xx.x%）而非全「—」
    await expect(table.getByRole("button", { name: /银行/ })).toBeVisible();
    await expect(table.getByText(/\d+\.\d+%/).first()).toBeVisible();
  });

  test("行点击进入下钻页并渲染成员排名", async ({ page, request }) => {
    test.skip(!(await boardHasData(request)), SKIP_NO_DATA);
    await page.goto("/industry");
    await page.getByTestId("industry-board-table").getByRole("button", { name: /银行/ }).click();
    await page.waitForURL(/\/industry\/801780/);
    const stocks = page.getByTestId("industry-stocks-table");
    await expect(stocks).toBeVisible({ timeout: 15_000 });
    await expect(stocks.getByText(/成员排名（\d+/)).toBeVisible();
    await expect(stocks.getByText(/总市值\(亿\)/)).toBeVisible();
  });

  test("下钻页排序切换", async ({ page, request }) => {
    test.skip(!(await boardHasData(request)), SKIP_NO_DATA);
    await page.goto("/industry");
    await page.getByTestId("industry-board-table").getByRole("button", { name: /银行/ }).click();
    await page.waitForURL(/\/industry\/801780/);
    const stocks = page.getByTestId("industry-stocks-table");
    await expect(stocks).toBeVisible({ timeout: 15_000 });
    const firstRow = () => stocks.getByRole("row").nth(1);
    const before = (await firstRow().textContent()) ?? "";
    // 默认 total_mv DESC；点击表头切 ASC：表头出现「↑」，首行由最大市值换为最小市值成员
    await stocks.getByText(/总市值\(亿\)/).click();
    await expect(stocks.getByText(/总市值\(亿\)\s*↑/)).toBeVisible();
    await expect(firstRow()).not.toHaveText(before);
  });

  test("直接访问下钻页渲染成员表与返回链接", async ({ page, request }) => {
    test.skip(!(await boardHasData(request)), SKIP_NO_DATA);
    // 深链不经过板面点击：成员表直接可用，页头行业名仍经板面接口解析，返回链接指向 /industry
    await page.goto("/industry/801780");
    const stocks = page.getByTestId("industry-stocks-table");
    await expect(stocks).toBeVisible({ timeout: 15_000 });
    await expect(stocks.getByText(/成员排名（\d+/)).toBeVisible();
    await expect(page.getByRole("heading", { name: /银行/ })).toBeVisible();
    await expect(page.getByText(/← 行业榜单/)).toBeVisible();
  });
});
