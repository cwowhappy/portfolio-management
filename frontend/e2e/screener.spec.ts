import { test, expect } from "@playwright/test";
import { registerAndApprove, TEST_PASSWORD, uniqueUsername } from "./helpers";

const hasAdminSeed = !!(process.env.ADMIN_USERNAME && process.env.ADMIN_PASSWORD);

test.describe("/screener 价值筛选器", () => {
  test("公开访问并渲染表单", async ({ page }) => {
    await page.goto("/screener");
    await expect(page.getByText("价值筛选器")).toBeVisible();
    await expect(page.getByText("估值水平")).toBeVisible();
    await expect(page.getByText(/不构成投资建议/)).toBeVisible();
  });

  // 管线级断言（CI e2e 为空库，无行情/成分股数据；数据正确性由后端集成测试覆盖）
  test("自选标签页匿名显示登录引导", async ({ page }) => {
    await page.goto("/screener");
    await page.getByTestId("tab-watchlist").click();
    await expect(page.getByTestId("watchlist-panel")).toBeVisible();
    await expect(page.getByTestId("watchlist-panel")).toContainText(/登录/);
  });

  test("登录后自选标签页渲染搜索与空列表", async ({ page }) => {
    test.skip(!hasAdminSeed, "未配置 ADMIN_USERNAME/ADMIN_PASSWORD，跳过登录用例");
    await registerAndApprove(page, uniqueUsername("wl"), TEST_PASSWORD);
    await page.goto("/screener");
    await page.getByTestId("tab-watchlist").click();
    await expect(page.getByTestId("watchlist-panel")).toBeVisible({ timeout: 15_000 });
    await expect(page.getByPlaceholder("搜索代码或名称添加")).toBeVisible();
    await expect(page.getByTestId("watchlist-panel")).toContainText(/暂无自选/); // 空库无行情数据也成立
  });

  test("指数范围下拉可选并进入筛选；CSV 可下载且文件名带时间戳", async ({ page }) => {
    await page.goto("/screener");
    await expect(page.getByLabel("指数范围")).toBeVisible();
    await page.getByLabel("指数范围").selectOption("000300");

    // PE<999999 保证至少一个条件（空库下结果为空集，仍能导出仅表头 CSV）
    await page.getByLabel("PE-TTM <").fill("999999");
    await page.locator("form button[type=\"submit\"]").click(); // 与「筛选」tab 同名，用 submit 定位

    const download = page.waitForEvent("download");
    await page.getByTestId("export-csv").click();
    const d = await download;
    expect(d.suggestedFilename()).toMatch(/^screening-\d{8}-\d{6}\.csv$/);
  });
});

