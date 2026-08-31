import { test, expect } from "@playwright/test";
import { registerAndApprove, TEST_PASSWORD, uniqueUsername } from "./helpers";

const hasAdminSeed = !!(process.env.ADMIN_USERNAME && process.env.ADMIN_PASSWORD);

test.describe("/allocation 资产配置", () => {
  test.describe.configure({ retries: 0 });
  test.skip(!hasAdminSeed, "未配置 ADMIN_USERNAME/ADMIN_PASSWORD（无种子管理员），跳过配置用例");

  test("登录后访问并套用模板创建方案", async ({ page }) => {
    await registerAndApprove(page, uniqueUsername("al"), TEST_PASSWORD);

    await page.getByRole("link", { name: "配置" }).click();
    await expect(page).toHaveURL(/\/allocation/, { timeout: 15_000 });
    await expect(page.getByRole("heading", { name: "资产配置" })).toBeVisible();
    await expect(page.getByTestId("deviation-chart")).toContainText("暂无生效方案");

    await page.getByRole("button", { name: "60/40 股债平衡" }).click();
    await page.getByRole("button", { name: "保存方案" }).click();

    await expect(page.getByTestId("plan-list").getByText("60/40 股债平衡")).toBeVisible({ timeout: 15_000 });
  });
});
