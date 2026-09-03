import { test, expect } from "@playwright/test";
import { registerAndApprove, TEST_PASSWORD, uniqueUsername } from "./helpers";

// 首页（/）要求已审核用户登录；注册后须由种子管理员审核，故依赖 ADMIN_USERNAME/ADMIN_PASSWORD。
const hasAdminSeed = !!(process.env.ADMIN_USERNAME && process.env.ADMIN_PASSWORD);

test.describe("应用导航", () => {
  test.skip(!hasAdminSeed, "未配置 ADMIN_USERNAME/ADMIN_PASSWORD（无种子管理员），跳过导航用例");

  test("首页标题与顶部导航", async ({ page }) => {
    await registerAndApprove(page, uniqueUsername("nav"), TEST_PASSWORD);
    await expect(page).toHaveTitle(/九和/);
    await expect(page.getByRole("link", { name: "对话" })).toBeVisible();
    await expect(page.getByRole("link", { name: "行情台" })).toBeVisible();
  });

  test("导航到行情台", async ({ page }) => {
    await registerAndApprove(page, uniqueUsername("nav"), TEST_PASSWORD);
    await page.getByRole("link", { name: "行情台" }).click();
    await expect(page).toHaveURL(/\/market/);
    await expect(page.getByPlaceholder(/输入股票名称或代码搜索/)).toBeVisible();
  });
});
